package io.amscotti.bravesearch.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.bravehttp.json.UpstreamJsonCodec;
import io.amscotti.bravesearch.api.internal.OpenedAnswersStream;
import io.amscotti.bravesearch.domain.answer.AnswerStreamEvent;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.error.UpstreamError;
import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.request.AnswersRequest;
import io.amscotti.bravesearch.domain.request.ContextRequest;
import io.amscotti.bravesearch.domain.request.ImageSearchRequest;
import io.amscotti.bravesearch.domain.request.NewsSearchRequest;
import io.amscotti.bravesearch.domain.request.PlaceEnrichmentRequest;
import io.amscotti.bravesearch.domain.request.PlaceSearchRequest;
import io.amscotti.bravesearch.domain.request.RichRequest;
import io.amscotti.bravesearch.domain.request.SpellcheckRequest;
import io.amscotti.bravesearch.domain.request.SuggestRequest;
import io.amscotti.bravesearch.domain.request.VideoSearchRequest;
import io.amscotti.bravesearch.domain.request.WebSearchRequest;
import io.amscotti.bravesearch.domain.result.AnswersResult;
import io.amscotti.bravesearch.domain.result.ContextResult;
import io.amscotti.bravesearch.domain.result.ImageSearchResult;
import io.amscotti.bravesearch.domain.result.NewsSearchResult;
import io.amscotti.bravesearch.domain.result.PlaceEnrichmentResult;
import io.amscotti.bravesearch.domain.result.PlaceSearchResult;
import io.amscotti.bravesearch.domain.result.RichResult;
import io.amscotti.bravesearch.domain.result.SpellcheckSearchResult;
import io.amscotti.bravesearch.domain.result.SuggestSearchResult;
import io.amscotti.bravesearch.domain.result.VideoSearchResult;
import io.amscotti.bravesearch.domain.result.WebSearchResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The public facade contract: every endpoint delegates to its bound exchange and converts a
 * success into the typed response with its caller-owned upstream snapshot, an unparseable
 * body is the malformed outcome instead of an exception, failures pass through unchanged,
 * endpoint-specific validations reject mismatched requests up front, the streaming wiring is
 * expressed purely in public handle types, naturally terminated streams release their client
 * reference without close, and closing cancels live stream handles, releases each owned
 * resource exactly once, and makes further calls report the closed-client transport failure.
 */
final class BraveSearchClientTest {

    private static final WebSearchRequest WEB_QUERY = WebSearchRequest.builder("bacon").build();

    private static final AnswersRequest STREAM_QUERY = AnswersRequest.builder("bacon")
            .stream(true)
            .build();

    @Test
    void everyBlockingEndpointDelegatesToItsBoundExchangeAndExposesTheTypedProjection() {
        FixedWiring wiring = new FixedWiring();
        try (BraveSearchClient client = wiring.client()) {

            WebSearchResponse web = valueOf(client.webSearch(WEB_QUERY));
            ContextResponse context = valueOf(client.llmContext(ContextRequest.builder("bacon").build()));
            NewsSearchResponse news = valueOf(client.newsSearch(NewsSearchRequest.builder("bacon").build()));
            VideoSearchResponse videos = valueOf(client.videoSearch(VideoSearchRequest.builder("bacon").build()));
            ImageSearchResponse images = valueOf(client.imageSearch(ImageSearchRequest.builder("bacon").build()));
            SuggestResponse suggest = valueOf(client.suggest(SuggestRequest.builder("bacon").build()));
            SpellcheckResponse spellcheck = valueOf(client.spellcheck(SpellcheckRequest.builder("bacon").build()));
            PlaceSearchResponse places = valueOf(client.placeSearch(PlaceSearchRequest.builder().query("bacon").build()));
            PlaceEnrichmentResponse details = valueOf(
                    client.placeDetails(new PlaceEnrichmentRequest(PlaceEnrichmentRequest.Kind.DETAILS, List.of("id-1"))));
            PlaceEnrichmentResponse descriptions = valueOf(client.describePlaces(
                    new PlaceEnrichmentRequest(PlaceEnrichmentRequest.Kind.DESCRIPTIONS, List.of("id-1"))));
            RichResponse rich = valueOf(client.rich(new RichRequest("callback-key")));
            AnswersResponse answers = valueOf(client.answers(AnswersRequest.builder("bacon").build()));

            assertEquals("web", upstreamOf(web).path("web").path("endpoint").stringValue());
            assertEquals("suggest", upstreamOf(suggest).path("web").path("endpoint").stringValue());
            assertEquals("enrichment", upstreamOf(details).path("web").path("endpoint").stringValue());
            assertEquals("enrichment", upstreamOf(descriptions).path("web").path("endpoint").stringValue());
            assertEquals("answers", upstreamOf(answers).path("web").path("endpoint").stringValue());
            assertEquals(200, web.httpStatus());
            assertEquals("req-context", context.requestId());
            assertEquals("req-news", news.requestId());
            assertEquals("req-videos", videos.requestId());
            assertEquals("req-images", images.requestId());
            assertEquals("req-spellcheck", spellcheck.requestId());
            assertEquals("req-places", places.requestId());
            assertEquals("req-rich", rich.requestId());
            assertEquals(200, answers.httpStatus());
            assertEquals(12, wiring.calls.get(), "each endpoint reached its bound exchange exactly once");
        }
    }

    @Test
    void upstreamSnapshotsAreIndependentCallerOwnedCopies() {
        FixedWiring wiring = new FixedWiring();
        try (BraveSearchClient client = wiring.client()) {

            WebSearchResponse response = valueOf(client.webSearch(WEB_QUERY));

            JsonNode first = response.upstream();
            JsonNode second = response.upstream();
            ((ObjectNode) first).put("injected", "mine");

            assertTrue(second.path("injected").isMissingNode(), "one snapshot never sees another's mutation");
            assertTrue(response.upstream().path("injected").isMissingNode(), "later snapshots stay pristine");
            assertEquals("web", response.upstream().path("web").path("endpoint").stringValue());
        }
    }

    @Test
    void anUnparseableUpstreamBodyIsTheMalformedOutcome() {
        FixedWiring wiring = new FixedWiring();
        wiring.reader = body -> {
            throw new tools.jackson.core.JacksonException("not one JSON document") {};
        };
        try (BraveSearchClient client = wiring.client()) {

            Outcome<WebSearchResponse> outcome = client.webSearch(WEB_QUERY);

            assertEquals(
                    FailureKind.MALFORMED,
                    ((Outcome.Failure<WebSearchResponse>) outcome).kind(),
                    "an unparseable body never escapes as an exception");
        }
    }

    @Test
    void failureOutcomesPassThroughUnchanged() {
        UpstreamPayload errorBody = new UpstreamPayload("{\"error\":\"limited\"}".getBytes(UTF_8));
        UpstreamError upstreamError = new UpstreamError("rate", null, errorBody);
        RateLimitSnapshot limits = RateLimitSnapshot.empty();
        Outcome<WebSearchResult> bound =
                new Outcome.Failure<>(FailureKind.RATE_LIMITED, "rate limited", upstreamError, limits, 429);
        FixedWiring wiring = new FixedWiring();
        wiring.webSearch = request -> bound;
        try (BraveSearchClient client = wiring.client()) {

            Outcome.Failure<WebSearchResponse> failure =
                    (Outcome.Failure<WebSearchResponse>) client.webSearch(WEB_QUERY);

            assertEquals(FailureKind.RATE_LIMITED, failure.kind());
            assertEquals("rate limited", failure.diagnostic());
            assertSame(upstreamError, failure.upstream());
            assertSame(limits, failure.rateLimits());
            assertEquals(429, failure.httpStatus());
        }
    }

    @Test
    void thePlaceMethodsRejectTheWrongEnrichmentKind() {
        FixedWiring wiring = new FixedWiring();
        try (BraveSearchClient client = wiring.client()) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.placeDetails(
                            new PlaceEnrichmentRequest(PlaceEnrichmentRequest.Kind.DESCRIPTIONS, List.of("id-1"))));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.describePlaces(
                            new PlaceEnrichmentRequest(PlaceEnrichmentRequest.Kind.DETAILS, List.of("id-1"))));
        }
    }

    @Test
    void answersBlocksOnlyBlockingRequestsAndAnswersStreamStreamsOnlyStreamingOnes() {
        FixedWiring wiring = new FixedWiring();
        try (BraveSearchClient client = wiring.client()) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.answers(AnswersRequest.builder("bacon").stream(true).build()));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.answersStream(AnswersRequest.builder("bacon").build()));
        }
    }

    @Test
    void nullRequestsAreRejected() {
        FixedWiring wiring = new FixedWiring();
        try (BraveSearchClient client = wiring.client()) {
            assertThrows(NullPointerException.class, () -> client.webSearch(null));
            assertThrows(NullPointerException.class, () -> client.llmContext(null));
            assertThrows(NullPointerException.class, () -> client.newsSearch(null));
            assertThrows(NullPointerException.class, () -> client.videoSearch(null));
            assertThrows(NullPointerException.class, () -> client.imageSearch(null));
            assertThrows(NullPointerException.class, () -> client.suggest(null));
            assertThrows(NullPointerException.class, () -> client.spellcheck(null));
            assertThrows(NullPointerException.class, () -> client.placeSearch(null));
            assertThrows(NullPointerException.class, () -> client.placeDetails(null));
            assertThrows(NullPointerException.class, () -> client.describePlaces(null));
            assertThrows(NullPointerException.class, () -> client.rich(null));
            assertThrows(NullPointerException.class, () -> client.answers(null));
            assertThrows(NullPointerException.class, () -> client.answersStream(null));
        }
    }

    @Test
    void useAfterCloseReportsTheClosedClientTransportFailure() {
        FixedWiring wiring = new FixedWiring();
        BraveSearchClient client = wiring.client();
        client.close();

        Outcome<WebSearchResponse> outcome = client.webSearch(WEB_QUERY);

        assertEquals(FailureKind.TRANSPORT, ((Outcome.Failure<WebSearchResponse>) outcome).kind());
        assertEquals("the client is closed", ((Outcome.Failure<WebSearchResponse>) outcome).diagnostic());
        assertEquals(
                FailureKind.TRANSPORT,
                ((Outcome.Failure<AnswersStreamHandle>) client.answersStream(STREAM_QUERY)).kind());
    }

    @Test
    void closeCancelsLiveStreamHandlesAndReleasesOwnedResourcesExactlyOnce() {
        FixedWiring wiring = new FixedWiring();
        CountingResource first = new CountingResource();
        CountingResource second = new CountingResource();
        wiring.ownedResources.add(first);
        wiring.ownedResources.add(second);
        BraveSearchClient client = wiring.client();
        client.answersStream(STREAM_QUERY);

        client.close();
        client.close();

        assertEquals(1, wiring.openedStreams.getFirst().releases.get(), "the live stream released exactly once");
        assertEquals(0, client.liveStreamCount(), "closing drains the client's live-stream tracking");
        assertEquals(1, first.closes.get(), "each owned resource closed exactly once");
        assertEquals(1, second.closes.get());
    }

    @Test
    void aStreamOpenSpanningCloseReleasesItsHandleAndReportsTheClosedClient() throws InterruptedException {
        FixedWiring wiring = new FixedWiring();
        CountDownLatch openStarted = new CountDownLatch(1);
        CountDownLatch allowOpenToFinish = new CountDownLatch(1);
        Function<AnswersRequest, Outcome<AnswersStreamHandle>> bound = wiring.answersStream;
        wiring.answersStream = request -> {
            openStarted.countDown();
            try {
                allowOpenToFinish.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return bound.apply(request);
        };
        BraveSearchClient client = wiring.client();
        AtomicReference<Outcome<AnswersStreamHandle>> opened = new AtomicReference<>();
        Thread opener = new Thread(() -> opened.set(client.answersStream(STREAM_QUERY)), "racing-stream-open");
        opener.start();
        assertTrue(openStarted.await(5, TimeUnit.SECONDS), "the open began before close drained the live streams");
        client.close();
        allowOpenToFinish.countDown();
        opener.join(10_000);

        Outcome.Failure<AnswersStreamHandle> failure = (Outcome.Failure<AnswersStreamHandle>) opened.get();
        assertEquals(
                FailureKind.TRANSPORT, failure.kind(), "an open that lost the race with close reports the closed client");
        assertEquals("the client is closed", failure.diagnostic());
        assertEquals(
                1,
                wiring.openedStreams.getFirst().releases.get(),
                "the raced-open stream releases its own resources instead of leaking past close");
        assertEquals(0, client.liveStreamCount(), "no handle registers after close drained the client");
    }

    @Test
    void aFailingStreamReleaseStillClosesEveryRemainingStreamAndOwnedResource() {
        FixedWiring wiring = new FixedWiring();
        CountingResource resource = new CountingResource();
        wiring.ownedResources.add(resource);
        AtomicInteger openedCount = new AtomicInteger();
        ReleasingHandle first = new ReleasingHandle("first release failed");
        ReleasingHandle second = new ReleasingHandle(null);
        wiring.answersStream = request ->
                new Outcome.Success<>(openedCount.getAndIncrement() == 0 ? first : second);
        BraveSearchClient client = wiring.client();
        valueOf(client.answersStream(STREAM_QUERY));
        valueOf(client.answersStream(STREAM_QUERY));

        IllegalStateException propagated = assertThrows(IllegalStateException.class, client::close);

        assertEquals("first release failed", propagated.getMessage(), "the first release failure propagates");
        assertEquals(1, first.closes.get(), "the failing stream still closed exactly once");
        assertEquals(1, second.closes.get(), "the stream after the failing one still closes");
        assertEquals(1, resource.closes.get(), "the owned resources still release after a stream release failed");
        assertEquals(0, client.liveStreamCount(), "closing still drains the live-stream tracking");
    }

    @Test
    void theHandleMemoizesOneFramesPublisherAndCancelsThroughThePublicCancellation() {
        FixedWiring wiring = new FixedWiring();
        try (BraveSearchClient client = wiring.client()) {
            AnswersStreamHandle handle = valueOf(client.answersStream(STREAM_QUERY));

            assertSame(handle.frames(), handle.frames(), "frames() hands out the one publisher");
            assertNotNull(handle.cancellation());

            handle.cancellation().cancel();

            assertTrue(handle.cancellation().cancelled(), "the public cancellation observes itself");
            assertEquals(
                    1,
                    wiring.openedStreams.getFirst().releases.get(),
                    "cancellation releases the stream exactly once");
        }
    }

    @Test
    void naturallyTerminatedStreamsReleaseTheirClientReferenceWithoutClose() {
        FixedWiring wiring = new FixedWiring();
        try (BraveSearchClient client = wiring.client()) {
            List<AnswersStreamHandle> handles = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                handles.add(valueOf(client.answersStream(STREAM_QUERY)));
            }
            assertEquals(8, client.liveStreamCount(), "each opened stream is tracked until it terminates");

            for (int index = 0; index < handles.size(); index++) {
                CollectingSubscriber subscriber = new CollectingSubscriber();
                handles.get(index).frames().subscribe(subscriber);
                wiring.openedStreams.get(index).emitAnswerAndComplete();
            }

            assertTrue(
                    spinUntil(() -> client.liveStreamCount() == 0),
                    "streams that completed naturally release their client reference without close; still live: "
                            + client.liveStreamCount());
        }
    }

    @Test
    void theBuilderRequiresAnExplicitTokenSupplierAndPositiveBudgets() {
        assertThrows(
                IllegalStateException.class,
                BraveSearchClient.builder()::build,
                "no token supplier means no client: the library never reads the environment");
        assertThrows(IllegalStateException.class, () -> BraveSearchClient.builder()
                .baseUrl("http://127.0.0.1:1")
                .connectTimeout(Duration.ofSeconds(2))
                .build());
        assertThrows(IllegalArgumentException.class, () -> BraveSearchClient.builder()
                .tokenSupplier(() -> null)
                .connectTimeout(Duration.ZERO)
                .build());
        assertThrows(IllegalArgumentException.class, () -> BraveSearchClient.builder()
                .tokenSupplier(() -> null)
                .totalTimeout(Duration.ofSeconds(-1))
                .build());
        assertThrows(NullPointerException.class, () -> BraveSearchClient.builder().tokenSupplier(null));
    }

    private static boolean spinUntil(java.util.function.BooleanSupplier condition) {
        for (int attempt = 0; attempt < 1_000 && !condition.getAsBoolean(); attempt++) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return condition.getAsBoolean();
    }

    private static <T> T valueOf(Outcome<T> outcome) {
        return switch (outcome) {
            case Outcome.Success<T> success -> success.value();
            case Outcome.Failure<T> failure -> throw new AssertionError(
                    "expected success, got " + failure.kind() + ": " + failure.diagnostic());
        };
    }

    private static JsonNode upstreamOf(WebSearchResponse response) {
        return response.upstream();
    }

    private static JsonNode upstreamOf(SuggestResponse response) {
        return response.upstream();
    }

    private static JsonNode upstreamOf(PlaceEnrichmentResponse response) {
        return response.upstream();
    }

    private static JsonNode upstreamOf(AnswersResponse response) {
        return response.upstream();
    }

    /** Fixed exchange wiring: every endpoint answers with a success carrying its own marker body. */
    private static final class FixedWiring {

        final AtomicInteger calls = new AtomicInteger();

        final List<ManualStream> openedStreams = new ArrayList<>();

        final List<AutoCloseable> ownedResources = new ArrayList<>();

        Function<UpstreamPayload, JsonNode> reader = UpstreamJsonCodec::readTree;

        Function<WebSearchRequest, Outcome<WebSearchResult>> webSearch = request -> success("web");

        Function<ContextRequest, Outcome<ContextResult>> llmContext = request -> success("context");

        Function<NewsSearchRequest, Outcome<NewsSearchResult>> newsSearch = request -> success("news");

        Function<VideoSearchRequest, Outcome<VideoSearchResult>> videoSearch = request -> success("videos");

        Function<ImageSearchRequest, Outcome<ImageSearchResult>> imageSearch = request -> success("images");

        Function<SuggestRequest, Outcome<SuggestSearchResult>> suggest = request -> success("suggest");

        Function<SpellcheckRequest, Outcome<SpellcheckSearchResult>> spellcheck = request -> success("spellcheck");

        Function<PlaceSearchRequest, Outcome<PlaceSearchResult>> placeSearch = request -> success("places");

        Function<PlaceEnrichmentRequest, Outcome<PlaceEnrichmentResult>> placeEnrichment =
                request -> success("enrichment");

        Function<RichRequest, Outcome<RichResult>> rich = request -> success("rich");

        Function<AnswersRequest, Outcome<AnswersResult>> answers = request -> success("answers");

        Function<AnswersRequest, Outcome<AnswersStreamHandle>> answersStream = request -> {
            calls.incrementAndGet();
            ManualStream stream = new ManualStream();
            openedStreams.add(stream);
            return new Outcome.Success<>(new ApiAnswersStreamHandle(stream.opened(), UpstreamJsonCodec::readTagPayload));
        };

        BraveSearchClient client() {
            return new BraveSearchClient(
                    counting(webSearch),
                    counting(llmContext),
                    counting(newsSearch),
                    counting(videoSearch),
                    counting(imageSearch),
                    counting(suggest),
                    counting(spellcheck),
                    counting(placeSearch),
                    counting(placeEnrichment),
                    counting(rich),
                    counting(answers),
                    answersStream,
                    body -> reader.apply(body),
                    new ArrayList<>(ownedResources));
        }

        private <Req, Res> Function<Req, Outcome<Res>> counting(Function<Req, Outcome<Res>> bound) {
            return request -> {
                calls.incrementAndGet();
                return bound.apply(request);
            };
        }

        private <Res> Outcome<Res> success(String endpoint) {
            return new Outcome.Success<>((Res) resultFor(endpoint));
        }

        private Object resultFor(String endpoint) {
            UpstreamPayload body =
                    new UpstreamPayload(("{\"web\":{\"endpoint\":\"" + endpoint + "\",\"results\":[]}}").getBytes(UTF_8));
            return switch (endpoint) {
                case "web" -> new WebSearchResult(200, body, null, null, "req-" + endpoint, null);
                case "context" -> new ContextResult(200, body, null, null, "req-" + endpoint, null);
                case "news" -> new NewsSearchResult(200, body, null, null, "req-" + endpoint, null);
                case "videos" -> new VideoSearchResult(200, body, null, null, "req-" + endpoint, null);
                case "images" -> new ImageSearchResult(200, body, null, null, "req-" + endpoint, null);
                case "suggest" -> new SuggestSearchResult(200, body, null, null, "req-" + endpoint, null);
                case "spellcheck" -> new SpellcheckSearchResult(200, body, null, null, "req-" + endpoint, null);
                case "places" -> new PlaceSearchResult(200, body, null, null, "req-" + endpoint, null);
                case "enrichment" -> new PlaceEnrichmentResult(200, body, null, null, "req-" + endpoint, null);
                case "rich" -> new RichResult(200, body, null, null, "req-" + endpoint, null);
                case "answers" -> new AnswersResult(200, body, null, null, "req-" + endpoint, null);
                default -> throw new IllegalArgumentException(endpoint);
            };
        }
    }

    /**
     * One manually driven opened stream: the pieces the composition hands the public package — a
     * semantic-frames publisher the test drives, the closed-cause latch action, the cancelled
     * probe, and the counting release.
     */
    private static final class ManualStream {

        private final ManualPublisher semanticFrames = new ManualPublisher();

        private final AtomicBoolean closedCause = new AtomicBoolean();

        final AtomicInteger releases = new AtomicInteger();

        OpenedAnswersStream opened() {
            return new OpenedAnswersStream(
                    semanticFrames, () -> closedCause.set(true), closedCause::get, releases::incrementAndGet);
        }

        void emitAnswerAndComplete() {
            semanticFrames.next().onNext(new AnswerStreamEvent.Text("done"));
            semanticFrames.next().onComplete();
        }
    }

    /** A fully manual synchronous semantic-frames publisher the test drives event by event. */
    private static final class ManualPublisher implements Flow.Publisher<AnswerStreamEvent> {

        private final AtomicReference<Flow.Subscriber<? super AnswerStreamEvent>> subscriber = new AtomicReference<>();

        @Override
        public void subscribe(Flow.Subscriber<? super AnswerStreamEvent> newcomer) {
            subscriber.set(newcomer);
        }

        Flow.Subscriber<? super AnswerStreamEvent> next() {
            return subscriber.get();
        }
    }

    private static final class CollectingSubscriber implements Flow.Subscriber<PublicStreamFrame> {

        @Override
        public void onSubscribe(Flow.Subscription subscription) {}

        @Override
        public void onNext(PublicStreamFrame frame) {}

        @Override
        public void onError(Throwable failure) {}

        @Override
        public void onComplete() {}
    }

    private static final class CountingResource implements AutoCloseable {

        final AtomicInteger closes = new AtomicInteger();

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }

    /** A minimal public handle whose close counts and optionally fails, standing for a caller-wired stream. */
    private static final class ReleasingHandle implements AnswersStreamHandle {

        final AtomicInteger closes = new AtomicInteger();

        private final String releaseFailure;

        ReleasingHandle(String releaseFailure) {
            this.releaseFailure = releaseFailure;
        }

        @Override
        public Flow.Publisher<PublicStreamFrame> frames() {
            throw new UnsupportedOperationException("the client never touches a handle's frames");
        }

        @Override
        public StreamCancellation cancellation() {
            throw new UnsupportedOperationException("the client never touches a handle's cancellation");
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            if (releaseFailure != null) {
                throw new IllegalStateException(releaseFailure);
            }
        }
    }
}

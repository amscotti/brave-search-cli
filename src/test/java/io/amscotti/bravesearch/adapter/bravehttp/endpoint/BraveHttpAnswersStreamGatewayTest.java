package io.amscotti.bravesearch.adapter.bravehttp.endpoint;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.port.out.AnswersStreamDispatch;
import io.amscotti.bravesearch.application.port.out.AnswersStreamExchange;
import io.amscotti.bravesearch.application.stream.AbruptEofException;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.CancellationRegistry;
import io.amscotti.bravesearch.domain.answer.AnswerStreamEvent;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.metadata.RateLimitWindow;
import io.amscotti.bravesearch.domain.request.AnswersRequest;
import io.amscotti.bravesearch.testsupport.ScriptedSseServer;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * Loopback contracts of the streaming answers exchange: one POST exchange whose body is
 * published as decoded-exact event-stream bytes, one semantic layer decoding those same
 * bytes, deadline enforcement that a silent or endless peer cannot escape, and cleanup that
 * no terminal path skips. The wire shape is pinned against the recorded request; the
 * incrementality proof observes deltas while the scripted server still stalls mid-stream.
 */
final class BraveHttpAnswersStreamGatewayTest {

    private static final Duration AWAIT = Duration.ofSeconds(5);

    private static final String SENTINEL = "stream-sentinel-" + UUID.randomUUID();

    /**
     * An unpinned stream resolves the documented defaults: the 60-second idle window, the
     * 300-second research idle window, and a research wall budget of the pinned research
     * seconds plus the 30-second grace — while ordinary and unpinned-seconds research
     * streams carry no default wall budget at all.
     */
    @Test
    void unpinnedStreamsResolveTheDocumentedDefaultDeadlines() {
        CapturingGateway gateway = new CapturingGateway(new RecordingRegistry());

        AnswersRequest plain = AnswersRequest.builder("q").stream(true).build();
        assertEquals(Duration.ofSeconds(60), gateway.resolveIdle(plain), "the ordinary default idle window is 60 seconds");
        assertNull(gateway.resolveWall(plain), "an ordinary stream carries no default wall budget");

        AnswersRequest research = AnswersRequest.builder("q").stream(true).research(true).build();
        assertEquals(
                Duration.ofSeconds(300), gateway.resolveIdle(research), "the research idle window is 300 seconds");
        assertNull(
                gateway.resolveWall(research), "research that pinned no seconds carries no default wall budget");

        AnswersRequest bounded =
                AnswersRequest.builder("q").stream(true).research(true).researchMaximumSeconds(90).build();
        assertEquals(
                Duration.ofSeconds(120),
                gateway.resolveWall(bounded),
                "the research wall budget is its pinned seconds plus the 30-second grace");
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 20, threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    void theStreamRequestTravelsTheExactWireShape() throws Exception {

        try (ScriptedSseServer server = smallEventStream()) {
            RecordingRegistry registry = new RecordingRegistry();
            CapturingGateway gateway = new CapturingGateway(registry);
            AnswersStreamExchange exchange = open(gateway, server, streamRequest(null, null));

            ScriptedSseServer.RecordedRequest recorded = server.requests().getFirst();
            assertEquals("POST", recorded.method());
            assertEquals("/chat/completions", recorded.path());
            assertEquals("text/event-stream", recorded.firstHeader("Accept").orElseThrow());
            assertEquals(
                    "identity",
                    recorded.firstHeader("Accept-Encoding").orElseThrow(),
                    "a stream never negotiates a compressed coding");
            assertEquals("application/json", recorded.firstHeader("Content-Type").orElseThrow());
            assertEquals(SENTINEL, recorded.firstHeader("X-Subscription-Token").orElseThrow());
            assertEquals(
                    "{\"messages\":[{\"content\":\"q\",\"role\":\"user\"}],\"stream\":true}",
                    new String(recorded.body(), UTF_8));

            RawSubscriber raw = new RawSubscriber();
            exchange.decodedRawFrames().subscribe(raw);
            raw.requestUnbounded();
            assertTrue(raw.terminal.await(5, TimeUnit.SECONDS), "the scripted stream completes");
            exchange.close();
            assertTrue(registry.awaitDetached(AWAIT), "closing detaches the cancellation context");
            assertTrue(gateway.readerTerminated(AWAIT), "the reader executor terminates on close");
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 20, threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    void theOpenMetaSnapshotCapturesTheHeaderTimeObservations() throws Exception {
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "text/event-stream")
                .header("X-Request-ID", "req-stream-9")
                .header("Api-Version", "2026-08-30")
                .header("X-RateLimit-Limit", "1")
                .header("X-RateLimit-Policy", "per day")
                .header("X-RateLimit-Remaining", "0")
                .header("X-RateLimit-Reset", "12")
                .writeBytes(doneMarker())
                .start()) {
            AnswersStreamExchange exchange = open(
                    new CapturingGateway(new RecordingRegistry()), server, streamRequest(null, null));

            assertEquals(200, exchange.openMeta().httpStatus());
            assertEquals("req-stream-9", exchange.openMeta().requestId());
            assertEquals("2026-08-30", exchange.openMeta().apiVersion());
            List<RateLimitWindow> windows = exchange.openMeta().rateLimits();
            assertEquals(1, windows.size(), "the header-time rate-limit snapshot rides the meta");
            assertEquals(0, windows.getFirst().remaining());
            assertNull(exchange.openMeta().usage(), "usage is a stream-borne fact, not a header-time one");
            assertNull(exchange.lastSemanticProgress(), "no semantic progress exists before the first event");
            assertTrue(
                    exchange.lastTransportActivity() != null,
                    "the transport activity mark exists from the opening instant");
            exchange.close();
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 20, threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    void semanticDeltasArriveWhileTheServerStillStallsMidStream() throws Exception {
        CountDownLatch serverStillStalling = new CountDownLatch(1);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "text/event-stream")
                .writeBytes(event("{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}"))
                .writeBytes(event("{\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}"))
                .flush()
                .stallUntil(serverStillStalling)
                .writeBytes(event("{\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}"))
                .writeBytes(doneMarker())
                .start()) {
            AnswersStreamExchange exchange = open(
                    new CapturingGateway(new RecordingRegistry()), server, streamRequest(null, null));
            SemanticSubscriber deltas = new SemanticSubscriber();
            exchange.semanticFrames().subscribe(deltas);
            deltas.requestUnbounded();

            assertTrue(
                    deltas.firstText.await(5, TimeUnit.SECONDS),
                    "the first semantic deltas must arrive before the server finishes");
            assertEquals(1, serverStillStalling.getCount(), "the release latch is still closed: the server is mid-stream");
            assertTrue(deltas.terminal.getCount() > 0, "the answer cannot be complete while the tail is withheld");
            assertEquals(
                    List.of(new AnswerStreamEvent.Text("Hel")),
                    deltas.texts(),
                    "the stalled server's already-flushed deltas are decoded live");

            serverStillStalling.countDown();
            assertTrue(deltas.terminal.await(5, TimeUnit.SECONDS));
            assertEquals(
                    List.of(new AnswerStreamEvent.Text("Hel"), new AnswerStreamEvent.Text("lo")),
                    deltas.texts(),
                    "the released tail completes the answer text");
            assertNull(deltas.error);
            exchange.close();
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 20, threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    void rawFramesDeliverDecodedExactBytesUnderDemandGating() throws Exception {
        CountDownLatch serverStillStalling = new CountDownLatch(1);
        byte[] blob = prefixedBlob();
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "text/event-stream")
                .writeBytes(blob)
                .flush()
                .stallUntil(serverStillStalling)
                .writeBytes(doneMarker())
                .start()) {
            AnswersStreamExchange exchange = open(
                    new CapturingGateway(new RecordingRegistry()), server, streamRequest(null, null));
            RawSubscriber raw = new RawSubscriber();
            exchange.decodedRawFrames().subscribe(raw);

            raw.subscription.request(1);
            assertTrue(raw.firstChunk.await(5, TimeUnit.SECONDS), "one unit of demand reads the body");
            assertEquals(1, raw.chunks.size(), "exactly one chunk is delivered");
            assertEquals(1, raw.chunks.getFirst().length, "request(1) grants exactly one decoded byte");

            // no fixed sleep witnesses the absence of a further read: the counting probe is
            // the next pull's exact accounting — had anything been read beyond demand, the
            // chunk granted next could not be exactly the ninety-nine bytes requested
            raw.subscription.request(99);
            await(() -> raw.chunks.size() == 2, AWAIT);
            assertEquals(
                    99,
                    raw.chunks.get(1).length,
                    "resumed demand reads exactly its own bytes; nothing was pre-read while demand was exhausted");

            raw.subscription.request(Long.MAX_VALUE);
            serverStillStalling.countDown();
            assertTrue(raw.terminal.await(5, TimeUnit.SECONDS), "the body completes once released");
            ByteArrayOutputStream rejoined = new ByteArrayOutputStream();
            raw.chunks.forEach(chunk -> rejoined.writeBytes(chunk));
            assertEquals(
                    blob.length + doneMarker().length,
                    rejoined.size(),
                    "the concatenated chunks are the decoded body exactly");
            assertEquals(
                    new String(blob, UTF_8),
                    new String(rejoined.toByteArray(), 0, blob.length, UTF_8),
                    "raw mode carries the decoded-exact event-stream bytes");
            exchange.close();
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 20, threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    void idleTimeoutFiresOnSilenceAndCleansUpEveryResource() throws Exception {
        CountDownLatch neverReleased = new CountDownLatch(1);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "text/event-stream")
                .writeBytes(event("{\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}"))
                .flush()
                .stallUntil(neverReleased)
                .writeBytes(doneMarker())
                .start()) {
            RecordingRegistry registry = new RecordingRegistry();
            CapturingGateway gateway = new CapturingGateway(registry);
            AnswersStreamExchange exchange = open(gateway, server, streamRequest(Duration.ofMillis(300), null));
            SemanticSubscriber deltas = new SemanticSubscriber();
            exchange.semanticFrames().subscribe(deltas);
            deltas.requestUnbounded();
            assertTrue(deltas.firstText.await(5, TimeUnit.SECONDS));

            assertTrue(
                    await(() -> exchange.cancellation()
                                    .cause()
                                    .filter(cause -> cause == CancellationContext.Cause.IDLE_TIMEOUT)
                                    .isPresent(),
                            Duration.ofSeconds(3)),
                    "a silent mid-stream body is cut by the idle deadline");

            assertTrue(registry.awaitDetached(AWAIT), "the watchdog's close detaches the context");
            assertTrue(gateway.readerTerminated(AWAIT), "the watchdog's close terminates the reader executor");
        } finally {
            neverReleased.countDown();
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 25, threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    void idleTimeoutResetsOnEveryDecodedByte() throws Exception {
        ScriptedSseServer.Builder script = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "text/event-stream");
        for (int drip = 0; drip < 10; drip++) {
            script.writeBytes(event("{\"choices\":[{\"delta\":{\"content\":\"x\"}}]}"))
                    .delay(Duration.ofMillis(200));
        }
        script.writeBytes(doneMarker());
        try (ScriptedSseServer server = script.start()) {
            AnswersStreamExchange exchange = open(
                    new CapturingGateway(new RecordingRegistry()),
                    server,
                    streamRequest(Duration.ofMillis(500), null));
            SemanticSubscriber deltas = new SemanticSubscriber();
            exchange.semanticFrames().subscribe(deltas);
            deltas.requestUnbounded();

            assertTrue(
                    deltas.terminal.await(10, TimeUnit.SECONDS),
                    "a stream whose bytes drip inside the idle window survives the whole drip");
            assertNull(deltas.error, "the slow-drip stream ends by completion, not by deadline");
            exchange.close();
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 20, threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    void wallTimeoutFiresDespiteHeartbeats() throws Exception {
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "text/event-stream")
                .heartbeatUntilClosed("keep-alive")
                .start()) {
            RecordingRegistry registry = new RecordingRegistry();
            CapturingGateway gateway = new CapturingGateway(registry);
            AnswersStreamExchange exchange = open(gateway, server, streamRequest(null, Duration.ofMillis(600)));
            RawSubscriber raw = new RawSubscriber();
            exchange.decodedRawFrames().subscribe(raw);
            raw.requestUnbounded();
            assertTrue(raw.firstChunk.await(5, TimeUnit.SECONDS), "heartbeat bytes flow as raw frames");

            assertTrue(
                    await(() -> exchange.cancellation()
                                    .cause()
                                    .filter(cause -> cause == CancellationContext.Cause.WALL_TIMEOUT)
                                    .isPresent(),
                            Duration.ofSeconds(5)),
                    "an absolute wall deadline fires even though heartbeats keep the idle clock resetting");
            assertTrue(registry.awaitDetached(AWAIT), "the wall breach detaches the context");
            assertTrue(gateway.readerTerminated(AWAIT), "the wall breach terminates the reader executor");
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 20, threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    void aWallDeadlineFiredBeforeAnySubscriptionStillHandsTheLateSubscriberItsTerminal() throws Exception {
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "text/event-stream")
                .writeBytes(event("{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}"))
                .writeBytes(doneMarker())
                .start()) {
            RecordingRegistry registry = new RecordingRegistry();
            CapturingGateway gateway = new CapturingGateway(registry);
            AnswersStreamExchange exchange = open(gateway, server, streamRequest(null, Duration.ofMillis(300)));

            assertTrue(
                    await(() -> exchange.cancellation()
                                    .cause()
                                    .filter(cause -> cause == CancellationContext.Cause.WALL_TIMEOUT)
                                    .isPresent(),
                            Duration.ofSeconds(5)),
                    "a wall budget nearly consumed by the headers phase fires once the exchange is live, "
                            + "before any subscription arrives");

            SemanticSubscriber latecomer = new SemanticSubscriber();
            exchange.semanticFrames().subscribe(latecomer);
            latecomer.requestUnbounded();

            assertTrue(
                    latecomer.terminal.await(5, TimeUnit.SECONDS),
                    "a subscription onto the closed exchange still receives its terminal signal");
            assertNotNull(
                    latecomer.error, "the closed exchange hands its late subscriber an error, never a throw");
            assertEquals(
                    CancellationContext.Cause.WALL_TIMEOUT,
                    exchange.cancellation().cause().orElseThrow(),
                    "the latched wall cause decides the ending, not the late subscription");
            assertTrue(registry.awaitDetached(AWAIT), "the watchdog's close detached the context");
            assertTrue(gateway.readerTerminated(AWAIT), "the watchdog's close terminated the reader executor");
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 20, threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    void anAbruptEndingFailsTypedAndReleasesEveryResource() throws Exception {
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "text/event-stream")
                .writeBytes(
                        ("data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"").getBytes(UTF_8))
                .abruptClose()
                .start()) {
            RecordingRegistry registry = new RecordingRegistry();
            CapturingGateway gateway = new CapturingGateway(registry);
            AnswersStreamExchange exchange = open(gateway, server, streamRequest(null, null));
            SemanticSubscriber deltas = new SemanticSubscriber();
            exchange.semanticFrames().subscribe(deltas);
            deltas.requestUnbounded();

            assertTrue(deltas.terminal.await(5, TimeUnit.SECONDS), "the break reaches the subscriber");
            assertInstanceOf(
                    AbruptEofException.class,
                    deltas.error,
                    "a truncated body is the typed abrupt-ending failure");
            assertEquals(
                    CancellationContext.Cause.TRANSPORT_FAILURE,
                    exchange.cancellation().cause().orElseThrow());

            exchange.close();
            assertTrue(gateway.readerTerminated(AWAIT), "the reader executor terminates after the break");
            assertTrue(registry.awaitDetached(AWAIT), "the context detaches after the break");
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 20, threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    void aNonEventStreamContentTypeIsMalformedAtOpen() throws Exception {
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .writeBytes("{\"not\":\"an event stream\"}".getBytes(UTF_8))
                .start()) {
            RecordingRegistry registry = new RecordingRegistry();
            CapturingGateway gateway = new CapturingGateway(registry);

            Outcome<AnswersStreamExchange> outcome = gateway.stream(dispatch(server, streamRequest(null, null)));

            Outcome.Failure<AnswersStreamExchange> failure = failing(outcome);
            assertEquals(FailureKind.MALFORMED, failure.kind());
            assertEquals(200, failure.httpStatus());
            assertTrue(failure.diagnostic().contains("text/event-stream"), failure.diagnostic());
            assertTrue(failure.diagnostic().contains("an event stream"), "the preview quotes the body");
            assertTrue(registry.awaitDetached(AWAIT), "the refused open detaches the context");
            assertTrue(gateway.readerTerminated(AWAIT), "the refused open leaves no reader behind");
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 20, threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    void aCompressedStreamResponseIsMalformedAtOpen() throws Exception {
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "text/event-stream")
                .header("Content-Encoding", "gzip")
                .writeBytes(event("{\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}"))
                .start()) {
            Outcome<AnswersStreamExchange> outcome = openFailing(
                    new CapturingGateway(new RecordingRegistry()), server);

            assertEquals(FailureKind.MALFORMED, failing(outcome).kind());
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 20, threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    void aNon2xxOpenKeepsTheUpstreamClassificationAndItsRateLimits() throws Exception {
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(429)
                .header("Content-Type", "application/json")
                .header("X-RateLimit-Limit", "1")
                .header("X-RateLimit-Policy", "per second")
                .header("X-RateLimit-Remaining", "0")
                .header("X-RateLimit-Reset", "30")
                .writeBytes("{\"error\":{\"code\":\"TooManyRequests\",\"detail\":\"slow down\"}}"
                        .getBytes(UTF_8))
                .start()) {
            RecordingRegistry registry = new RecordingRegistry();
            CapturingGateway gateway = new CapturingGateway(registry);

            Outcome<AnswersStreamExchange> outcome = gateway.stream(dispatch(server, streamRequest(null, null)));

            Outcome.Failure<AnswersStreamExchange> failure = failing(outcome);
            assertEquals(FailureKind.RATE_LIMITED, failure.kind());
            assertEquals(429, failure.httpStatus());
            assertEquals("TooManyRequests", failure.upstream().code());
            assertEquals(1, failure.rateLimits().windows().size(), "the failure explains itself with its windows");
            assertTrue(registry.awaitDetached(AWAIT), "the failed open detaches the context");
            assertTrue(gateway.readerTerminated(AWAIT), "the failed open leaves no reader behind");
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 20, threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    void aSilentPeerBoundsTheOpeningPhaseByTheStreamDeadline() throws Exception {
        CountDownLatch neverSendHeaders = new CountDownLatch(1);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .holdHeadersUntil(neverSendHeaders)
                .writeBytes(doneMarker())
                .start()) {
            RecordingRegistry registry = new RecordingRegistry();
            CapturingGateway gateway = new CapturingGateway(registry);

            Outcome<AnswersStreamExchange> outcome =
                    gateway.stream(dispatch(server, streamRequest(null, Duration.ofMillis(500))));

            Outcome.Failure<AnswersStreamExchange> failure = failing(outcome);
            assertEquals(FailureKind.TRANSPORT, failure.kind());
            assertTrue(failure.diagnostic().contains("HttpTimeoutException"), failure.diagnostic());
            assertTrue(registry.awaitDetached(AWAIT), "the timed-out open detaches the context");
        } finally {
            neverSendHeaders.countDown();
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 20, threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    void aBlockingRequestIsRefusedWithEveryOpenResourceReleased() throws Exception {
        try (ScriptedSseServer server = smallEventStream()) {
            RecordingRegistry registry = new RecordingRegistry();
            CapturingGateway gateway = new CapturingGateway(registry);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> gateway.stream(dispatch(server, AnswersRequest.builder("q").build())),
                    "a request without stream=true is the documented open rejection");
            assertTrue(registry.awaitDetached(AWAIT), "the refused open detaches the registered context");
            assertTrue(server.requests().isEmpty(), "the refused open sends nothing on the wire");
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 20, threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    void aFailingSemanticAssemblyRethrowsWithEveryOpenResourceReleased() throws Exception {
        try (ScriptedSseServer server = smallEventStream()) {
            RecordingRegistry registry = new RecordingRegistry();
            CapturingGateway gateway = new CapturingGateway(
                    registry,
                    (rawFrames, cancellation) -> {
                        throw new IllegalStateException("the composition refused to arm the semantic layer");
                    });

            assertThrows(
                    IllegalStateException.class,
                    () -> gateway.stream(dispatch(server, streamRequest(null, null))),
                    "an assembly failure is the composition's own exception, not a failure outcome");
            assertTrue(registry.awaitDetached(AWAIT), "the failed assembly detaches the registered context");
            assertTrue(gateway.readerTerminated(AWAIT), "the failed assembly shuts the reader executor");
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 20, threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    void aNon2xxBodyWithheldPastTheWallBudgetEndsTheOpenPromptly() throws Exception {
        CountDownLatch neverDelivered = new CountDownLatch(1);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(500)
                .header("Content-Type", "application/json")
                .writeBytes("{\"error\":{\"code\":\"Internal".getBytes(UTF_8))
                .flush()
                .stallUntil(neverDelivered)
                .start()) {
            RecordingRegistry registry = new RecordingRegistry();
            CapturingGateway gateway = new CapturingGateway(registry);

            long startedAt = System.nanoTime();
            Outcome<AnswersStreamExchange> outcome =
                    gateway.stream(dispatch(server, streamRequest(null, Duration.ofMillis(400))));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            Outcome.Failure<AnswersStreamExchange> failure = failing(outcome);
            assertEquals(FailureKind.TRANSPORT, failure.kind(), failure.diagnostic());
            assertEquals("upstream stream exchange exceeded its wall budget", failure.diagnostic());
            assertTrue(elapsedMs < 5000, "a withheld error body must not outlast the wall budget");
            assertTrue(registry.awaitDetached(AWAIT), "the deadline-broken open detaches the context");
        } finally {
            neverDelivered.countDown();
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 20, threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    void aLatchedSignalUnblocksAWithheldNon2xxBodyRead() throws Exception {
        CountDownLatch neverDelivered = new CountDownLatch(1);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(429)
                .header("Content-Type", "application/json")
                .writeBytes("{\"error\":{\"code\":\"TooMany".getBytes(UTF_8))
                .flush()
                .stallUntil(neverDelivered)
                .start()) {
            RecordingRegistry registry = new RecordingRegistry();
            CapturingGateway gateway = new CapturingGateway(registry);
            Thread.ofVirtual().start(() -> {
                try {
                    assertTrue(server.awaitFirstRequest(Duration.ofSeconds(5)), "the exchange must reach the server");
                    TimeUnit.MILLISECONDS.sleep(300);
                } catch (InterruptedException waiting) {
                    Thread.currentThread().interrupt();
                }
                registry.registered().latch(CancellationContext.Cause.SIGINT);
            });

            long startedAt = System.nanoTime();
            Outcome<AnswersStreamExchange> outcome = gateway.stream(dispatch(server, streamRequest(null, null)));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            Outcome.Failure<AnswersStreamExchange> failure = failing(outcome);
            assertEquals(FailureKind.TRANSPORT, failure.kind(), failure.diagnostic());
            assertEquals("upstream stream exchange cancelled", failure.diagnostic());
            assertTrue(elapsedMs < 5000, "a latched signal must not leave a withheld error body parked");
            assertTrue(registry.awaitDetached(AWAIT), "the cancelled open detaches the context");
        } finally {
            neverDelivered.countDown();
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 20, threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    void aWithheldWrongContentTypePreviewEndsTheMalformedRefusalPromptly() throws Exception {
        CountDownLatch neverDelivered = new CountDownLatch(1);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .writeBytes("{\"not\":\"an event stream\"".getBytes(UTF_8))
                .flush()
                .stallUntil(neverDelivered)
                .start()) {
            RecordingRegistry registry = new RecordingRegistry();
            CapturingGateway gateway = new CapturingGateway(registry);

            long startedAt = System.nanoTime();
            Outcome<AnswersStreamExchange> outcome =
                    gateway.stream(dispatch(server, streamRequest(null, Duration.ofMillis(400))));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            Outcome.Failure<AnswersStreamExchange> failure = failing(outcome);
            assertEquals(FailureKind.MALFORMED, failure.kind(), failure.diagnostic());
            assertEquals(200, failure.httpStatus());
            assertTrue(elapsedMs < 5000, "a withheld malformed body must not outlast the wall budget");
            assertTrue(registry.awaitDetached(AWAIT), "the refused open detaches the context");
        } finally {
            neverDelivered.countDown();
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 20, threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    void cancellingTheSubscriptionUnblocksTheStalledServer() throws Exception {
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "text/event-stream")
                .writeBytesUntilClosed(("data: " + "x".repeat(64) + "\n\n").getBytes(UTF_8))
                .start()) {
            CapturingGateway gateway = new CapturingGateway(new RecordingRegistry());
            AnswersStreamExchange exchange = open(gateway, server, streamRequest(null, null));
            RawSubscriber raw = new RawSubscriber();
            exchange.decodedRawFrames().subscribe(raw);
            raw.requestUnbounded();
            assertTrue(raw.firstChunk.await(5, TimeUnit.SECONDS));

            raw.subscription.cancel();
            assertTrue(
                    server.awaitConnectionClosed(AWAIT),
                    "cancelling the subscription severs the peer's connection");

            exchange.close();
            assertTrue(gateway.readerTerminated(AWAIT), "the reader executor terminates after cancel");
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 20, threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    void eachPublisherAcceptsExactlyOneSubscriptionPerExchange() throws Exception {
        try (ScriptedSseServer server = smallEventStream()) {
            AnswersStreamExchange exchange = open(
                    new CapturingGateway(new RecordingRegistry()), server, streamRequest(null, null));
            RawSubscriber raw = new RawSubscriber();
            exchange.decodedRawFrames().subscribe(raw);
            SemanticSubscriber refusedByTheLiveBody = new SemanticSubscriber();
            exchange.semanticFrames().subscribe(refusedByTheLiveBody);
            assertTrue(
                    refusedByTheLiveBody.terminal.await(5, TimeUnit.SECONDS),
                    "one exchange serves one body subscription: the semantic layer is refused once raw holds it");
            assertInstanceOf(IllegalStateException.class, refusedByTheLiveBody.error);
            exchange.close();
        }

        try (ScriptedSseServer server = smallEventStream()) {
            AnswersStreamExchange exchange = open(
                    new CapturingGateway(new RecordingRegistry()), server, streamRequest(null, null));
            SemanticSubscriber first = new SemanticSubscriber();
            exchange.semanticFrames().subscribe(first);
            SemanticSubscriber second = new SemanticSubscriber();
            exchange.semanticFrames().subscribe(second);
            assertTrue(second.terminal.await(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, second.error, "a duplicate semantic subscriber is refused");
            first.requestUnbounded();
            assertTrue(first.terminal.await(5, TimeUnit.SECONDS), "the live semantic subscription still completes");
            assertNull(first.error);
            exchange.close();
        }
    }

    private static ScriptedSseServer smallEventStream() throws Exception {
        return ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "text/event-stream")
                .writeBytes(event("{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}"))
                .writeBytes(doneMarker())
                .start();
    }

    private static AnswersRequest streamRequest(java.time.Duration idleTimeout, java.time.Duration streamTimeout) {
        AnswersRequest.Builder builder = AnswersRequest.builder("q").stream(true);
        if (idleTimeout != null) {
            builder.idleTimeout(idleTimeout);
        }
        if (streamTimeout != null) {
            builder.streamTimeout(streamTimeout);
        }
        return builder.build();
    }

    private static AnswersStreamDispatch dispatch(ScriptedSseServer server, AnswersRequest request) {
        return new AnswersStreamDispatch(
                request,
                BraveApiOrigin.fromOverride(
                        server.baseUrl().toString(), host -> { throw new AssertionError("literals are never resolved"); }),
                Credential.of(SENTINEL.getBytes(UTF_8)),
                Duration.ofSeconds(5),
                null);
    }

    private static AnswersStreamExchange open(
            CapturingGateway gateway, ScriptedSseServer server, AnswersRequest request) {
        Outcome<AnswersStreamExchange> outcome = gateway.stream(dispatch(server, request));
        return switch (outcome) {
            case Outcome.Success<AnswersStreamExchange> success -> success.value();
            case Outcome.Failure<AnswersStreamExchange> failure ->
                    throw new AssertionError("expected an open exchange, saw " + failure.kind() + ": "
                            + failure.diagnostic());
        };
    }

    private static Outcome<AnswersStreamExchange> openFailing(CapturingGateway gateway, ScriptedSseServer server) {
        return gateway.stream(dispatch(server, streamRequest(null, null)));
    }

    private static Outcome.Failure<AnswersStreamExchange> failing(Outcome<AnswersStreamExchange> outcome) {
        return switch (outcome) {
            case Outcome.Success<AnswersStreamExchange> ignored ->
                    throw new AssertionError("expected a failure, saw a success");
            case Outcome.Failure<AnswersStreamExchange> failure -> failure;
        };
    }

    /** The wire bytes of one dispatched event carrying {@code data} as its payload. */
    private static byte[] event(String data) {
        return ("data: " + data + "\n\n").getBytes(UTF_8);
    }

    private static byte[] doneMarker() {
        return "data: [DONE]\n\n".getBytes(UTF_8);
    }

    /** A framed blob large enough to span several read windows: a BOM-prefixed comment line. */
    private static byte[] prefixedBlob() {
        return ("x".repeat(16 * 1024 + 7)).getBytes(UTF_8);
    }

    private static boolean await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    /** Registry double: counts live contexts so every path can prove its detachment. */
    private static final class RecordingRegistry implements CancellationRegistry {

        private final AtomicInteger live = new AtomicInteger();

        private volatile CancellationContext registered;

        @Override
        public Runnable register(CancellationContext cancellation) {
            Objects.requireNonNull(cancellation);
            registered = cancellation;
            live.incrementAndGet();
            return live::decrementAndGet;
        }

        boolean awaitDetached(Duration timeout) throws InterruptedException {
            return await(() -> live.get() == 0, timeout);
        }

        CancellationContext registered() {
            return registered;
        }
    }

    /** The gateway under test, capturing the per-exchange reader executor it creates. */
    private static final class CapturingGateway extends BraveHttpAnswersStreamGateway {

        private volatile ExecutorService reader;

        CapturingGateway(CancellationRegistry registry) {
            this(registry, compositionRootAssembly());
        }

        CapturingGateway(
                CancellationRegistry registry,
                io.amscotti.bravesearch.adapter.bravehttp.sse.SemanticStreamAssembly assembly) {
            super(
                    registry,
                    Clock.systemUTC(),
                    io.amscotti.bravesearch.application.exchange.ResponseLimits.production(),
                    assembly);
        }

        /** The composition-root wiring of the semantic layer, as bootstrap will express it. */
        private static io.amscotti.bravesearch.adapter.bravehttp.sse.SemanticStreamAssembly compositionRootAssembly() {
            return (rawFrames, cancellation) -> {
                io.amscotti.bravesearch.adapter.bravehttp.sse.AnswerStreamProcessor processor =
                        new io.amscotti.bravesearch.adapter.bravehttp.sse.AnswerStreamProcessor(
                                rawFrames, cancellation, Clock.systemUTC());
                return processor.armedWith(
                        new io.amscotti.bravesearch.adapter.bravehttp.sse.SseEventParser(
                                processor::onDispatchedEvent,
                                cancellation,
                                io.amscotti.bravesearch.adapter.bravehttp.sse.SseEventParser.DEFAULT_MAX_LINE_BYTES,
                                io.amscotti.bravesearch.adapter.bravehttp.sse.SseEventParser.DEFAULT_MAX_EVENT_BYTES),
                        new io.amscotti.bravesearch.adapter.bravehttp.json.AnswerTagDecoder(
                                cancellation,
                                io.amscotti.bravesearch.adapter.bravehttp.json.AnswerTagDecoder.DEFAULT_MAX_TAG_PAYLOAD_BYTES));
            };
        }

        @Override
        protected ExecutorService newReaderExecutor() {
            ExecutorService created = super.newReaderExecutor();
            reader = created;
            return created;
        }

        boolean readerTerminated(Duration timeout) throws InterruptedException {
            ExecutorService current = reader;
            return current == null || current.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** Raw consumer: records delivered chunks and their arrival, with manually granted demand. */
    private static final class RawSubscriber implements Flow.Subscriber<byte[]> {

        private final List<byte[]> chunks = new CopyOnWriteArrayList<>();

        private final CountDownLatch firstChunk = new CountDownLatch(1);

        private final CountDownLatch terminal = new CountDownLatch(1);

        private volatile Flow.Subscription subscription;

        private volatile Throwable error;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
        }

        void requestUnbounded() {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(byte[] chunk) {
            chunks.add(chunk);
            firstChunk.countDown();
        }

        @Override
        public void onError(Throwable thrown) {
            error = thrown;
            terminal.countDown();
        }

        @Override
        public void onComplete() {
            terminal.countDown();
        }
    }

    /** Semantic consumer: records decoded events, with the first text and the terminal latched. */
    private static final class SemanticSubscriber implements Flow.Subscriber<AnswerStreamEvent> {

        private final List<AnswerStreamEvent> events = new CopyOnWriteArrayList<>();

        private final CountDownLatch firstText = new CountDownLatch(1);

        private final CountDownLatch terminal = new CountDownLatch(1);

        private volatile Flow.Subscription subscription;

        private volatile Throwable error;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
        }

        void requestUnbounded() {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(AnswerStreamEvent event) {
            events.add(event);
            if (event instanceof AnswerStreamEvent.Text) {
                firstText.countDown();
            }
        }

        @Override
        public void onError(Throwable thrown) {
            error = thrown;
            terminal.countDown();
        }

        @Override
        public void onComplete() {
            terminal.countDown();
        }

        List<AnswerStreamEvent.Text> texts() {
            return events.stream()
                    .filter(event -> event instanceof AnswerStreamEvent.Text)
                    .map(event -> (AnswerStreamEvent.Text) event)
                    .toList();
        }
    }
}

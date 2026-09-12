package io.amscotti.bravesearch.api;

import io.amscotti.bravesearch.api.internal.Assembler;
import io.amscotti.bravesearch.api.internal.OpenedAnswersStream;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
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
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;

/**
 * The public facade over every Brave endpoint: one method per endpoint, each delegating to
 * the exchange the composition bound and converting its outcome into the endpoint's public
 * response — typed exchange metadata plus the caller-owned upstream snapshot.
 *
 * <p>Lifecycle: the client implements {@link AutoCloseable}; closing cancels every live
 * Answers stream, releases each owned resource exactly once (the shared transport and the
 * client-owned executor, when the composition created one), and is idempotent. A
 * caller-supplied executor is never shut down by close. Use after close reports the
 * closed-client transport failure, never an exception.
 *
 * <p>Composition: {@link #builder()} is the supported entry; its {@link Builder#build()}
 * delegates to {@code api.internal.Assembler}, which instantiates the concrete adapters so
 * this package never imports one. The public constructor below exists for that composition
 * and for callers wiring their own endpoints; every collaborator is a plain function over
 * public, domain, or JDK types — the streaming collaborator returns public
 * {@link AnswersStreamHandle handles} — so no internal type appears in any signature.
 */
public final class BraveSearchClient implements AutoCloseable {

    private final Function<WebSearchRequest, Outcome<WebSearchResult>> webSearch;

    private final Function<ContextRequest, Outcome<ContextResult>> llmContext;

    private final Function<NewsSearchRequest, Outcome<NewsSearchResult>> newsSearch;

    private final Function<VideoSearchRequest, Outcome<VideoSearchResult>> videoSearch;

    private final Function<ImageSearchRequest, Outcome<ImageSearchResult>> imageSearch;

    private final Function<SuggestRequest, Outcome<SuggestSearchResult>> suggest;

    private final Function<SpellcheckRequest, Outcome<SpellcheckSearchResult>> spellcheck;

    private final Function<PlaceSearchRequest, Outcome<PlaceSearchResult>> placeSearch;

    private final Function<PlaceEnrichmentRequest, Outcome<PlaceEnrichmentResult>> placeEnrichment;

    private final Function<RichRequest, Outcome<RichResult>> rich;

    private final Function<AnswersRequest, Outcome<AnswersResult>> answers;

    private final Function<AnswersRequest, Outcome<AnswersStreamHandle>> answersStream;

    private final Function<UpstreamPayload, JsonNode> upstreamReader;

    private final List<AutoCloseable> ownedResources;

    private final CopyOnWriteArrayList<AnswersStreamHandle> liveStreams = new CopyOnWriteArrayList<>();

    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * The lifecycle guard shared by stream registration and close's drain: the blocking open
     * itself runs unguarded, but a just-opened handle registers under this lock only when the
     * client is still open, so no handle can appear after close drained the live streams.
     */
    private final Object streamLifecycle = new Object();

    /**
     * The composition entry: one bound exchange per endpoint, the handle-returning streaming
     * opener, the shared upstream reader, and the resources close() owns.
     *
     * @throws NullPointerException when any exchange, the reader, or the resource list is
     *     null
     */
    public BraveSearchClient(
            Function<WebSearchRequest, Outcome<WebSearchResult>> webSearch,
            Function<ContextRequest, Outcome<ContextResult>> llmContext,
            Function<NewsSearchRequest, Outcome<NewsSearchResult>> newsSearch,
            Function<VideoSearchRequest, Outcome<VideoSearchResult>> videoSearch,
            Function<ImageSearchRequest, Outcome<ImageSearchResult>> imageSearch,
            Function<SuggestRequest, Outcome<SuggestSearchResult>> suggest,
            Function<SpellcheckRequest, Outcome<SpellcheckSearchResult>> spellcheck,
            Function<PlaceSearchRequest, Outcome<PlaceSearchResult>> placeSearch,
            Function<PlaceEnrichmentRequest, Outcome<PlaceEnrichmentResult>> placeEnrichment,
            Function<RichRequest, Outcome<RichResult>> rich,
            Function<AnswersRequest, Outcome<AnswersResult>> answers,
            Function<AnswersRequest, Outcome<AnswersStreamHandle>> answersStream,
            Function<UpstreamPayload, JsonNode> upstreamReader,
            List<AutoCloseable> ownedResources) {
        this.webSearch = Objects.requireNonNull(webSearch, "webSearch");
        this.llmContext = Objects.requireNonNull(llmContext, "llmContext");
        this.newsSearch = Objects.requireNonNull(newsSearch, "newsSearch");
        this.videoSearch = Objects.requireNonNull(videoSearch, "videoSearch");
        this.imageSearch = Objects.requireNonNull(imageSearch, "imageSearch");
        this.suggest = Objects.requireNonNull(suggest, "suggest");
        this.spellcheck = Objects.requireNonNull(spellcheck, "spellcheck");
        this.placeSearch = Objects.requireNonNull(placeSearch, "placeSearch");
        this.placeEnrichment = Objects.requireNonNull(placeEnrichment, "placeEnrichment");
        this.rich = Objects.requireNonNull(rich, "rich");
        this.answers = Objects.requireNonNull(answers, "answers");
        this.answersStream = Objects.requireNonNull(answersStream, "answersStream");
        this.upstreamReader = Objects.requireNonNull(upstreamReader, "upstreamReader");
        this.ownedResources = List.copyOf(Objects.requireNonNull(ownedResources, "ownedResources"));
    }

    /** The supported composition entry; see {@link Builder}. */
    public static Builder builder() {
        return new Builder();
    }

    /** One web-search exchange; blocking. */
    public Outcome<WebSearchResponse> webSearch(WebSearchRequest request) {
        Objects.requireNonNull(request, "request");
        return whileOpen(() -> ApiResponses.convert(webSearch.apply(request), upstreamReader, WebSearchResponse::new));
    }

    /** One LLM-context exchange; blocking. */
    public Outcome<ContextResponse> llmContext(ContextRequest request) {
        Objects.requireNonNull(request, "request");
        return whileOpen(() -> ApiResponses.convert(llmContext.apply(request), upstreamReader, ContextResponse::new));
    }

    /** One news-search exchange; blocking. */
    public Outcome<NewsSearchResponse> newsSearch(NewsSearchRequest request) {
        Objects.requireNonNull(request, "request");
        return whileOpen(() -> ApiResponses.convert(newsSearch.apply(request), upstreamReader, NewsSearchResponse::new));
    }

    /** One video-search exchange; blocking. */
    public Outcome<VideoSearchResponse> videoSearch(VideoSearchRequest request) {
        Objects.requireNonNull(request, "request");
        return whileOpen(() -> ApiResponses.convert(videoSearch.apply(request), upstreamReader, VideoSearchResponse::new));
    }

    /** One image-search exchange; blocking. */
    public Outcome<ImageSearchResponse> imageSearch(ImageSearchRequest request) {
        Objects.requireNonNull(request, "request");
        return whileOpen(() -> ApiResponses.convert(imageSearch.apply(request), upstreamReader, ImageSearchResponse::new));
    }

    /** One suggest exchange; blocking. */
    public Outcome<SuggestResponse> suggest(SuggestRequest request) {
        Objects.requireNonNull(request, "request");
        return whileOpen(() -> ApiResponses.convert(suggest.apply(request), upstreamReader, SuggestResponse::new));
    }

    /** One spellcheck exchange; blocking. */
    public Outcome<SpellcheckResponse> spellcheck(SpellcheckRequest request) {
        Objects.requireNonNull(request, "request");
        return whileOpen(() -> ApiResponses.convert(spellcheck.apply(request), upstreamReader, SpellcheckResponse::new));
    }

    /** One place-search exchange; blocking. */
    public Outcome<PlaceSearchResponse> placeSearch(PlaceSearchRequest request) {
        Objects.requireNonNull(request, "request");
        return whileOpen(() -> ApiResponses.convert(placeSearch.apply(request), upstreamReader, PlaceSearchResponse::new));
    }

    /**
     * One POI-details enrichment exchange over opaque place ids; blocking. The request must
     * carry the details kind.
     *
     * @throws IllegalArgumentException when the request carries the descriptions kind
     */
    public Outcome<PlaceEnrichmentResponse> placeDetails(PlaceEnrichmentRequest request) {
        Objects.requireNonNull(request, "request");
        requireKind(request, PlaceEnrichmentRequest.Kind.DETAILS, "placeDetails");
        return whileOpen(
                () -> ApiResponses.convert(placeEnrichment.apply(request), upstreamReader, PlaceEnrichmentResponse::new));
    }

    /**
     * One AI-description enrichment exchange over opaque place ids; blocking. The request
     * must carry the descriptions kind.
     *
     * @throws IllegalArgumentException when the request carries the details kind
     */
    public Outcome<PlaceEnrichmentResponse> describePlaces(PlaceEnrichmentRequest request) {
        Objects.requireNonNull(request, "request");
        requireKind(request, PlaceEnrichmentRequest.Kind.DESCRIPTIONS, "describePlaces");
        return whileOpen(
                () -> ApiResponses.convert(placeEnrichment.apply(request), upstreamReader, PlaceEnrichmentResponse::new));
    }

    /** One rich-result callback exchange; blocking. */
    public Outcome<RichResponse> rich(RichRequest request) {
        Objects.requireNonNull(request, "request");
        return whileOpen(() -> ApiResponses.convert(rich.apply(request), upstreamReader, RichResponse::new));
    }

    /**
     * One blocking Answers exchange. The request must not ask to stream.
     *
     * @throws IllegalArgumentException when the request carries {@code stream=true}; use
     *     {@link #answersStream(AnswersRequest)}
     */
    public Outcome<AnswersResponse> answers(AnswersRequest request) {
        Objects.requireNonNull(request, "request");
        requireBlocking(request);
        return whileOpen(() -> ApiResponses.convert(answers.apply(request), upstreamReader, AnswersResponse::new));
    }

    /**
     * Opens one streaming Answers exchange; the open itself blocks, the frames do not. The
     * request must ask to stream.
     *
     * @throws IllegalArgumentException when the request carries no {@code stream=true}; use
     *     {@link #answers(AnswersRequest)}
     */
    public Outcome<AnswersStreamHandle> answersStream(AnswersRequest request) {
        Objects.requireNonNull(request, "request");
        requireStreaming(request);
        if (closed.get()) {
            return new Outcome.Failure<>(FailureKind.TRANSPORT, "the client is closed");
        }
        return switch (answersStream.apply(request)) {
            case Outcome.Success<AnswersStreamHandle> opened -> registerWhileOpen(opened.value());
            case Outcome.Failure<AnswersStreamHandle> failure -> new Outcome.Failure<>(
                    failure.kind(), failure.diagnostic(), failure.upstream(), failure.rateLimits(), failure.httpStatus());
        };
    }

    /**
     * Registers one just-opened stream under the same lifecycle guard close() drains. An open
     * whose whole blocking span raced close() therefore either registered before the drain —
     * and is cancelled by it — or finds the client already closed: the handle then releases
     * its own exchange and the caller receives the closed-client transport failure, so no
     * stream ever leaks past a close that can no longer reach it.
     */
    private Outcome<AnswersStreamHandle> registerWhileOpen(AnswersStreamHandle handle) {
        boolean registered;
        synchronized (streamLifecycle) {
            registered = !closed.get();
            if (registered) {
                if (handle instanceof ApiAnswersStreamHandle apiHandle) {
                    apiHandle.detachFromClient(() -> liveStreams.removeIf(live -> live == handle));
                }
                liveStreams.add(handle);
            }
        }
        if (!registered) {
            handle.close();
            return new Outcome.Failure<>(FailureKind.TRANSPORT, "the client is closed");
        }
        return new Outcome.Success<>(handle);
    }

    /** The number of stream handles this client still tracks; the drain seam of its tests. */
    int liveStreamCount() {
        return liveStreams.size();
    }

    /**
     * Cancels every live Answers stream and releases each owned resource exactly once, most
     * recently owned first; idempotent. A stream or resource whose release throws never
     * strands the others: every remaining release still runs and the first failure
     * propagates.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<AnswersStreamHandle> streamsToRelease;
        synchronized (streamLifecycle) {
            streamsToRelease = new ArrayList<>(liveStreams);
            liveStreams.clear();
        }
        RuntimeException failure = null;
        for (AnswersStreamHandle handle : streamsToRelease) {
            try {
                handle.close();
            } catch (RuntimeException runtime) {
                failure = failure == null ? runtime : failure;
            }
        }
        for (int index = ownedResources.size() - 1; index >= 0; index--) {
            try {
                ownedResources.get(index).close();
            } catch (RuntimeException runtime) {
                failure = failure == null ? runtime : failure;
            } catch (Exception checked) {
                failure = failure == null ? new IllegalStateException("closing an owned resource failed", checked) : failure;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private <Response> Outcome<Response> whileOpen(Supplier<Outcome<Response>> call) {
        if (closed.get()) {
            return new Outcome.Failure<>(FailureKind.TRANSPORT, "the client is closed");
        }
        return call.get();
    }

    private static void requireKind(PlaceEnrichmentRequest request, PlaceEnrichmentRequest.Kind kind, String method) {
        if (request.kind() != kind) {
            throw new IllegalArgumentException(
                    method + " requires the " + kind + " enrichment kind, not " + request.kind());
        }
    }

    private static void requireBlocking(AnswersRequest request) {
        if (request.stream()) {
            throw new IllegalArgumentException("answers blocks a non-streaming request; answersStream streams");
        }
    }

    private static void requireStreaming(AnswersRequest request) {
        if (!request.stream()) {
            throw new IllegalArgumentException("answersStream requires a streaming request");
        }
    }

    /**
     * Collects the composition knobs of the assembled client. The token supplier is
     * required and explicit — the library never reads the environment and never applies
     * any configuration-file precedence on a consumer's behalf.
     */
    public static final class Builder {

        /** The connection-establishment budget used when the builder pins none. */
        public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

        /** The non-streaming total budget, dispatch through the complete bounded body, when none is pinned. */
        public static final Duration DEFAULT_TOTAL_TIMEOUT = Duration.ofSeconds(30);

        private Supplier<Credential> tokenSupplier;

        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;

        private Duration totalTimeout = DEFAULT_TOTAL_TIMEOUT;

        private ExecutorService executor;

        private String baseUrl;

        private Builder() {}

        /**
         * The explicit token source, resolved per exchange. A supplier that yields null or
         * throws makes the exchange report the local-configuration failure, never an
         * environment lookup.
         *
         * @throws NullPointerException when {@code tokenSupplier} is null
         */
        public Builder tokenSupplier(Supplier<Credential> tokenSupplier) {
            this.tokenSupplier = Objects.requireNonNull(tokenSupplier, "tokenSupplier");
            return this;
        }

        /**
         * The connection-establishment budget of every exchange.
         *
         * @throws NullPointerException when {@code connectTimeout} is null
         */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
            return this;
        }

        /**
         * The non-streaming total budget, dispatch through the complete bounded body.
         *
         * @throws NullPointerException when {@code totalTimeout} is null
         */
        public Builder totalTimeout(Duration totalTimeout) {
            this.totalTimeout = Objects.requireNonNull(totalTimeout, "totalTimeout");
            return this;
        }

        /**
         * A caller-owned executor the client's HTTP machinery rides; close() never shuts it
         * down. The default is a client-owned executor close() does shut down.
         *
         * @throws NullPointerException when {@code executor} is null
         */
        public Builder executor(ExecutorService executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        /**
         * An advanced origin override for tests and proxies: a literal loopback origin only
         * (http(s) on exactly {@code 127.0.0.1}, {@code [::1]}, or {@code localhost}, with
         * an optional port, no user information, no query, no fragment, and an empty or
         * slash path). The override changes where requests travel, not which credential
         * they carry: the caller's own token supplier feeds both origins alike — the
         * production origin and the override draw from the same supplier — so the caller
         * alone decides what its supplier may hand to either peer. The default is the
         * production origin.
         *
         * @throws NullPointerException when {@code loopbackBaseUrl} is null
         */
        public Builder baseUrl(String loopbackBaseUrl) {
            this.baseUrl = Objects.requireNonNull(loopbackBaseUrl, "loopbackBaseUrl");
            return this;
        }

        /**
         * Assembles the client through {@code api.internal.Assembler}, which instantiates
         * the concrete adapters and hands back the wiring this builder unpacks into the
         * facade — so this package never imports an adapter and the package hierarchy stays
         * cycle-free.
         *
         * @throws IllegalStateException when no token supplier was set
         * @throws IllegalArgumentException when a budget is not positive or the base-url
         *     override violates the loopback origin contract
         */
        public BraveSearchClient build() {
            if (tokenSupplier == null) {
                throw new IllegalStateException(
                        "the builder requires an explicit token supplier; the library never reads the environment");
            }
            if (connectTimeout.isZero() || connectTimeout.isNegative()) {
                throw new IllegalArgumentException("connect timeout must be positive");
            }
            if (totalTimeout.isZero() || totalTimeout.isNegative()) {
                throw new IllegalArgumentException("total timeout must be positive");
            }
            Assembler.ClientWiring wiring =
                    Assembler.assemble(tokenSupplier, connectTimeout, totalTimeout, executor, baseUrl);
            Function<String, JsonNode> tagPayloadReader = wiring.tagPayloadReader();
            Function<AnswersRequest, Outcome<AnswersStreamHandle>> answersStream = request -> openedHandle(
                    tagPayloadReader, wiring.answersStream().apply(request));
            return new BraveSearchClient(
                    wiring.webSearch(),
                    wiring.llmContext(),
                    wiring.newsSearch(),
                    wiring.videoSearch(),
                    wiring.imageSearch(),
                    wiring.suggest(),
                    wiring.spellcheck(),
                    wiring.placeSearch(),
                    wiring.placeEnrichment(),
                    wiring.rich(),
                    wiring.answers(),
                    answersStream,
                    wiring.upstreamReader(),
                    wiring.ownedResources());
        }

        /**
         * Wraps the composition's opened stream — plain functions over domain and JDK types —
         * into the public handle, so the handle construction itself never leaves the public
         * package.
         */
        private static Outcome<AnswersStreamHandle> openedHandle(
                Function<String, JsonNode> tagPayloadReader, Outcome<OpenedAnswersStream> opened) {
            return switch (opened) {
                case Outcome.Success<OpenedAnswersStream> success ->
                    new Outcome.Success<>(new ApiAnswersStreamHandle(success.value(), tagPayloadReader));
                case Outcome.Failure<OpenedAnswersStream> failure -> new Outcome.Failure<>(
                        failure.kind(), failure.diagnostic(), failure.upstream(), failure.rateLimits(), failure.httpStatus());
            };
        }
    }
}

package io.amscotti.bravesearch.api.internal;

import io.amscotti.bravesearch.adapter.bravehttp.BraveHttpClientFactory;
import io.amscotti.bravesearch.adapter.bravehttp.BraveHttpTransport;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.AnswersEndpoint;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.BraveHttpAnswersStreamGateway;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.BraveHttpBlockingGateway;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.BraveHttpBlockingGateway.Assembly;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.BraveHttpBlockingGateway.Projection;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.ContextEndpoint;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.ImageSearchEndpoint;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.NewsSearchEndpoint;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.PlaceEnrichmentEndpoint;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.PlaceSearchEndpoint;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.RichEndpoint;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.SpellcheckEndpoint;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.SuggestEndpoint;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.VideoSearchEndpoint;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.WebSearchEndpoint;
import io.amscotti.bravesearch.adapter.bravehttp.json.UpstreamJsonCodec;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.LocalhostResolver;
import io.amscotti.bravesearch.application.exchange.ResponseLimits;
import io.amscotti.bravesearch.application.port.out.AnswersPort;
import io.amscotti.bravesearch.application.port.out.AnswersStreamDispatch;
import io.amscotti.bravesearch.application.port.out.AnswersStreamExchange;
import io.amscotti.bravesearch.application.port.out.ContextPort;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.CredentialResolutionException;
import io.amscotti.bravesearch.application.port.out.ImageSearchPort;
import io.amscotti.bravesearch.application.port.out.NewsSearchPort;
import io.amscotti.bravesearch.application.port.out.PlaceSearchPort;
import io.amscotti.bravesearch.application.port.out.RichPort;
import io.amscotti.bravesearch.application.port.out.SpellcheckPort;
import io.amscotti.bravesearch.application.port.out.SuggestPort;
import io.amscotti.bravesearch.application.port.out.VideoSearchPort;
import io.amscotti.bravesearch.application.port.out.WebSearchPort;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.CancellationRegistry;
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
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;

/**
 * The library composition root: assembles the concrete HTTP gateways, the shared transport,
 * the streaming machinery, and the tolerant upstream reader into the public client the
 * builder hands out. Only composition roots may instantiate concrete adapters, and this is
 * the library's — the public {@code api} package never imports an adapter.
 *
 * <p>The credential travels only through the builder's explicit token supplier, resolved
 * per exchange; no environment or configuration-file precedence is ever applied here. The
 * origin is the production origin unless the builder pinned a loopback override, and the
 * origin's own routing rule keeps the supplier's credential away from any origin it did
 * not sign up for. The client owns the shared HTTP executor unless the builder supplied
 * one, and {@code close()} on the assembled client releases exactly what this composition
 * created: the live streams, the transport, and the owned executor.
 */
public final class Assembler {

    private Assembler() {}

    /**
     * Assembles the wiring of the builder's pinned configuration: one bound exchange per
     * endpoint, the shared upstream reader, and the resources the client owns. The record
     * is deliberately free of {@code api} types — the public builder unpacks it into the
     * facade — so this package never depends on the public package and the package
     * hierarchy stays cycle-free.
     *
     * @param tokenSupplier the caller's explicit token source, resolved per exchange
     * @param connectTimeout the connection-establishment budget of every exchange
     * @param totalTimeout the non-streaming total budget, dispatch through the bounded body
     * @param callerExecutor a caller-owned executor the HTTP machinery rides, or null for
     *     the client-owned default
     * @param loopbackBaseUrl a loopback origin override, or null for the production origin
     * @throws IllegalArgumentException when {@code loopbackBaseUrl} violates the loopback
     *     origin contract
     */
    public static ClientWiring assemble(
            Supplier<Credential> tokenSupplier,
            Duration connectTimeout,
            Duration totalTimeout,
            ExecutorService callerExecutor,
            String loopbackBaseUrl) {
        Objects.requireNonNull(tokenSupplier, "tokenSupplier");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(totalTimeout, "totalTimeout");
        BraveApiOrigin origin = loopbackBaseUrl == null
                ? BraveApiOrigin.production()
                : BraveApiOrigin.fromOverride(loopbackBaseUrl, LocalhostResolver.platform());
        // the library path has one credential source: the caller's own token supplier feeds
        // both origins alike, so the production origin and a loopback override draw from it
        TokenSupplierCredentials storedCredentials = new TokenSupplierCredentials(tokenSupplier);

        boolean executorOwned = callerExecutor == null;
        ExecutorService executor = executorOwned ? ownedExecutor() : callerExecutor;
        BraveHttpTransport transport = new BraveHttpTransport(
                new BraveHttpClientFactory(connectTimeout, executor).newClient(),
                totalTimeout,
                ResponseLimits.production(),
                Clock.systemUTC());

        // every blocking port is the one generic gateway bound to its endpoint's wire
        // translation: the assembly is the endpoint's own, the projection the result record's
        // constructor
        WebSearchPort webSearch =
                Assembler.<WebSearchRequest, WebSearchResult>gateway(transport, origin, storedCredentials, totalTimeout, WebSearchEndpoint::assemble, WebSearchResult::new)
                        ::exchange;
        ContextPort llmContext =
                Assembler.<ContextRequest, ContextResult>gateway(transport, origin, storedCredentials, totalTimeout, ContextEndpoint::assemble, ContextResult::new)
                        ::exchange;
        NewsSearchPort newsSearch =
                Assembler.<NewsSearchRequest, NewsSearchResult>gateway(transport, origin, storedCredentials, totalTimeout, NewsSearchEndpoint::assemble, NewsSearchResult::new)
                        ::exchange;
        VideoSearchPort videoSearch =
                Assembler.<VideoSearchRequest, VideoSearchResult>gateway(transport, origin, storedCredentials, totalTimeout, VideoSearchEndpoint::assemble, VideoSearchResult::new)
                        ::exchange;
        ImageSearchPort imageSearch =
                Assembler.<ImageSearchRequest, ImageSearchResult>gateway(transport, origin, storedCredentials, totalTimeout, ImageSearchEndpoint::assemble, ImageSearchResult::new)
                        ::exchange;
        SuggestPort suggest =
                Assembler.<SuggestRequest, SuggestSearchResult>gateway(transport, origin, storedCredentials, totalTimeout, SuggestEndpoint::assemble, SuggestSearchResult::new)
                        ::exchange;
        SpellcheckPort spellcheck = Assembler.<SpellcheckRequest, SpellcheckSearchResult>gateway(
                        transport, origin, storedCredentials, totalTimeout, SpellcheckEndpoint::assemble, SpellcheckSearchResult::new)
                ::exchange;
        PlaceSearchPort placeSearch =
                Assembler.<PlaceSearchRequest, PlaceSearchResult>gateway(transport, origin, storedCredentials, totalTimeout, PlaceSearchEndpoint::assemble, PlaceSearchResult::new)
                        ::exchange;
        Function<PlaceEnrichmentRequest, Outcome<PlaceEnrichmentResult>> placeEnrichment = Assembler.<PlaceEnrichmentRequest, PlaceEnrichmentResult>gateway(
                        transport,
                        origin,
                        storedCredentials,
                        totalTimeout,
                        PlaceEnrichmentEndpoint::assemble,
                        PlaceEnrichmentResult::new)
                ::exchange;
        RichPort rich = Assembler.<RichRequest, RichResult>gateway(transport, origin, storedCredentials, totalTimeout, RichEndpoint::assemble, RichResult::new)
                ::exchange;
        AnswersPort answers =
                Assembler.<AnswersRequest, AnswersResult>gateway(transport, origin, storedCredentials, totalTimeout, AnswersEndpoint::assemble, AnswersResult::new)
                        ::exchange;
        Function<AnswersRequest, Outcome<OpenedAnswersStream>> answersStream =
                streamOpener(origin, storedCredentials, connectTimeout);
        Function<String, JsonNode> tagPayloadReader = UpstreamJsonCodec::readTagPayload;

        List<AutoCloseable> ownedResources = executorOwned ? List.of(executor, transport) : List.of(transport);
        return new ClientWiring(
                webSearch::search,
                llmContext::search,
                newsSearch::search,
                videoSearch::search,
                imageSearch::search,
                suggest::suggest,
                spellcheck::spellcheck,
                placeSearch::search,
                placeEnrichment,
                rich::rich,
                answers::answer,
                answersStream,
                tagPayloadReader,
                UpstreamJsonCodec::readTree,
                ownedResources);
    }

    /**
     * The assembled library wiring: the bound exchanges, the opened-stream composition of the
     * streaming path, the shared readers, and the owned resources, all as plain functions
     * over domain and JDK types.
     */
    public record ClientWiring(
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
            Function<AnswersRequest, Outcome<OpenedAnswersStream>> answersStream,
            Function<String, JsonNode> tagPayloadReader,
            Function<UpstreamPayload, JsonNode> upstreamReader,
            List<AutoCloseable> ownedResources) {}

    /**
     * One blocking endpoint's gateway over the shared transport: the generic gateway bound to
     * the endpoint's wire translation, its credential resolved per exchange through the
     * origin's routing rule.
     */
    private static <Req, Res> BraveHttpBlockingGateway<Req, Res> gateway(
            BraveHttpTransport transport,
            BraveApiOrigin origin,
            CredentialProvider credentials,
            Duration totalTimeout,
            Assembly<Req> assembly,
            Projection<Res> projection) {
        return new BraveHttpBlockingGateway<>(
                transport, origin, credentials, credentials, totalTimeout, null, assembly, projection);
    }

    /**
     * The streaming opener: resolve the credential for the origin, open the exchange, and
     * reduce the opened exchange to the plain pieces of {@link OpenedAnswersStream} — the
     * semantic-frames publisher plus the terminal-cause machinery behind JDK functional
     * types — so the public package builds its handle without ever seeing an internal
     * exchange or latch type.
     */
    private static Function<AnswersRequest, Outcome<OpenedAnswersStream>> streamOpener(
            BraveApiOrigin origin, TokenSupplierCredentials storedCredentials, Duration connectTimeout) {
        CancellationRegistry registry = new ClientExchangeRegistry();
        BraveHttpAnswersStreamGateway gateway =
                new BraveHttpAnswersStreamGateway(
                        registry,
                        Clock.systemUTC(),
                        ResponseLimits.production(),
                        StandardSemanticStream.standard(),
                        BraveHttpAnswersStreamGateway::daemonReader,
                        Optional::empty);
        return request -> {
            try {
                return switch (gateway.stream(new AnswersStreamDispatch(
                        request, origin, storedCredentials.resolve(), connectTimeout, null))) {
                    case Outcome.Success<AnswersStreamExchange> opened -> {
                        AnswersStreamExchange exchange = opened.value();
                        yield new Outcome.Success<>(new OpenedAnswersStream(
                                exchange.semanticFrames(),
                                () -> exchange.cancellation().latch(CancellationContext.Cause.CLOSED),
                                exchange.cancellation()::cancelled,
                                exchange::close));
                    }
                    case Outcome.Failure<AnswersStreamExchange> failure -> failure.widen();
                };
            } catch (CredentialResolutionException unresolved) {
                return new Outcome.Failure<>(FailureKind.LOCAL_CONFIG, unresolved.getMessage());
            }
        };
    }

    /** The client-owned default executor: daemon platform threads with a stable name. */
    private static ExecutorService ownedExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        return Executors.newCachedThreadPool(task -> Thread.ofPlatform()
                .daemon(true)
                .name("brave-search-client-" + sequence.incrementAndGet())
                .unstarted(task));
    }
}

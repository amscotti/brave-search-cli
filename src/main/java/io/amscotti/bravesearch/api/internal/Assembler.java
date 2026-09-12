package io.amscotti.bravesearch.api.internal;

import io.amscotti.bravesearch.adapter.bravehttp.BraveHttpClientFactory;
import io.amscotti.bravesearch.adapter.bravehttp.BraveHttpTransport;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.BraveHttpAnswersGateway;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.BraveHttpAnswersStreamGateway;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.BraveHttpContextGateway;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.BraveHttpImageSearchGateway;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.BraveHttpNewsSearchGateway;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.BraveHttpPlaceEnrichmentGateway;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.BraveHttpPlaceSearchGateway;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.BraveHttpRichGateway;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.BraveHttpSpellcheckGateway;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.BraveHttpSuggestGateway;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.BraveHttpVideoSearchGateway;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.BraveHttpWebSearchGateway;
import io.amscotti.bravesearch.adapter.bravehttp.json.UpstreamJsonCodec;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.LocalhostResolver;
import io.amscotti.bravesearch.application.exchange.ResponseLimits;
import io.amscotti.bravesearch.application.port.out.AnswersStreamDispatch;
import io.amscotti.bravesearch.application.port.out.AnswersStreamExchange;
import io.amscotti.bravesearch.application.port.out.CredentialResolutionException;
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
import java.util.concurrent.CompletionException;
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
        TokenSupplierCredentials storedCredentials = new TokenSupplierCredentials(tokenSupplier);
        Supplier<Credential> loopbackTestToken = () -> {
            try {
                return storedCredentials.resolve();
            } catch (CredentialResolutionException unresolved) {
                // the gateways' library wiring carries resolution failures in this wrapper
                throw new CompletionException(unresolved);
            }
        };

        boolean executorOwned = callerExecutor == null;
        ExecutorService executor = executorOwned ? ownedExecutor() : callerExecutor;
        BraveHttpTransport transport = new BraveHttpTransport(
                new BraveHttpClientFactory(connectTimeout, executor).newClient(),
                totalTimeout,
                ResponseLimits.production(),
                Clock.systemUTC());

        Function<WebSearchRequest, Outcome<WebSearchResult>> webSearch = new BraveHttpWebSearchGateway(
                        transport, origin, storedCredentials, loopbackTestToken, totalTimeout, null)
                ::search;
        Function<ContextRequest, Outcome<ContextResult>> llmContext = new BraveHttpContextGateway(
                        transport, origin, storedCredentials, loopbackTestToken, totalTimeout, null)
                ::search;
        Function<NewsSearchRequest, Outcome<NewsSearchResult>> newsSearch = new BraveHttpNewsSearchGateway(
                        transport, origin, storedCredentials, loopbackTestToken, totalTimeout, null)
                ::search;
        Function<VideoSearchRequest, Outcome<VideoSearchResult>> videoSearch = new BraveHttpVideoSearchGateway(
                        transport, origin, storedCredentials, loopbackTestToken, totalTimeout, null)
                ::search;
        Function<ImageSearchRequest, Outcome<ImageSearchResult>> imageSearch = new BraveHttpImageSearchGateway(
                        transport, origin, storedCredentials, loopbackTestToken, totalTimeout, null)
                ::search;
        Function<SuggestRequest, Outcome<SuggestSearchResult>> suggest = new BraveHttpSuggestGateway(
                        transport, origin, storedCredentials, loopbackTestToken, totalTimeout, null)
                ::suggest;
        Function<SpellcheckRequest, Outcome<SpellcheckSearchResult>> spellcheck = new BraveHttpSpellcheckGateway(
                        transport, origin, storedCredentials, loopbackTestToken, totalTimeout, null)
                ::spellcheck;
        Function<PlaceSearchRequest, Outcome<PlaceSearchResult>> placeSearch = new BraveHttpPlaceSearchGateway(
                        transport, origin, storedCredentials, loopbackTestToken, totalTimeout, null)
                ::search;
        Function<PlaceEnrichmentRequest, Outcome<PlaceEnrichmentResult>> placeEnrichment =
                new BraveHttpPlaceEnrichmentGateway(
                                transport, origin, storedCredentials, loopbackTestToken, totalTimeout, null)
                        ::fetch;
        Function<RichRequest, Outcome<RichResult>> rich = new BraveHttpRichGateway(
                        transport, origin, storedCredentials, loopbackTestToken, totalTimeout, null)
                ::rich;
        Function<AnswersRequest, Outcome<AnswersResult>> answers = new BraveHttpAnswersGateway(
                        transport, origin, storedCredentials, loopbackTestToken, totalTimeout, null)
                ::answer;
        Function<AnswersRequest, Outcome<OpenedAnswersStream>> answersStream =
                streamOpener(origin, storedCredentials, connectTimeout);
        Function<String, JsonNode> tagPayloadReader = UpstreamJsonCodec::readTagPayload;

        List<AutoCloseable> ownedResources = executorOwned ? List.of(executor, transport) : List.of(transport);
        return new ClientWiring(
                webSearch,
                llmContext,
                newsSearch,
                videoSearch,
                imageSearch,
                suggest,
                spellcheck,
                placeSearch,
                placeEnrichment,
                rich,
                answers,
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
                        registry, Clock.systemUTC(), ResponseLimits.production(), StandardSemanticStream.standard());
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
                    case Outcome.Failure<AnswersStreamExchange> failure -> new Outcome.Failure<>(
                            failure.kind(),
                            failure.diagnostic(),
                            failure.upstream(),
                            failure.rateLimits(),
                            failure.httpStatus());
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

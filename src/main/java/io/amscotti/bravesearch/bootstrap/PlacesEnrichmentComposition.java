package io.amscotti.bravesearch.bootstrap;

import io.amscotti.bravesearch.adapter.bravehttp.BraveHttpClientFactory;
import io.amscotti.bravesearch.adapter.bravehttp.BraveHttpTransport;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.BraveHttpPlaceEnrichmentGateway;
import io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings;
import io.amscotti.bravesearch.adapter.cli.presentation.PlaceDescribePresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.PlaceDetailsPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.places.PlaceDescribePresenterImpl;
import io.amscotti.bravesearch.adapter.cli.presentation.places.PlaceDescriptionsExtractor;
import io.amscotti.bravesearch.adapter.cli.presentation.places.PlaceDetailsExtractor;
import io.amscotti.bravesearch.adapter.cli.presentation.places.PlaceDetailsPresenterImpl;
import io.amscotti.bravesearch.adapter.config.EnvironmentCredentialSource;
import io.amscotti.bravesearch.adapter.config.LoopbackTestKeyCredentials;
import io.amscotti.bravesearch.application.exchange.ResponseLimits;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.PlaceEnrichmentExchange;
import io.amscotti.bravesearch.application.service.PaginationService;
import io.amscotti.bravesearch.application.service.SlicedRateWaiter;
import io.amscotti.bravesearch.domain.result.PlaceEnrichmentResult;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Function;

/**
 * The place enrichment commands' process wiring: the loopback test-key source, the
 * chunk exchange that binds each parsed chunk invocation to the transport honoring its
 * own origin, budgets, and version pin, the sequential walk orchestrator of the chunk
 * fan-out with its cancellable pacing waiter, and the two presenters. Every part stays
 * injectable so the root command line runs hermetically in tests; the process factory
 * binds the real environment lookup and transport adapters.
 *
 * <p>Each chunk dispatch is a self-contained lifecycle: the invocation's
 * already-resolved credential is handed to a fresh single-exchange gateway, whose
 * transport and HTTP client close with the gateway after the exchange, so one walk
 * never leaves an open client behind and never resolves its credential twice. The walk
 * requests its chunks strictly sequentially.
 *
 * <p>The fan-out walk's continuation rule: the enrichment endpoints document no
 * continuation field, so the orchestrator is wired with the always-continue probe —
 * the chunk budget the invocation's id count composes is the walk's sole terminator.
 */
public record PlacesEnrichmentComposition(
        CredentialProvider loopbackTestToken,
        PlaceEnrichmentExchange exchange,
        PaginationService<PlaceEnrichmentResult> pagination,
        PlaceDetailsPresenter detailsPresenter,
        PlaceDescribePresenter describePresenter) {

    public PlacesEnrichmentComposition {
        Objects.requireNonNull(loopbackTestToken, "loopbackTestToken");
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(pagination, "pagination");
        Objects.requireNonNull(detailsPresenter, "detailsPresenter");
        Objects.requireNonNull(describePresenter, "describePresenter");
    }

    /** The process wiring: real environment lookup, real transport, per-invocation binding. */
    public static PlacesEnrichmentComposition process(CredentialProvider storedCredentials) {
        return process(storedCredentials, EnvironmentCredentialSource.processEnvironmentLookup());
    }

    /** The shared wiring behind the process factory, with the environment lookup injected. */
    static PlacesEnrichmentComposition process(CredentialProvider storedCredentials, Function<String, String> environmentLookup) {
        CredentialProvider loopbackTestToken = new LoopbackTestKeyCredentials(environmentLookup);
        PlaceEnrichmentExchange exchange = invocation -> {
            BraveHttpTransport transport = new BraveHttpTransport(
                    new BraveHttpClientFactory(invocation.connectTimeout()).newClient(),
                    invocation.totalTimeout(),
                    ResponseLimits.production(),
                    Clock.systemUTC(),
                    invocation.cancellation());
            try (BraveHttpPlaceEnrichmentGateway gateway = new BraveHttpPlaceEnrichmentGateway(
                    transport,
                    invocation.origin(),
                    invocation.credential(),
                    invocation.totalTimeout(),
                    invocation.pinnedApiVersion())) {
                return gateway.fetch(invocation.request());
            }
        };
        PaginationService<PlaceEnrichmentResult> pagination =
                new PaginationService<>(PaginationService.ContinuationProbe.alwaysContinue(), new SlicedRateWaiter(Clock.systemUTC()));
        JsonMappers mappers = new JsonMappers();
        JsonlCodec jsonl = new JsonlCodec(mappers);
        return new PlacesEnrichmentComposition(
                loopbackTestToken, exchange, pagination, detailsPresenter(mappers, jsonl), describePresenter(mappers, jsonl));
    }

    /** The places detail presenter over the shared machine-output codecs and the warnings channel. */
    private static PlaceDetailsPresenter detailsPresenter(JsonMappers mappers, JsonlCodec jsonl) {
        return new PlaceDetailsPresenterImpl(
                new EnvelopeCodec(mappers),
                jsonl,
                new PlaceDetailsExtractor(mappers),
                (mode, diagnostics, results, quiet) ->
                        new ModeAwareWarnings(mode, PlaceDetailsPresenter.COMMAND, diagnostics, results, jsonl, quiet));
    }

    /** The places describe presenter over the same codecs and warnings channel. */
    private static PlaceDescribePresenter describePresenter(JsonMappers mappers, JsonlCodec jsonl) {
        return new PlaceDescribePresenterImpl(
                new EnvelopeCodec(mappers),
                jsonl,
                new PlaceDescriptionsExtractor(mappers),
                (mode, diagnostics, results, quiet) ->
                        new ModeAwareWarnings(mode, PlaceDescribePresenter.COMMAND, diagnostics, results, jsonl, quiet));
    }
}

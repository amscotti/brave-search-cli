package io.amscotti.bravesearch.bootstrap;

import io.amscotti.bravesearch.adapter.bravehttp.endpoint.WebContinuationProbe;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.WebSearchEndpoint;
import io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings;
import io.amscotti.bravesearch.adapter.cli.presentation.WebPagedSearchPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.WebSearchPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.RawCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.web.WebPagedSearchPresenterImpl;
import io.amscotti.bravesearch.adapter.cli.presentation.web.WebProjectionExtractor;
import io.amscotti.bravesearch.adapter.cli.presentation.web.WebSearchPresenterImpl;
import io.amscotti.bravesearch.adapter.config.EnvironmentCredentialSource;
import io.amscotti.bravesearch.adapter.config.LoopbackTestKeyCredentials;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.RemoteExchange;
import io.amscotti.bravesearch.application.service.PaginationService;
import io.amscotti.bravesearch.application.service.SlicedRateWaiter;
import io.amscotti.bravesearch.domain.request.WebSearchRequest;
import io.amscotti.bravesearch.domain.result.WebSearchResult;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Function;

/**
 * The web command's process wiring: the loopback test-key source, the exchange that binds
 * each parsed invocation to the transport honoring its own origin, budgets, and version pin,
 * the pagination orchestrator of the multi-request page walk with its cancellable pacing
 * waiter, and the two presenters. Every part stays injectable so the root command line runs
 * hermetically in tests; the process factory binds the real environment lookup and transport
 * adapters.
 *
 * <p>Each dispatch is a self-contained lifecycle: the invocation's already-resolved
 * credential is handed to a fresh single-exchange gateway, whose transport and HTTP client
 * close with the gateway after the exchange, so one invocation never leaves an open client
 * behind and never resolves its credential twice. A sequential page walk instead opens one
 * shared transport and HTTP client across every page through {@link RemoteExchange#openWalk}
 * and closes it when the walk finishes.
 */
public record WebSearchComposition(
        CredentialProvider loopbackTestToken,
        RemoteExchange<WebSearchRequest, WebSearchResult> exchange,
        WebSearchPresenter presenter,
        PaginationService<WebSearchResult> pagination,
        WebPagedSearchPresenter pagedPresenter) {

    public WebSearchComposition {
        Objects.requireNonNull(loopbackTestToken, "loopbackTestToken");
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(presenter, "presenter");
        Objects.requireNonNull(pagination, "pagination");
        Objects.requireNonNull(pagedPresenter, "pagedPresenter");
    }

    /** The process wiring: real environment lookup, real transport, per-invocation binding. */
    public static WebSearchComposition process() {
        return process(EnvironmentCredentialSource.processEnvironmentLookup());
    }

    /** The shared wiring with machine codecs of its own, for a composition built standalone. */
    static WebSearchComposition process(Function<String, String> environmentLookup) {
        JsonMappers mappers = new JsonMappers();
        return process(environmentLookup, mappers, new JsonlCodec(mappers));
    }

    /** The shared wiring behind the process factory, with the environment lookup and the machine codecs injected. */
    static WebSearchComposition process(
            Function<String, String> environmentLookup, JsonMappers mappers, JsonlCodec jsonl) {
        CredentialProvider loopbackTestToken = new LoopbackTestKeyCredentials(environmentLookup);
        RemoteExchange<WebSearchRequest, WebSearchResult> exchange =
                RemoteExchanges.blocking(WebSearchEndpoint::assemble, WebSearchResult::new, environmentLookup);
        PaginationService<WebSearchResult> pagination =
                new PaginationService<>(new WebContinuationProbe(), new SlicedRateWaiter(Clock.systemUTC()));
        return new WebSearchComposition(
                loopbackTestToken, exchange, presenter(mappers, jsonl), pagination, pagedPresenter(mappers, jsonl));
    }

    /** The web presenter over the shared machine-output codecs and the warnings channel. */
    private static WebSearchPresenter presenter(JsonMappers mappers, JsonlCodec jsonl) {
        return new WebSearchPresenterImpl(
                new EnvelopeCodec(mappers),
                jsonl,
                new RawCodec(),
                new WebProjectionExtractor(mappers),
                (mode, diagnostics, results, quiet) ->
                        new ModeAwareWarnings(mode, WebSearchPresenter.COMMAND, diagnostics, results, jsonl, quiet));
    }

    /** The multi-request web presenter over the same codecs and warnings channel. */
    private static WebPagedSearchPresenter pagedPresenter(JsonMappers mappers, JsonlCodec jsonl) {
        return new WebPagedSearchPresenterImpl(
                new EnvelopeCodec(mappers),
                jsonl,
                new WebProjectionExtractor(mappers),
                (mode, diagnostics, results, quiet) ->
                        new ModeAwareWarnings(mode, WebPagedSearchPresenter.COMMAND, diagnostics, results, jsonl, quiet));
    }
}

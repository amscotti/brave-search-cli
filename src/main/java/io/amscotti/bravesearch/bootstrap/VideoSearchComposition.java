package io.amscotti.bravesearch.bootstrap;

import io.amscotti.bravesearch.adapter.bravehttp.BraveHttpClientFactory;
import io.amscotti.bravesearch.adapter.bravehttp.BraveHttpTransport;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.BraveHttpVideoSearchGateway;
import io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings;
import io.amscotti.bravesearch.adapter.cli.presentation.VideoPagedSearchPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.VideoSearchPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.RawCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.videos.VideoPagedSearchPresenterImpl;
import io.amscotti.bravesearch.adapter.cli.presentation.videos.VideoProjectionExtractor;
import io.amscotti.bravesearch.adapter.cli.presentation.videos.VideoSearchPresenterImpl;
import io.amscotti.bravesearch.adapter.config.EnvironmentCredentialSource;
import io.amscotti.bravesearch.adapter.config.LoopbackTestKeyCredentials;
import io.amscotti.bravesearch.application.exchange.ResponseLimits;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.VideoSearchExchange;
import io.amscotti.bravesearch.application.service.PaginationService;
import io.amscotti.bravesearch.application.service.SlicedRateWaiter;
import io.amscotti.bravesearch.domain.result.VideoSearchResult;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Function;

/**
 * The videos command's process wiring: the loopback test-key source, the exchange that
 * binds each parsed invocation to the transport honoring its own origin, budgets, and
 * version pin, the pagination orchestrator of the multi-request page walk with its
 * cancellable pacing waiter, and the two presenters. Every part stays injectable so the
 * root command line runs hermetically in tests; the process factory binds the real
 * environment lookup and transport adapters.
 *
 * <p>Each dispatch is a self-contained lifecycle: the invocation's already-resolved
 * credential is handed to a fresh single-exchange gateway, whose transport and HTTP client
 * close with the gateway after the exchange, so one invocation never leaves an open client
 * behind and never resolves its credential twice. A page walk reuses that same lifecycle per
 * page, strictly sequentially.
 *
 * <p>The videos walk's continuation rule: the videos endpoint documents no continuation
 * field, so the orchestrator is wired with the always-continue probe — the requested page
 * budget ({@code --max-pages} within the documented page range) is the walk's sole
 * terminator, and a short page never infers exhaustion.
 */
public record VideoSearchComposition(
        CredentialProvider loopbackTestToken,
        VideoSearchExchange exchange,
        VideoSearchPresenter presenter,
        PaginationService<VideoSearchResult> pagination,
        VideoPagedSearchPresenter pagedPresenter) {

    public VideoSearchComposition {
        Objects.requireNonNull(loopbackTestToken, "loopbackTestToken");
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(presenter, "presenter");
        Objects.requireNonNull(pagination, "pagination");
        Objects.requireNonNull(pagedPresenter, "pagedPresenter");
    }

    /** The process wiring: real environment lookup, real transport, per-invocation binding. */
    public static VideoSearchComposition process(CredentialProvider storedCredentials) {
        return process(storedCredentials, EnvironmentCredentialSource.processEnvironmentLookup());
    }

    /** The shared wiring behind the process factory, with the environment lookup injected. */
    static VideoSearchComposition process(CredentialProvider storedCredentials, Function<String, String> environmentLookup) {
        CredentialProvider loopbackTestToken = new LoopbackTestKeyCredentials(environmentLookup);
        VideoSearchExchange exchange = invocation -> {
            BraveHttpTransport transport = new BraveHttpTransport(
                    new BraveHttpClientFactory(invocation.connectTimeout()).newClient(),
                    invocation.totalTimeout(),
                    ResponseLimits.production(),
                    Clock.systemUTC(),
                    invocation.cancellation());
            try (BraveHttpVideoSearchGateway gateway = new BraveHttpVideoSearchGateway(
                    transport,
                    invocation.origin(),
                    invocation.credential(),
                    invocation.totalTimeout(),
                    invocation.pinnedApiVersion())) {
                return gateway.search(invocation.request());
            }
        };
        PaginationService<VideoSearchResult> pagination =
                new PaginationService<>(PaginationService.ContinuationProbe.alwaysContinue(), new SlicedRateWaiter(Clock.systemUTC()));
        JsonMappers mappers = new JsonMappers();
        JsonlCodec jsonl = new JsonlCodec(mappers);
        return new VideoSearchComposition(
                loopbackTestToken, exchange, presenter(mappers, jsonl), pagination, pagedPresenter(mappers, jsonl));
    }

    /** The videos presenter over the shared machine-output codecs and the warnings channel. */
    private static VideoSearchPresenter presenter(JsonMappers mappers, JsonlCodec jsonl) {
        return new VideoSearchPresenterImpl(
                new EnvelopeCodec(mappers),
                jsonl,
                new RawCodec(),
                new VideoProjectionExtractor(mappers),
                (mode, diagnostics, results, quiet) ->
                        new ModeAwareWarnings(mode, VideoSearchPresenter.COMMAND, diagnostics, results, jsonl, quiet));
    }

    /** The multi-request videos presenter over the same codecs and warnings channel. */
    private static VideoPagedSearchPresenter pagedPresenter(JsonMappers mappers, JsonlCodec jsonl) {
        return new VideoPagedSearchPresenterImpl(
                new EnvelopeCodec(mappers),
                jsonl,
                new VideoProjectionExtractor(mappers),
                (mode, diagnostics, results, quiet) ->
                        new ModeAwareWarnings(mode, VideoPagedSearchPresenter.COMMAND, diagnostics, results, jsonl, quiet));
    }
}

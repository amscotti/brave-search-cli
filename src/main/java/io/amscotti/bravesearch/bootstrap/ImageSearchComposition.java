package io.amscotti.bravesearch.bootstrap;

import io.amscotti.bravesearch.adapter.bravehttp.endpoint.ImageSearchEndpoint;
import io.amscotti.bravesearch.adapter.cli.presentation.ImageSearchPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings;
import io.amscotti.bravesearch.adapter.cli.presentation.images.ImageProjectionExtractor;
import io.amscotti.bravesearch.adapter.cli.presentation.images.ImageSearchPresenterImpl;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.RawCodec;
import io.amscotti.bravesearch.adapter.config.EnvironmentCredentialSource;
import io.amscotti.bravesearch.adapter.config.LoopbackTestKeyCredentials;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.RemoteExchange;
import io.amscotti.bravesearch.domain.request.ImageSearchRequest;
import io.amscotti.bravesearch.domain.result.ImageSearchResult;
import java.util.Objects;
import java.util.function.Function;

/**
 * The images command's process wiring: the loopback test-key source, the exchange that
 * binds each parsed invocation to the transport honoring its own origin, budgets, and
 * version pin, and the single-request presenter. Every part stays injectable so the root
 * command line runs hermetically in tests; the process factory binds the real environment
 * lookup and transport adapters.
 *
 * <p>Each dispatch is a self-contained lifecycle: the invocation's already-resolved
 * credential is handed to a fresh single-exchange gateway, whose transport and HTTP client
 * close with the gateway after the exchange, so one invocation never leaves an open client
 * behind and never resolves its credential twice. The images endpoint documents no
 * pagination, so there is no walk, no paged presenter, and no cancellation registry here:
 * one invocation is exactly one GET exchange.
 */
public record ImageSearchComposition(
        CredentialProvider loopbackTestToken, RemoteExchange<ImageSearchRequest, ImageSearchResult> exchange, ImageSearchPresenter presenter) {

    public ImageSearchComposition {
        Objects.requireNonNull(loopbackTestToken, "loopbackTestToken");
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(presenter, "presenter");
    }

    /** The process wiring: real environment lookup, real transport, per-invocation binding. */
    public static ImageSearchComposition process() {
        return process(EnvironmentCredentialSource.processEnvironmentLookup());
    }

    /** The shared wiring with machine codecs of its own, for a composition built standalone. */
    static ImageSearchComposition process(Function<String, String> environmentLookup) {
        JsonMappers mappers = new JsonMappers();
        return process(environmentLookup, mappers, new JsonlCodec(mappers));
    }

    /** The shared wiring behind the process factory, with the environment lookup and the machine codecs injected. */
    static ImageSearchComposition process(
            Function<String, String> environmentLookup, JsonMappers mappers, JsonlCodec jsonl) {
        CredentialProvider loopbackTestToken = new LoopbackTestKeyCredentials(environmentLookup);
        RemoteExchange<ImageSearchRequest, ImageSearchResult> exchange =
                RemoteExchanges.blocking(ImageSearchEndpoint::assemble, ImageSearchResult::new, environmentLookup);
        return new ImageSearchComposition(loopbackTestToken, exchange, presenter(mappers, jsonl));
    }

    /** The images presenter over the shared machine-output codecs and the warnings channel. */
    private static ImageSearchPresenter presenter(JsonMappers mappers, JsonlCodec jsonl) {
        return new ImageSearchPresenterImpl(
                new EnvelopeCodec(mappers),
                jsonl,
                new RawCodec(),
                new ImageProjectionExtractor(mappers),
                (mode, diagnostics, results, quiet) ->
                        new ModeAwareWarnings(mode, ImageSearchPresenter.COMMAND, diagnostics, results, jsonl, quiet));
    }
}

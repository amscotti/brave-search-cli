package io.amscotti.bravesearch.bootstrap;

import io.amscotti.bravesearch.adapter.bravehttp.endpoint.RichEndpoint;
import io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings;
import io.amscotti.bravesearch.adapter.cli.presentation.RichPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.RawCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.rich.RichPresenterImpl;
import io.amscotti.bravesearch.adapter.cli.presentation.rich.RichProjectionExtractor;
import io.amscotti.bravesearch.adapter.config.EnvironmentCredentialSource;
import io.amscotti.bravesearch.adapter.config.LoopbackTestKeyCredentials;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.RemoteExchange;
import io.amscotti.bravesearch.domain.request.RichRequest;
import io.amscotti.bravesearch.domain.result.RichResult;
import java.util.Objects;
import java.util.function.Function;

/**
 * The rich command's process wiring: the loopback test-key source, the exchange that
 * binds each parsed invocation to the transport honoring its own origin, budgets, and
 * version pin, and the single-request presenter. Every part stays injectable so the
 * root command line runs hermetically in tests; the process factory binds the real
 * environment lookup and transport adapters.
 *
 * <p>Each dispatch is a self-contained lifecycle: the invocation's already-resolved
 * credential is handed to a fresh single-exchange gateway, whose transport and HTTP
 * client close with the gateway after the exchange, so one invocation never leaves an
 * open client behind and never resolves its credential twice. The rich endpoint is one
 * callback lookup — no pagination, no streaming — so one invocation is exactly one GET
 * exchange.
 */
public record RichComposition(CredentialProvider loopbackTestToken, RemoteExchange<RichRequest, RichResult> exchange, RichPresenter presenter) {

    public RichComposition {
        Objects.requireNonNull(loopbackTestToken, "loopbackTestToken");
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(presenter, "presenter");
    }

    /** The process wiring: real environment lookup, real transport, per-invocation binding. */
    public static RichComposition process(CredentialProvider storedCredentials) {
        return process(storedCredentials, EnvironmentCredentialSource.processEnvironmentLookup());
    }

    /** The shared wiring with machine codecs of its own, for a composition built standalone. */
    static RichComposition process(CredentialProvider storedCredentials, Function<String, String> environmentLookup) {
        JsonMappers mappers = new JsonMappers();
        return process(storedCredentials, environmentLookup, mappers, new JsonlCodec(mappers));
    }

    /** The shared wiring behind the process factory, with the environment lookup and the machine codecs injected. */
    static RichComposition process(CredentialProvider storedCredentials, Function<String, String> environmentLookup, JsonMappers mappers, JsonlCodec jsonl) {
        CredentialProvider loopbackTestToken = new LoopbackTestKeyCredentials(environmentLookup);
        RemoteExchange<RichRequest, RichResult> exchange =
                RemoteExchanges.blocking(RichEndpoint::assemble, RichResult::new, environmentLookup);
        return new RichComposition(loopbackTestToken, exchange, presenter(mappers, jsonl));
    }

    /** The rich presenter over the shared machine-output codecs and the warnings channel. */
    private static RichPresenter presenter(JsonMappers mappers, JsonlCodec jsonl) {
        return new RichPresenterImpl(
                new EnvelopeCodec(mappers),
                jsonl,
                new RawCodec(),
                new RichProjectionExtractor(mappers),
                (mode, diagnostics, results, quiet) ->
                        new ModeAwareWarnings(mode, RichPresenter.COMMAND, diagnostics, results, jsonl, quiet));
    }
}

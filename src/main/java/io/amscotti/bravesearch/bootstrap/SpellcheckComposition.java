package io.amscotti.bravesearch.bootstrap;

import io.amscotti.bravesearch.adapter.bravehttp.endpoint.SpellcheckEndpoint;
import io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings;
import io.amscotti.bravesearch.adapter.cli.presentation.SpellcheckPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.RawCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.spellcheck.SpellcheckPresenterImpl;
import io.amscotti.bravesearch.adapter.cli.presentation.spellcheck.SpellcheckProjectionExtractor;
import io.amscotti.bravesearch.adapter.config.EnvironmentCredentialSource;
import io.amscotti.bravesearch.adapter.config.LoopbackTestKeyCredentials;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.RemoteExchange;
import io.amscotti.bravesearch.domain.request.SpellcheckRequest;
import io.amscotti.bravesearch.domain.result.SpellcheckSearchResult;
import java.util.Objects;
import java.util.function.Function;

/**
 * The spellcheck command's process wiring: the loopback test-key source, the exchange that
 * binds each parsed invocation to the transport honoring its own origin, budgets, and
 * version pin, and the single-request presenter. Every part stays injectable so the root
 * command line runs hermetically in tests; the process factory binds the real environment
 * lookup and transport adapters.
 *
 * <p>Each dispatch is a self-contained lifecycle: the invocation's already-resolved
 * credential is handed to a fresh single-exchange gateway, whose transport and HTTP client
 * close with the gateway after the exchange, so one invocation never leaves an open client
 * behind and never resolves its credential twice. The spellcheck endpoint documents no
 * pagination, so there is no walk, no paged presenter, and no cancellation registry here:
 * one invocation is exactly one GET exchange.
 */
public record SpellcheckComposition(
        CredentialProvider loopbackTestToken, RemoteExchange<SpellcheckRequest, SpellcheckSearchResult> exchange, SpellcheckPresenter presenter) {

    public SpellcheckComposition {
        Objects.requireNonNull(loopbackTestToken, "loopbackTestToken");
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(presenter, "presenter");
    }

    /** The process wiring: real environment lookup, real transport, per-invocation binding. */
    public static SpellcheckComposition process() {
        return process(EnvironmentCredentialSource.processEnvironmentLookup());
    }

    /** The shared wiring with machine codecs of its own, for a composition built standalone. */
    static SpellcheckComposition process(Function<String, String> environmentLookup) {
        JsonMappers mappers = new JsonMappers();
        return process(environmentLookup, mappers, new JsonlCodec(mappers));
    }

    /** The shared wiring behind the process factory, with the environment lookup and the machine codecs injected. */
    static SpellcheckComposition process(
            Function<String, String> environmentLookup, JsonMappers mappers, JsonlCodec jsonl) {
        CredentialProvider loopbackTestToken = new LoopbackTestKeyCredentials(environmentLookup);
        RemoteExchange<SpellcheckRequest, SpellcheckSearchResult> exchange =
                RemoteExchanges.blocking(SpellcheckEndpoint::assemble, SpellcheckSearchResult::new, environmentLookup);
        return new SpellcheckComposition(loopbackTestToken, exchange, presenter(mappers, jsonl));
    }

    /** The spellcheck presenter over the shared machine-output codecs and the warnings channel. */
    private static SpellcheckPresenter presenter(JsonMappers mappers, JsonlCodec jsonl) {
        return new SpellcheckPresenterImpl(
                new EnvelopeCodec(mappers),
                jsonl,
                new RawCodec(),
                new SpellcheckProjectionExtractor(mappers),
                (mode, diagnostics, results, quiet) ->
                        new ModeAwareWarnings(mode, SpellcheckPresenter.COMMAND, diagnostics, results, jsonl, quiet));
    }
}

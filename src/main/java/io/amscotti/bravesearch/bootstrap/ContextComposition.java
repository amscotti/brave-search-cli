package io.amscotti.bravesearch.bootstrap;

import io.amscotti.bravesearch.adapter.bravehttp.BraveHttpClientFactory;
import io.amscotti.bravesearch.adapter.bravehttp.BraveHttpTransport;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.BraveHttpContextGateway;
import io.amscotti.bravesearch.adapter.cli.presentation.ContextPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings;
import io.amscotti.bravesearch.adapter.cli.presentation.context.ContextPresenterImpl;
import io.amscotti.bravesearch.adapter.cli.presentation.context.ContextProjectionExtractor;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.RawCodec;
import io.amscotti.bravesearch.adapter.config.EnvironmentCredentialSource;
import io.amscotti.bravesearch.adapter.config.LoopbackTestKeyCredentials;
import io.amscotti.bravesearch.application.exchange.ResponseLimits;
import io.amscotti.bravesearch.application.port.out.ContextExchange;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Function;

/**
 * The context command's process wiring: the loopback test-key source, the exchange that
 * binds each parsed invocation to the transport honoring its own origin, budgets, and
 * version pin, and the presenter. Every part stays injectable so the root command line runs
 * hermetically in tests; the process factory binds the real environment lookup and
 * transport adapters.
 *
 * <p>Each dispatch is a self-contained lifecycle — the invocation's already-resolved
 * credential handed to a fresh single-exchange gateway whose transport and HTTP client
 * close with the gateway after the exchange — because a context retrieval is one single
 * non-paginated request: nothing walks pages, nothing outlives the run.
 */
public record ContextComposition(
        CredentialProvider loopbackTestToken, ContextExchange exchange, ContextPresenter presenter) {

    public ContextComposition {
        Objects.requireNonNull(loopbackTestToken, "loopbackTestToken");
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(presenter, "presenter");
    }

    /** The process wiring: real environment lookup, real transport, per-invocation binding. */
    public static ContextComposition process(CredentialProvider storedCredentials) {
        return process(storedCredentials, EnvironmentCredentialSource.processEnvironmentLookup());
    }

    /** The shared wiring behind the process factory, with the environment lookup injected. */
    static ContextComposition process(CredentialProvider storedCredentials, Function<String, String> environmentLookup) {
        CredentialProvider loopbackTestToken = new LoopbackTestKeyCredentials(environmentLookup);
        ContextExchange exchange = invocation -> {
            BraveHttpTransport transport = new BraveHttpTransport(
                    new BraveHttpClientFactory(invocation.connectTimeout()).newClient(),
                    invocation.totalTimeout(),
                    ResponseLimits.production(),
                    Clock.systemUTC(),
                    invocation.cancellation());
            try (BraveHttpContextGateway gateway = new BraveHttpContextGateway(
                    transport,
                    invocation.origin(),
                    invocation.credential(),
                    invocation.totalTimeout(),
                    invocation.pinnedApiVersion())) {
                return gateway.search(invocation.request());
            }
        };
        JsonMappers mappers = new JsonMappers();
        JsonlCodec jsonl = new JsonlCodec(mappers);
        return new ContextComposition(
                loopbackTestToken,
                exchange,
                new ContextPresenterImpl(
                        new EnvelopeCodec(mappers),
                        jsonl,
                        new RawCodec(),
                        new ContextProjectionExtractor(mappers),
                        (mode, diagnostics, results, quiet) ->
                                new ModeAwareWarnings(mode, ContextPresenter.COMMAND, diagnostics, results, jsonl, quiet)));
    }
}

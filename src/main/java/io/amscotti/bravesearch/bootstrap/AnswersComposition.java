package io.amscotti.bravesearch.bootstrap;

import io.amscotti.bravesearch.adapter.bravehttp.BraveHttpClientFactory;
import io.amscotti.bravesearch.adapter.bravehttp.BraveHttpTransport;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.BraveHttpAnswersGateway;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.BraveHttpAnswersStreamGateway;
import io.amscotti.bravesearch.adapter.bravehttp.sse.BoundedEventAccumulator;
import io.amscotti.bravesearch.adapter.cli.presentation.AnswersPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.AnswersStreamPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings;
import io.amscotti.bravesearch.adapter.cli.presentation.answers.AnswersPresenterImpl;
import io.amscotti.bravesearch.adapter.cli.presentation.answers.AnswersProjectionExtractor;
import io.amscotti.bravesearch.adapter.cli.presentation.answers.AnswersStreamPresenterImpl;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.RawCodec;
import io.amscotti.bravesearch.adapter.config.EnvironmentCredentialSource;
import io.amscotti.bravesearch.adapter.config.LoopbackTestKeyCredentials;
import io.amscotti.bravesearch.api.internal.StandardSemanticStream;
import io.amscotti.bravesearch.application.exchange.ResponseLimits;
import io.amscotti.bravesearch.application.port.out.AnswersExchange;
import io.amscotti.bravesearch.application.port.out.AnswersStreamPort;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.stream.CancellationRegistry;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Function;

/**
 * The answers command's process wiring: the loopback test-key source, the blocking
 * exchange that binds each parsed invocation to the transport honoring its own origin,
 * budgets, and version pin, the streaming gateway whose open exchanges register with the
 * process-wide cancellation registry, and the two renderers — blocking and streaming.
 * Every part stays injectable so the root command line runs hermetically in tests; the
 * process factory binds the real environment lookup and transport adapters.
 *
 * <p>Each dispatch is a self-contained lifecycle: the invocation's already-resolved
 * credential is handed to a fresh single-exchange gateway, whose transport and HTTP client
 * close with the gateway after the exchange, so one invocation never leaves an open client
 * behind and never resolves its credential twice. A streaming invocation instead opens one
 * streaming exchange — a gateway that owns its client, its reader, and its body publisher,
 * registers the run's cancellation context before connecting, and resolves the stream's
 * own idle and wall deadlines — and renders it through the streaming presenter, whose
 * buffered mode accumulates under the same decoded-stream ceiling the streaming adapter
 * family defines.
 */
public record AnswersComposition(
        CredentialProvider loopbackTestToken,
        AnswersExchange exchange,
        AnswersPresenter presenter,
        AnswersStreamPort streams,
        AnswersStreamPresenter streamingPresenter) {

    public AnswersComposition {
        Objects.requireNonNull(loopbackTestToken, "loopbackTestToken");
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(presenter, "presenter");
        Objects.requireNonNull(streams, "streams");
        Objects.requireNonNull(streamingPresenter, "streamingPresenter");
    }

    /**
     * The process wiring for the composition the bootstrap owns: the streaming gateway must
     * register its exchanges with the same registry the bootstrap's interrupt handler
     * latches, so a SIGINT reaches a live stream.
     */
    public static AnswersComposition process(CredentialProvider storedCredentials, CancellationRegistry registry) {
        return process(storedCredentials, EnvironmentCredentialSource.processEnvironmentLookup(), registry);
    }

    /** The shared wiring behind the process factory, with the environment lookup injected. */
    static AnswersComposition process(
            CredentialProvider storedCredentials,
            Function<String, String> environmentLookup,
            CancellationRegistry registry) {
        CredentialProvider loopbackTestToken = new LoopbackTestKeyCredentials(environmentLookup);
        AnswersExchange exchange = invocation -> {
            BraveHttpTransport transport = new BraveHttpTransport(
                    new BraveHttpClientFactory(invocation.connectTimeout()).newClient(),
                    invocation.totalTimeout(),
                    ResponseLimits.production(),
                    Clock.systemUTC(),
                    invocation.cancellation());
            try (BraveHttpAnswersGateway gateway = new BraveHttpAnswersGateway(
                    transport,
                    invocation.origin(),
                    invocation.credential(),
                    invocation.totalTimeout(),
                    invocation.pinnedApiVersion())) {
                return gateway.answer(invocation.request());
            }
        };
        JsonMappers mappers = new JsonMappers();
        JsonlCodec jsonl = new JsonlCodec(mappers);
        return new AnswersComposition(
                loopbackTestToken,
                exchange,
                presenter(mappers, jsonl),
                streamGateway(registry),
                streamingPresenter(mappers, jsonl));
    }

    /** The blocking answers presenter over the shared machine-output codecs and the warnings channel. */
    private static AnswersPresenter presenter(JsonMappers mappers, JsonlCodec jsonl) {
        return new AnswersPresenterImpl(
                new EnvelopeCodec(mappers),
                jsonl,
                new RawCodec(),
                new AnswersProjectionExtractor(mappers),
                (mode, diagnostics, results, quiet) ->
                        new ModeAwareWarnings(mode, AnswersPresenter.COMMAND, diagnostics, results, jsonl, quiet));
    }

    /**
     * The streaming gateway behind the port: its per-exchange semantic layer is the standard
     * semantic-stream wiring shared with the library composition root, because those are
     * concrete adapters only a composition root instantiates — and one shared factory keeps
     * both roots' parser and decoder bounds identical.
     */
    private static AnswersStreamPort streamGateway(CancellationRegistry registry) {
        return new BraveHttpAnswersStreamGateway(
                registry, Clock.systemUTC(), ResponseLimits.production(), StandardSemanticStream.standard());
    }

    /** The streaming presenter, whose buffered mode accumulates under the decoded-stream family ceiling. */
    private static AnswersStreamPresenter streamingPresenter(JsonMappers mappers, JsonlCodec jsonl) {
        return new AnswersStreamPresenterImpl(
                new EnvelopeCodec(mappers),
                jsonl,
                mappers,
                BoundedEventAccumulator.DEFAULT_MAX_BYTES,
                (mode, diagnostics, results, quiet) -> new ModeAwareWarnings(
                        mode, AnswersPresenter.COMMAND, diagnostics, results, jsonl, quiet));
    }

}

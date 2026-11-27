package io.amscotti.bravesearch.adapter.cli.command.answers;

import io.amscotti.bravesearch.adapter.cli.command.RemoteCommandSupport;
import io.amscotti.bravesearch.adapter.cli.exit.ExitCodeMapper;
import io.amscotti.bravesearch.adapter.cli.option.CommonSearchOptions;
import io.amscotti.bravesearch.adapter.cli.option.GlobalOptions;
import io.amscotti.bravesearch.adapter.cli.option.RemoteOptions;
import io.amscotti.bravesearch.adapter.cli.option.SimpleDurationConverter;
import io.amscotti.bravesearch.adapter.cli.presentation.AnswersPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.AnswersStreamPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.DiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import io.amscotti.bravesearch.application.port.out.AnswersStreamDispatch;
import io.amscotti.bravesearch.application.port.out.AnswersStreamExchange;
import io.amscotti.bravesearch.application.port.out.AnswersStreamPort;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.RemoteExchange;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.CancellationRegistry;
import io.amscotti.bravesearch.application.stream.CauseSignals;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.FailureSignal;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.error.UsageValidationError;
import io.amscotti.bravesearch.domain.output.CommandOutputProfile;
import io.amscotti.bravesearch.domain.output.OutputMode;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.request.AnswersRequest;
import io.amscotti.bravesearch.domain.result.AnswersResult;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The answers command: one question, one chat-completions exchange, one rendering. The
 * endpoint's own grammar is the answers family — {@code --model}, the explicit {@code
 * --stream/--no-stream} pair with streaming as the default, the citation, entity, and
 * research flags with their six bounded research members, the completion budget, the
 * seed, the locale trio, and the two stream deadlines — while the shared mixin narrows to
 * the {@code --country} and {@code --safe-search} spellings this endpoint documents; the
 * language option travels under this endpoint's own {@code --language}, never the search
 * verticals' {@code --search-lang}.
 *
 * <p>The local rejections are the request's own validation plus one channel rule: blocking
 * answers — {@code --no-stream} — is incompatible with {@code --output jsonl}, because
 * the JSONL channel belongs to the streaming family. Every rejection predates any
 * dispatch. A streaming invocation — the default, or explicit {@code --stream} — opens
 * one streaming exchange through the injected streaming port with only the connection
 * budget and the version pin: the blocking total deadline never applies to a stream,
 * whose idle and wall deadlines travel on the request itself. An exchange that never
 * opened renders through the shared failure presenter of the active mode; an open
 * exchange renders through the streaming presenter, which drives it to its terminal and
 * owns the exit status, and the command closes the exchange after that render returns.
 */
@Command(name = AnswersCommand.NAME, description = "Ask one question and print the completed grounded answer.")
public final class AnswersCommand extends RemoteCommandSupport<AnswersRequest, AnswersResult> {

    /** Registration name under the root command. */
    public static final String NAME = "answers";

    private final AnswersStreamPort streams;

    private final AnswersStreamPresenter streamingPresenter;

    @Mixin
    CommonSearchOptions commonOptions;

    @Parameters(
            index = "0",
            arity = "1..1",
            paramLabel = "QUESTION",
            description = "The question to answer; quote it and use -- when it starts like an option.")
    String question;

    @Option(
            names = "--model",
            paramLabel = "<string>",
            description = "The answers model; omitted by default so Brave selects its own.")
    String model;

    /**
     * The tri-state stream flag — streaming unless explicitly disabled. The negatable
     * pair keeps {@code --no-stream} explicit: absent means streaming, never a mode
     * inference, and the wire always carries the effective boolean.
     */
    @Option(
            names = "--stream",
            negatable = true,
            description = "Stream the answer as it arrives (the default); --no-stream waits for the completed answer.")
    Boolean stream;

    /** The effective stream choice: streaming unless {@code --no-stream} said otherwise. */
    private boolean streaming() {
        return stream == null || stream;
    }

    @Option(names = "--research", description = "Enable the research family; requires streaming.")
    boolean research;

    @Option(
            names = "--citations",
            negatable = true,
            description = "Ask for citations; enabled citations require streaming.")
    Boolean citations;

    @Option(
            names = "--entities",
            negatable = true,
            description = "Ask for entities; enabled entities require streaming.")
    Boolean entities;

    @Option(
            names = "--research-thinking",
            negatable = true,
            description = "Allow the research family to think; requires --research.")
    Boolean researchThinking;

    @Option(
            names = "--research-tokens-per-query",
            paramLabel = "<1024..16384>",
            description = "The research token budget per query; requires --research.")
    Integer researchTokensPerQuery;

    @Option(
            names = "--research-queries",
            paramLabel = "<1..50>",
            description = "The research query budget; requires --research.")
    Integer researchQueries;

    @Option(
            names = "--research-iterations",
            paramLabel = "<1..5>",
            description = "The research iteration budget; requires --research.")
    Integer researchIterations;

    @Option(
            names = "--research-seconds",
            paramLabel = "<1..300>",
            description = "The research wall-clock budget in seconds; requires --research.")
    Integer researchSeconds;

    @Option(
            names = "--research-results-per-query",
            paramLabel = "<1..60>",
            description = "The research results budget per query; requires --research.")
    Integer researchResultsPerQuery;

    @Option(
            names = "--max-completion-tokens",
            paramLabel = "<n>",
            description = "The completion token budget of the answer.")
    Integer maxCompletionTokens;

    @Option(names = "--seed", paramLabel = "<integer>", description = "The seed of the completion.")
    Integer seed;

    @Option(
            names = "--language",
            paramLabel = "<tag>",
            description = "The result language tag; this endpoint's own language spelling.")
    String language;

    @Option(
            names = "--idle-timeout",
            converter = SimpleDurationConverter.class,
            paramLabel = "<duration>",
            description = "The streaming idle deadline; optional, inert for a blocking answer.")
    Duration idleTimeout;

    @Option(
            names = "--stream-timeout",
            converter = SimpleDurationConverter.class,
            paramLabel = "<duration>",
            description = "The streaming wall-clock deadline; optional, inert for a blocking answer.")
    Duration streamTimeout;

    /**
     * @param commonOptions the shared search-option grammar this command narrows to the
     *     country and safe-search spellings through the complement guard
     */
    public AnswersCommand(
            GlobalOptions globals,
            RemoteOptions remoteOptions,
            CommonSearchOptions commonOptions,
            CredentialProvider storedCredentials,
            CredentialProvider loopbackTestToken,
            RemoteExchange<AnswersRequest, AnswersResult> exchange,
            AnswersStreamPort streams,
            CancellationRegistry cancellations,
            AnswersPresenter presenter,
            AnswersStreamPresenter streamingPresenter,
            ResultWriter results,
            Function<java.io.Writer, DiagnosticsSink> diagnosticsFactory) {
        super(
                globals,
                remoteOptions,
                storedCredentials,
                loopbackTestToken,
                exchange,
                presenter,
                cancellations,
                results,
                diagnosticsFactory);
        this.commonOptions = Objects.requireNonNull(commonOptions, "commonOptions");
        this.streams = Objects.requireNonNull(streams, "streams");
        this.streamingPresenter = Objects.requireNonNull(streamingPresenter, "streamingPresenter");
    }

    @Override
    protected String commandName() {
        return NAME;
    }

    @Override
    protected CommandOutputProfile outputProfile() {
        // one single non-paginated request: raw stays valid
        return CommandOutputProfile.REMOTE;
    }

    @Override
    protected AnswersRequest buildRequest() throws UsageValidationError {
        if (!streaming() && remoteOptions.outputMode() == OutputMode.JSONL) {
            throw new UsageValidationError("--output jsonl is not valid with --no-stream: blocking answers has no record stream");
        }
        commonOptions.rejectUndocumentedExcept("--country", "--safe-search");
        AnswersRequest.Builder builder = AnswersRequest.builder(question).stream(streaming());
        if (model != null) {
            builder.model(model);
        }
        if (citations != null) {
            builder.citations(citations);
        }
        if (entities != null) {
            builder.entities(entities);
        }
        if (research) {
            builder.research(true);
        }
        if (researchThinking != null) {
            builder.researchAllowThinking(researchThinking);
        }
        if (researchTokensPerQuery != null) {
            builder.researchMaximumTokensPerQuery(researchTokensPerQuery);
        }
        if (researchQueries != null) {
            builder.researchMaximumQueries(researchQueries);
        }
        if (researchIterations != null) {
            builder.researchMaximumIterations(researchIterations);
        }
        if (researchSeconds != null) {
            builder.researchMaximumSeconds(researchSeconds);
        }
        if (researchResultsPerQuery != null) {
            builder.researchMaximumResultsPerQuery(researchResultsPerQuery);
        }
        if (maxCompletionTokens != null) {
            builder.maxCompletionTokens(maxCompletionTokens);
        }
        if (seed != null) {
            builder.seed(seed);
        }
        if (commonOptions.country() != null) {
            builder.country(commonOptions.country());
        }
        if (language != null) {
            builder.language(language);
        }
        if (commonOptions.safeSearch() != null) {
            builder.safeSearch(commonOptions.safeSearch());
        }
        if (idleTimeout != null) {
            builder.idleTimeout(idleTimeout);
        }
        if (streamTimeout != null) {
            builder.streamTimeout(streamTimeout);
        }
        return builder.build();
    }


    /**
     * The streaming family's own dispatch step: a stream request registers one cancellation
     * context with the process registry and passes it into the streaming port — carrying only
     * the connection budget, never the blocking total deadline — so both the opening headers
     * phase and the live stream share that single latch without duplicate registration. A
     * failed open renders through the shared failure presenter of the active mode while a
     * user signal latched on that context decides the exit status; an open exchange renders
     * through the streaming presenter and closes once the render returns.
     */
    @Override
    protected int dispatchAndPresent(
            AnswersRequest request,
            Credential credential,
            OutputRequest output,
            ResultWriter results,
            DiagnosticsSink diagnostics) {
        if (!request.stream()) {
            return super.dispatchAndPresent(request, credential, output, results, diagnostics);
        }
        CancellationContext cancellation = new CancellationContext();
        Runnable detach = cancellations().register(cancellation);
        try {
            Outcome<AnswersStreamExchange> opened = streams.stream(new AnswersStreamDispatch(
                    request,
                    remoteOptions.origin(),
                    credential,
                    remoteOptions.connectTimeout(),
                    remoteOptions.apiVersionPin(),
                    cancellation));
            return switch (opened) {
                case Outcome.Success<AnswersStreamExchange> live -> {
                    try (AnswersStreamExchange exchange = live.value()) {
                        yield streamingPresenter.present(exchange, request, output, results, diagnostics);
                    }
                }
                case Outcome.Failure<AnswersStreamExchange> openFailure -> {
                    int rendered = renderOpenFailure(openFailure, request, output, results, diagnostics);
                    yield latchedSignalExit(cancellation).orElse(rendered);
                }
            };
        } finally {
            detach.run();
        }
    }

    /** An exchange that never opened carries no stream state: the shared failure presenter renders it per mode. */
    private int renderOpenFailure(
            Outcome.Failure<AnswersStreamExchange> failure,
            AnswersRequest request,
            OutputRequest output,
            ResultWriter results,
            DiagnosticsSink diagnostics) {
        return presenter.present(failure.widen(), request, output, results, diagnostics);
    }

    /**
     * The exit of the user signal that won the dispatch window's latch — 130 for SIGINT,
     * 143 for SIGTERM — or empty when no signal owns the window.
     */
    private static java.util.Optional<Integer> latchedSignalExit(CancellationContext cancellation) {
        return cancellation
                .cause()
                .flatMap(CauseSignals::failureSignal)
                .filter(signal -> signal == FailureSignal.INTERRUPTED || signal == FailureSignal.TERMINATED)
                .map(ExitCodeMapper::forSignal);
    }
}

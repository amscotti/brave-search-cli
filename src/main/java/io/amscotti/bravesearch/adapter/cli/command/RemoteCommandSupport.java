package io.amscotti.bravesearch.adapter.cli.command;

import io.amscotti.bravesearch.adapter.cli.exit.ExitCodeMapper;
import io.amscotti.bravesearch.adapter.cli.option.GlobalOptions;
import io.amscotti.bravesearch.adapter.cli.option.OutputModeCompatibility;
import io.amscotti.bravesearch.adapter.cli.option.RemoteOptions;
import io.amscotti.bravesearch.adapter.cli.presentation.DiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.SearchPresenter;
import io.amscotti.bravesearch.application.exchange.BraveApiRequest;
import io.amscotti.bravesearch.application.exchange.InvalidTokenException;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.CredentialResolutionException;
import io.amscotti.bravesearch.application.port.out.RemoteDispatch;
import io.amscotti.bravesearch.application.port.out.RemoteExchange;
import io.amscotti.bravesearch.application.service.PaginationService;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.CancellationRegistry;
import io.amscotti.bravesearch.application.stream.CauseSignals;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.FailureSignal;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.error.UsageValidationError;
import io.amscotti.bravesearch.domain.output.CommandOutputProfile;
import io.amscotti.bravesearch.domain.output.OutputMode;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Function;
import picocli.CommandLine;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

/**
 * The shared run policy of every remote search command, so one endpoint's skeleton is written
 * once and cloned by options and renderers instead of by copy.
 *
 * <p>The fixed spine, in order: local rejections first — option compatibility against the
 * command's output profile and the endpoint's own request validation, all failing as usage
 * errors before anything is dispatched — then the credential preflight, which resolves the
 * invocation's credential exactly once, routed by the invocation's origin: the stored
 * production credential never reaches a loopback override, which draws from the loopback
 * test-key source instead, and an unresolvable credential keeps the local-configuration
 * status with one redacted stderr line in every output mode, because it is a local
 * precondition failure, not a stream event. A resolved credential whose characters cannot
 * travel in the subscription-token header value fails the same way, still inside the
 * preflight, so no exchange is ever dispatched to die at request assembly. The surviving
 * credential travels into the exchange; the exchange's outcome renders through the injected
 * presenter, which owns its exit status. A concrete command supplies its name, output profile,
 * and request assembly — options to request is all a subclass remains, because the invocation
 * this spine dispatches carries every member the command line parsed.
 *
 * <p>The single exchange runs under a cancellation context registered with the process
 * registry, so the bootstrap's INT and TERM handlers reach it while it is live: a signal
 * latched mid-exchange fails the exchange as cancelled, the presenter still renders the
 * mode's failure document, and the process exits by the latched signal — the conventional
 * 130 of SIGINT or 143 of SIGTERM, the latch, not the rendered kind, deciding. An exchange
 * that completed keeps its own verdict, so a signal arriving after completion changes
 * nothing.
 *
 * @param <TReq> the command's parsed request type
 * @param <TRes> the command's exchange result type
 */
public abstract class RemoteCommandSupport<TReq, TRes> implements Callable<Integer> {

    @Mixin
    protected RemoteOptions remoteOptions;

    @Spec
    protected CommandSpec spec;

    private final GlobalOptions globals;

    private final CredentialProvider storedCredentials;

    private final CredentialProvider loopbackTestToken;

    private final RemoteExchange<TReq, TRes> exchange;

    /** The renderer of the command's exchange outcomes; subclasses of another family render through it too. */
    protected final SearchPresenter<TReq, TRes> presenter;

    private final CancellationRegistry cancellations;

    private final ResultWriter results;

    private final Function<java.io.Writer, DiagnosticsSink> diagnosticsFactory;

    /**
     * @param globals the shared all-command options, parsed before and after the subcommand
     *     token into one instance the composition owns
     * @param remoteOptions the remote mixin instance the composition injected with its
     *     name-resolution seam
     * @param storedCredentials the stored production credential source
     * @param loopbackTestToken the loopback test-key source a loopback origin draws from
     * @param exchange the outbound port of one fully parsed invocation
     * @param presenter the renderer of the command's exchange outcomes
     * @param cancellations the process registry that lets a signal cancel the live
     *     single-request exchange
     * @param results the byte-lossless process stdout writer; write failures carry the
     *     operating system's identity so a broken pipe stays classifiable
     * @param diagnosticsFactory the stderr diagnostics channel factory
     */
    protected RemoteCommandSupport(
            GlobalOptions globals,
            RemoteOptions remoteOptions,
            CredentialProvider storedCredentials,
            CredentialProvider loopbackTestToken,
            RemoteExchange<TReq, TRes> exchange,
            SearchPresenter<TReq, TRes> presenter,
            CancellationRegistry cancellations,
            ResultWriter results,
            Function<java.io.Writer, DiagnosticsSink> diagnosticsFactory) {
        this.globals = Objects.requireNonNull(globals, "globals");
        this.remoteOptions = Objects.requireNonNull(remoteOptions, "remoteOptions");
        this.storedCredentials = Objects.requireNonNull(storedCredentials, "storedCredentials");
        this.loopbackTestToken = Objects.requireNonNull(loopbackTestToken, "loopbackTestToken");
        this.exchange = Objects.requireNonNull(exchange, "exchange");
        this.presenter = Objects.requireNonNull(presenter, "presenter");
        this.cancellations = Objects.requireNonNull(cancellations, "cancellations");
        this.results = Objects.requireNonNull(results, "results");
        this.diagnosticsFactory = Objects.requireNonNull(diagnosticsFactory, "diagnosticsFactory");
    }

    /** The canonical command name under the root command. */
    protected abstract String commandName();

    /**
     * The output capability of the command's family: every single-request remote command —
     * web, images, suggest, spellcheck — returns the single-request remote profile whose
     * raw channel stays valid, while a command whose invocation fans out returns the
     * multi-request profile.
     */
    protected abstract CommandOutputProfile outputProfile();

    /**
     * Assembles the endpoint's domain request from the parsed options.
     *
     * @throws UsageValidationError when a parsed combination violates the endpoint contract
     */
    protected abstract TReq buildRequest() throws UsageValidationError;

    /** The process registry that lets a signal cancel this run's live exchanges. */
    protected final CancellationRegistry cancellations() {
        return cancellations;
    }

    /**
     * The exchanges of this command's endpoint: the single exchange, and the walk a
     * multi-request run opens over its scope.
     */
    protected final RemoteExchange<TReq, TRes> exchange() {
        return exchange;
    }

    /**
     * The dispatch of one request under this invocation's command line — its origin, budgets,
     * and version pin — around the preflight-resolved credential and the run's cancellation
     * latch, which the exchange joins for its whole life so a signal reaching the live
     * exchange cancels it.
     */
    protected final RemoteDispatch<TReq> dispatchOf(TReq request, Credential credential, CancellationContext cancellation) {
        return new RemoteDispatch<>(
                request,
                remoteOptions.origin(),
                credential,
                remoteOptions.totalTimeout(),
                remoteOptions.connectTimeout(),
                remoteOptions.apiVersionPin(),
                cancellation);
    }

    /**
     * Announces each pacing wait of a sequential run on the diagnostics channel before it
     * happens, naming the request it precedes, so a human never watches a walk pause without
     * an explanation. The announcement follows the channel conventions: stderr is the human
     * channel, so the human mode always hears it and a machine mode hears it only under
     * {@code --verbose}, whose diagnostics reach stderr in every mode — a machine document's
     * stream stays exactly what its schema says. {@code --quiet} silences it everywhere.
     *
     * @param requestNoun what the walk counts, {@code page} or {@code request}
     */
    protected static PaginationService.PacingListener announcePacingWaits(
            String requestNoun, String command, OutputRequest output, DiagnosticsSink diagnostics) {
        boolean heard = output.mode() == OutputMode.HUMAN || output.verbose();
        return (request, outstanding) -> {
            if (heard && !output.quiet()) {
                diagnostics.emit(command + ": waiting " + renderWait(outstanding)
                        + " for the rate-limit window to reset before " + requestNoun + " " + request);
            }
        };
    }

    /** Whole seconds as {@code 2s}, anything finer in milliseconds: {@code 1500 ms}. */
    private static String renderWait(Duration outstanding) {
        long millis = outstanding.toMillis();
        return millis % 1000 == 0 ? millis / 1000 + "s" : millis + " ms";
    }

    @Override
    public final Integer call() {
        CommandLine commandLine = spec.commandLine();
        DiagnosticsSink diagnostics = diagnosticsFactory.apply(commandLine.getErr());
        OutputRequest outputRequest = new OutputRequest(
                remoteOptions.outputFlagPresent(),
                remoteOptions.outputMode(),
                remoteOptions.pretty(),
                globals.verbose(),
                globals.quiet(),
                globals.colorEnabled(),
                globals.outputWidth());
        List<String> violations = OutputModeCompatibility.check(outputProfile(), outputRequest);
        if (!violations.isEmpty()) {
            throw new ParameterException(commandLine, String.join("; ", violations));
        }
        TReq request;
        try {
            request = buildRequest();
        } catch (UsageValidationError invalid) {
            throw new ParameterException(commandLine, invalid.getMessage());
        }
        Credential credential;
        try {
            credential = CredentialProvider.routedTo(remoteOptions.origin(), storedCredentials, loopbackTestToken)
                    .resolve();
        } catch (CredentialResolutionException unresolved) {
            // the line says what to do when no source provided a key at all; an unset test
            // variable or a broken value is fixed elsewhere, so it keeps the bare message
            String remedy = unresolved.noSourceProvided() ? "; store one with 'brave-search config set-key'" : "";
            diagnostics.emit(commandName() + ": " + unresolved.getMessage() + remedy);
            return ExitCodeMapper.forKind(FailureKind.LOCAL_CONFIG);
        }
        try {
            BraveApiRequest.requireWireableToken(credential);
        } catch (InvalidTokenException unwirable) {
            diagnostics.emit(commandName() + ": " + unwirable.getMessage());
            return ExitCodeMapper.forKind(unwirable.kind());
        }
        return dispatchAndPresent(request, credential, outputRequest, results, diagnostics);
    }

    /**
     * The exchange-and-render step of the spine: the default registers a fresh cancellation
     * context with the process registry, performs the single exchange under it, and hands the
     * outcome to the presenter. An exchange that failed while the latch held a user signal
     * keeps the presenter's rendered document but exits by that signal — the conventional 130
     * of SIGINT or 143 of SIGTERM; a completed exchange keeps its own verdict. A command whose
     * invocation fans out into several sequential exchanges overrides this hook with its own
     * orchestration, keeping every local rejection and the credential preflight above
     * untouched.
     */
    protected int dispatchAndPresent(
            TReq request, Credential credential, OutputRequest output, ResultWriter results, DiagnosticsSink diagnostics) {
        CancellationContext cancellation = new CancellationContext();
        Runnable detach = cancellations.register(cancellation);
        try {
            Outcome<TRes> outcome = exchange.dispatch(dispatchOf(request, credential, cancellation));
            int presented = presenter.present(outcome, request, output, results, diagnostics);
            Optional<Integer> signalExit = latchedSignalExit(outcome, cancellation);
            // the latch is authoritative: the mode rendered its failure document, while the
            // signalled process exits by its signal
            return signalExit.orElse(presented);
        } finally {
            detach.run();
        }
    }

    /**
     * The exit status of the user signal that owns a failed exchange's latch — the
     * conventional 130 of SIGINT or 143 of SIGTERM — or empty when the exchange completed or
     * no user signal owns the latch.
     */
    private static Optional<Integer> latchedSignalExit(Outcome<?> outcome, CancellationContext cancellation) {
        return switch (outcome) {
            case Outcome.Success<?> completed -> Optional.empty();
            case Outcome.Failure<?> failed -> cancellation
                    .cause()
                    .flatMap(CauseSignals::failureSignal)
                    .filter(signal -> signal == FailureSignal.INTERRUPTED || signal == FailureSignal.TERMINATED)
                    .map(ExitCodeMapper::forSignal);
        };
    }
}

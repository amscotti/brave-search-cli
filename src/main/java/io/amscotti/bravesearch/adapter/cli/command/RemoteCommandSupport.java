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
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.CancellationRegistry;
import io.amscotti.bravesearch.application.stream.CauseSignals;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.FailureSignal;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.error.UsageValidationError;
import io.amscotti.bravesearch.domain.output.CommandOutputProfile;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import picocli.CommandLine;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

/**
 * The shared run policy of every remote search command, so one endpoint's skeleton is
 * written once and cloned by options and renderers instead of by copy.
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
 * presenter, which owns its exit status. A concrete command supplies its name, output profile, request assembly, and
 * the exchange call — options to request to dispatch is all a subclass remains.
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
            SearchPresenter<TReq, TRes> presenter,
            CancellationRegistry cancellations,
            ResultWriter results,
            Function<java.io.Writer, DiagnosticsSink> diagnosticsFactory) {
        this.globals = Objects.requireNonNull(globals, "globals");
        this.remoteOptions = Objects.requireNonNull(remoteOptions, "remoteOptions");
        this.storedCredentials = Objects.requireNonNull(storedCredentials, "storedCredentials");
        this.loopbackTestToken = Objects.requireNonNull(loopbackTestToken, "loopbackTestToken");
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

    /**
     * Performs the exchange of {@code request} under the preflight-resolved credential,
     * joining {@code cancellation} for the exchange's whole life so a signal reaching the
     * live exchange cancels it.
     */
    protected abstract Outcome<TRes> exchange(TReq request, Credential credential, CancellationContext cancellation);

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
            credential = remoteOptions
                    .origin()
                    .credentialSource(() -> resolve(storedCredentials), () -> resolve(loopbackTestToken))
                    .get();
        } catch (CompletionException routed) {
            if (routed.getCause() instanceof CredentialResolutionException unresolved) {
                diagnostics.emit(commandName() + ": " + unresolved.getMessage());
                return ExitCodeMapper.forKind(FailureKind.LOCAL_CONFIG);
            }
            throw routed;
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
     * context with the process registry, performs the single exchange under it, and hands
     * the outcome to the presenter. An exchange that failed while the latch held a user
     * signal keeps the presenter's rendered document but exits by that signal — the
     * conventional 130 of SIGINT or 143 of SIGTERM; a completed exchange keeps its own
     * verdict. A command whose invocation fans out into several sequential exchanges
     * overrides this hook with its own orchestration, keeping every local rejection and the
     * credential preflight above untouched.
     */
    protected int dispatchAndPresent(
            TReq request, Credential credential, OutputRequest output, ResultWriter results, DiagnosticsSink diagnostics) {
        CancellationContext cancellation = new CancellationContext();
        Runnable detach = cancellations.register(cancellation);
        try {
            Outcome<TRes> outcome = exchange(request, credential, cancellation);
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

    private static Credential resolve(CredentialProvider provider) {
        try {
            return provider.resolve();
        } catch (CredentialResolutionException unresolved) {
            throw new CompletionException(unresolved);
        }
    }
}

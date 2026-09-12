package io.amscotti.bravesearch.adapter.cli.command;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.exit.ExitCodeMapper;
import io.amscotti.bravesearch.adapter.cli.option.GlobalOptions;
import io.amscotti.bravesearch.adapter.cli.option.RemoteOptions;
import io.amscotti.bravesearch.adapter.cli.option.StrictOptionParsing;
import io.amscotti.bravesearch.adapter.cli.presentation.DiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.OutputStreamResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.SearchPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.WriterDiagnosticsSink;
import io.amscotti.bravesearch.application.exchange.LocalhostResolver;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.ResolvedCredential;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.CancellationRegistry;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.error.UsageValidationError;
import io.amscotti.bravesearch.domain.output.CommandOutputProfile;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * The signal-exit contract of the single-request spine: an exchange that failed while its
 * run latched SIGINT or SIGTERM renders the mode's failure document but exits by the
 * conventional signal status — the latch, not the rendered kind, decides — while a
 * completed exchange keeps success, a plain failure without a latched signal keeps its
 * kind's own status, and the exchange always runs under the cancellation context the spine
 * registered with the process registry.
 */
final class SingleRequestSignalExitTest {

    private static final LocalhostResolver STUB_LOCALHOST = host -> List.of(InetAddress.getByName("127.0.0.1"));

    @Test
    void aFailedExchangeUnderALatchedInterruptExits130() {
        Harness harness = new Harness(CancellationContext.Cause.SIGINT, true);

        harness.run();

        assertEquals(130, harness.exitCode, () -> harness.describe());
        assertEquals(
                ExitCodeMapper.forKind(FailureKind.TRANSPORT),
                harness.presenterExit,
                "the presenter still rendered its transport-failure document first");
        assertEquals("", harness.stderr(), () -> harness.describe());
    }

    @Test
    void aFailedExchangeUnderALatchedTerminationExits143() {
        Harness harness = new Harness(CancellationContext.Cause.SIGTERM, true);

        harness.run();

        assertEquals(143, harness.exitCode, () -> harness.describe());
        assertEquals(
                ExitCodeMapper.forKind(FailureKind.TRANSPORT),
                harness.presenterExit,
                "the presenter still rendered its transport-failure document first");
    }

    @Test
    void aCompletedExchangeKeepsSuccess() {
        Harness harness = new Harness(null, false);

        harness.run();

        assertEquals(0, harness.exitCode, () -> harness.describe());
        assertTrue(harness.presenterSawSuccess, "a completed exchange renders its success document");
    }

    @Test
    void aPlainTransportFailureWithoutASignalKeepsItsOwnStatus() {
        Harness harness = new Harness(null, true);

        harness.run();

        assertEquals(ExitCodeMapper.forKind(FailureKind.TRANSPORT), harness.exitCode, () -> harness.describe());
    }

    @Test
    void theExchangeRunsUnderTheContextTheSpineRegistered() {
        Harness harness = new Harness(null, false);

        harness.run();

        assertNotNull(harness.dispatchedContext, "an exchange always runs under a cancellation context");
        assertSame(
                harness.registeredBySpine.get(),
                harness.dispatchedContext,
                "the context handed to the exchange is the one registered with the process registry");
    }

    /** A minimal concrete single-request command: no options beyond the spine's own. */
    @Command(name = ProbeCommand.NAME, description = "Probe command of the signal-exit contract.")
    public static final class ProbeCommand extends RemoteCommandSupport<String, String> {

        static final String NAME = "signal-probe";

        private final CancellationContext.Cause latchOnExchange;

        private final boolean exchangeFails;

        private final java.util.concurrent.atomic.AtomicReference<CancellationContext> dispatchedContextRef;

        public ProbeCommand(
                GlobalOptions globals,
                RemoteOptions remoteOptions,
                CredentialProvider stored,
                CredentialProvider loopback,
                SearchPresenter<String, String> presenter,
                CancellationRegistry cancellations,
                ResultWriter results,
                Function<Writer, DiagnosticsSink> diagnosticsFactory,
                CancellationContext.Cause latchOnExchange,
                boolean exchangeFails,
                java.util.concurrent.atomic.AtomicReference<CancellationContext> dispatchedContextRef) {
            super(globals, remoteOptions, stored, loopback, presenter, cancellations, results, diagnosticsFactory);
            this.latchOnExchange = latchOnExchange;
            this.exchangeFails = exchangeFails;
            this.dispatchedContextRef = dispatchedContextRef;
        }

        @Override
        protected String commandName() {
            return NAME;
        }

        @Override
        protected CommandOutputProfile outputProfile() {
            return CommandOutputProfile.REMOTE;
        }

        @Override
        protected String buildRequest() throws UsageValidationError {
            return "probe query";
        }

        @Override
        protected Outcome<String> exchange(String request, Credential credential, CancellationContext cancellation) {
            dispatchedContextRef.set(cancellation);
            if (latchOnExchange != null) {
                cancellation.latch(latchOnExchange);
            }
            return exchangeFails
                    ? new Outcome.Failure<>(FailureKind.TRANSPORT, "upstream exchange cancelled")
                    : new Outcome.Success<>("done");
        }
    }

    @Command(name = "brave-search", description = "Brave Search command line client for humans and autonomous agents.")
    static final class Root implements Runnable {

        @Mixin
        GlobalOptions globals;

        @Spec CommandSpec spec;

        @Override
        public void run() {
            spec.commandLine().usage(spec.commandLine().getOut());
        }
    }

    private static final class RecordingPresenter implements SearchPresenter<String, String> {

        int exit;
        boolean sawSuccess;

        @Override
        public int present(
                Outcome<String> outcome,
                String request,
                OutputRequest output,
                ResultWriter results,
                DiagnosticsSink diagnostics) {
            return switch (outcome) {
                case Outcome.Success<String> ignored -> {
                    sawSuccess = true;
                    yield exit = 0;
                }
                case Outcome.Failure<String> failure -> exit = ExitCodeMapper.forKind(failure.kind());
            };
        }
    }

    private static final class RecordingProvider implements CredentialProvider {

        @Override
        public Credential resolve() {
            return Credential.of("probe-token".getBytes(UTF_8));
        }

        @Override
        public ResolvedCredential resolveWithProvenance() {
            return new ResolvedCredential(resolve(), "test source", false);
        }
    }

    private static final class Harness {

        final java.util.concurrent.atomic.AtomicReference<CancellationContext> registeredBySpine =
                new java.util.concurrent.atomic.AtomicReference<>();

        final java.util.concurrent.atomic.AtomicReference<CancellationContext> dispatchedByProbe =
                new java.util.concurrent.atomic.AtomicReference<>();

        final java.io.ByteArrayOutputStream stdoutBytes = new java.io.ByteArrayOutputStream();

        final java.io.ByteArrayOutputStream stderrBytes = new java.io.ByteArrayOutputStream();

        final RecordingPresenter presenter = new RecordingPresenter();

        final ProbeCommand probe;

        final CommandLine commandLine;

        int exitCode;

        CancellationContext dispatchedContext;

        int presenterExit;

        boolean presenterSawSuccess;

        Harness(CancellationContext.Cause latchOnExchange, boolean exchangeFails) {
            GlobalOptions globals = new GlobalOptions();
            // the registry observes which context is live while the exchange runs
            CancellationRegistry observingRegistry = context -> {
                registeredBySpine.set(context);
                return () -> {};
            };
            probe = new ProbeCommand(
                    globals,
                    new RemoteOptions(STUB_LOCALHOST),
                    new RecordingProvider(),
                    new RecordingProvider(),
                    presenter,
                    observingRegistry,
                    new OutputStreamResultWriter(stdoutBytes),
                    WriterDiagnosticsSink::new,
                    latchOnExchange,
                    exchangeFails,
                    dispatchedByProbe);
            dispatchedContext = null;
            Root root = new Root();
            root.globals = globals;
            commandLine = new CommandLine(root);
            commandLine.addSubcommand(ProbeCommand.NAME, new CommandLine(probe));
            StrictOptionParsing.apply(commandLine);
            commandLine.setOut(new PrintWriter(new java.io.OutputStreamWriter(stdoutBytes, UTF_8), true));
            commandLine.setErr(new PrintWriter(new java.io.OutputStreamWriter(stderrBytes, UTF_8), true));
        }

        void run() {
            exitCode = commandLine.execute(ProbeCommand.NAME);
            presenterExit = presenter.exit;
            presenterSawSuccess = presenter.sawSuccess;
            dispatchedContext = dispatchedByProbe.get();
        }

        String stderr() {
            return stderrBytes.toString(UTF_8);
        }

        String describe() {
            return "exitCode=" + exitCode + ", stderr=<" + stderr() + ">";
        }
    }
}

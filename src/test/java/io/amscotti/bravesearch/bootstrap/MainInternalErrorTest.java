package io.amscotti.bravesearch.bootstrap;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.presentation.ContextPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.DiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import io.amscotti.bravesearch.application.port.out.ConfigStore;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.ResolvedCredential;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.config.LocalConfigException;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.request.ContextRequest;
import io.amscotti.bravesearch.domain.result.ContextResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * The safety net of the entry point: an unexpected crash inside a command — here a
 * presenter that throws a runtime exception mid-render — must end as the documented
 * internal status with exactly one redacted stderr line, a local-configuration failure that
 * escapes the command layer keeps its own status and message, and a composition defect
 * during construction ends as the internal status instead of a raw stack trace. No stack
 * frames, no internal class names beyond the exception's own simple name, and no stdout
 * payload may escape.
 */
final class MainInternalErrorTest {

    @TempDir
    Path home;

    @Test
    void anUnexpectedCrashInsideACommandExitsSeventyWithOneRedactedLine() {
        RunResult result = runMain(contextComposition(new CrashingPresenter()), "context", "q");

        assertEquals(70, result.exitCode(), "the internal status owns an unexpected crash: " + result.describe());
        List<String> lines = result.stderr().lines().toList();
        assertEquals(1, lines.size(), "exactly one stderr line: " + result.describe());
        assertEquals(
                "brave-search: unexpected internal error (IllegalStateException)",
                lines.getFirst(),
                "the line is the exact redacted internal form, unwrapped to the causing exception's simple name");
        assertFalse(result.stderr().contains("\tat "), "no stack frames may reach stderr");
        assertFalse(
                result.stderr().contains("picocli") || result.stderr().contains("bravesearch.adapter"),
                "no internal class names beyond the exception's own simple name: " + result.describe());
        assertFalse(result.stderr().contains("Crashing"), "the crashing class stays unnamed: " + result.describe());
        assertEquals("", result.stdout(), "a crashed run writes no stdout payload: " + result.describe());
    }

    @Test
    void withTheDebugVariableSetAnInternalErrorAlsoPrintsATraceOfFramesAndTypesButNeverAMessage() {
        RunResult result = runGuarded(
                () -> new Main(
                        new ExchangeRegistry(),
                        configComposition(),
                        Compositions.process(name -> null, new ExchangeRegistry())
                                .withWebSearch(webComposition())
                                .withContext(contextComposition(new CrashingPresenter())),
                        name -> "1"),
                "context",
                "q");

        assertEquals(70, result.exitCode(), "the debug opt-in changes the report, never the status: " + result.describe());
        List<String> lines = result.stderr().lines().toList();
        assertEquals(
                "brave-search: unexpected internal error (IllegalStateException)",
                lines.getFirst(),
                "the redacted line still comes first");
        assertTrue(lines.size() > 3, "the trace follows the redacted line: " + result.describe());
        assertTrue(
                lines.stream().anyMatch(line -> line.stripLeading().startsWith("at io.amscotti.bravesearch.")),
                "the trace names the frames where the failure happened: " + result.describe());
        assertFalse(
                result.stderr().contains("presenter crashed mid-render"),
                "an exception message can quote user input or credential material, so no trace carries it: "
                        + result.describe());
        assertEquals("", result.stdout(), "a traced crash still writes no stdout payload");
    }

    @Test
    void theDebugTraceFollowsTheCauseChainAndStillPrintsNoMessage() {
        RunResult result = runGuarded(
                () -> new Main(
                        new ExchangeRegistry(),
                        configComposition(),
                        Compositions.process(name -> null, new ExchangeRegistry())
                                .withWebSearch(webComposition())
                                .withContext(contextComposition(new ChainedCrashPresenter())),
                        name -> "1"),
                "context",
                "q");

        List<String> lines = result.stderr().lines().toList();
        assertEquals("brave-search: unexpected internal error (IllegalStateException)", lines.getFirst());
        assertTrue(
                lines.contains("brave-search: debug trace, exception messages withheld: IllegalStateException"),
                "the trace opens with the outer type: " + result.describe());
        assertTrue(
                lines.contains("brave-search: caused by: IllegalArgumentException"),
                "and names every cause by type: " + result.describe());
        assertTrue(
                lines.stream().filter(line -> line.stripLeading().startsWith("at ")).count() >= 2,
                "with frames for the failure and its cause: " + result.describe());
        assertFalse(result.stderr().contains("outer message sentinel"), "no outer message: " + result.describe());
        assertFalse(result.stderr().contains("inner message sentinel"), "no inner message either: " + result.describe());
        assertEquals(70, result.exitCode(), "the opt-in never changes the status");
    }

    @Test
    void aLocalConfigurationEscapeStillKeepsItsOwnStatusAndOneLine() {
        RunResult result = runMain(contextComposition(new CrashingPresenter()), "context", "--base-url", "http://127.0.0.1:1", "q");

        // without a loopback test key the run never reaches the crashing presenter: the
        // local-configuration status stays three, proving the catch-all did not swallow it
        assertEquals(3, result.exitCode(), "the documented configuration status survives the rider: " + result.describe());
        assertEquals(1, result.stderr().lines().count(), "still exactly one diagnostic line: " + result.describe());
    }

    @Test
    void anEscapedLocalConfigurationFailureKeepsTheConfigurationStatus() {
        RunResult result = runMain(
                contextComposition(new MisconfiguredPresenter(), name -> "loopback-test-token"),
                "context",
                "--base-url",
                "http://127.0.0.1:1",
                "q");

        assertEquals(
                3,
                result.exitCode(),
                "a configuration failure escaping the command layer keeps its own status: " + result.describe());
        List<String> lines = result.stderr().lines().toList();
        assertEquals(1, lines.size(), "exactly one stderr line: " + result.describe());
        assertEquals(
                "context: local configuration error: the credential-file location is set but relative",
                lines.getFirst(),
                "the line carries the failing subcommand's prefix and the redacted message");
        assertFalse(result.stderr().contains("\tat "), "no stack frames may reach stderr");
        assertFalse(
                result.stderr().contains("picocli") || result.stderr().contains("bravesearch.adapter"),
                "no internal class names: " + result.describe());
        assertEquals("", result.stdout(), "no stdout payload may escape: " + result.describe());
    }

    @Test
    void anEscapedLocalConfigurationFailureRoutesThroughTheCommandLinesErrorWriterWithItsSubcommandPrefix() {
        PlacesEnrichmentComposition placesProcess =
                PlacesEnrichmentComposition.process(name -> "loopback-test-token");
        PlacesEnrichmentComposition misconfiguredPlaces = new PlacesEnrichmentComposition(
                placesProcess.loopbackTestToken(),
                invocation -> {
                    throw LocalConfigException.misconfiguredPath("the proxy address names no host");
                },
                placesProcess.pagination(),
                placesProcess.detailsPresenter(),
                placesProcess.describePresenter());
        Main main = new Main(
                new ExchangeRegistry(),
                configComposition(),
                Compositions.process(name -> null, new ExchangeRegistry())
                        .withContext(contextComposition(new MisconfiguredPresenter(), name -> "loopback-test-token"))
                        .withPlacesEnrichment(misconfiguredPlaces),
                name -> null);

        PrintStream originalErr = System.err;
        ByteArrayOutputStream processStderr = new ByteArrayOutputStream();
        System.setErr(new PrintStream(processStderr, true, UTF_8));
        try {
            ByteArrayOutputStream customOut = new ByteArrayOutputStream();
            ByteArrayOutputStream customErr = new ByteArrayOutputStream();
            CommandLine assembled = main.assemble(new PrintWriter(new OutputStreamWriter(customOut, UTF_8), true));
            assembled.setErr(new PrintWriter(new OutputStreamWriter(customErr, UTF_8), true));

            int exit = assembled.execute("places", "details", "--base-url", "http://127.0.0.1:1", "poi-1");

            assertEquals(3, exit);
            assertEquals(
                    "places.details: local configuration error: the proxy address names no host\n",
                    customErr.toString(UTF_8),
                    "nested subcommand failures route to the command line's error writer with a dotted prefix");
            assertEquals("", processStderr.toString(UTF_8), "System.err stays untouched when err is redirected");
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    void aCompositionDefectEndsAsTheInternalStatusWithOneRedactedLine() {
        RunResult result = runGuarded(
                () -> {
                    throw new IllegalStateException("the wiring broke before the command line existed");
                },
                "context",
                "q");

        assertEquals(70, result.exitCode(), "the internal status owns a composition defect: " + result.describe());
        List<String> lines = result.stderr().lines().toList();
        assertEquals(1, lines.size(), "exactly one stderr line: " + result.describe());
        assertEquals(
                "brave-search: unexpected internal error (IllegalStateException)",
                lines.getFirst(),
                "the line is the exact redacted internal form, not a raw stack trace");
        assertFalse(result.stderr().contains("\tat "), "no stack frames may reach stderr");
        assertEquals("", result.stdout(), "no stdout payload may escape: " + result.describe());
    }

    @Test
    void aCompositionDefectHonorsTheDebugOptInAndSuppressesItWhenDisabled() {
        Supplier<Main> broken = () -> {
            throw new IllegalStateException("construction secret sentinel");
        };

        RunResult traced = runGuarded(broken, name -> "1", "context", "q");
        assertEquals(70, traced.exitCode(), traced.describe());
        List<String> tracedLines = traced.stderr().lines().toList();
        assertEquals("brave-search: unexpected internal error (IllegalStateException)", tracedLines.getFirst());
        assertTrue(
                tracedLines.contains(
                        "brave-search: debug trace, exception messages withheld: IllegalStateException"),
                traced.describe());
        assertTrue(
                tracedLines.stream().anyMatch(line -> line.stripLeading().startsWith("at io.amscotti.bravesearch.")),
                traced.describe());
        assertFalse(traced.stderr().contains("construction secret sentinel"), traced.describe());

        for (String disabled : List.of("0", "false")) {
            RunResult quiet = runGuarded(broken, name -> disabled, "context", "q");
            assertEquals(70, quiet.exitCode(), quiet.describe());
            assertEquals(
                    List.of("brave-search: unexpected internal error (IllegalStateException)"),
                    quiet.stderr().lines().toList(),
                    () -> "BRAVE_SEARCH_DEBUG=" + disabled + " must suppress the trace: " + quiet.describe());
        }
    }

    @Test
    void theDebugTraceWalksSuppressedExceptionsHandlesCauseCyclesAndNamesAnonymousThrowables() {
        RunResult redacted = runMain(contextComposition(new ComplexCrashPresenter()), "context", "q");
        assertEquals(70, redacted.exitCode(), redacted.describe());
        assertEquals(
                List.of("brave-search: unexpected internal error (IllegalStateException)"),
                redacted.stderr().lines().toList(),
                "an anonymous throwable subclass names its superclass instead of empty parentheses or a package name");

        RunResult traced = runGuarded(
                () -> new Main(
                        new ExchangeRegistry(),
                        configComposition(),
                        Compositions.process(name -> null, new ExchangeRegistry())
                                .withWebSearch(webComposition())
                                .withContext(contextComposition(new ComplexCrashPresenter())),
                        name -> "1"),
                "context",
                "q");

        assertEquals(70, traced.exitCode(), traced.describe());
        List<String> lines = traced.stderr().lines().toList();
        assertEquals("brave-search: unexpected internal error (IllegalStateException)", lines.getFirst());
        assertTrue(
                lines.contains("brave-search: debug trace, exception messages withheld: IllegalStateException"),
                traced.describe());
        assertTrue(lines.contains("brave-search: suppressed: IOException"), traced.describe());
        assertTrue(lines.contains("brave-search: caused by: IllegalArgumentException"), traced.describe());
        assertTrue(
                lines.contains("brave-search: caused by (circular reference): IllegalStateException"),
                "cyclic causes terminate cleanly without looping: " + traced.describe());
        assertFalse(traced.stderr().contains("anonymous outer secret"), traced.describe());
        assertFalse(traced.stderr().contains("cycle inner secret"), traced.describe());
        assertFalse(traced.stderr().contains("suppressed secret"), traced.describe());
    }

    private static ContextComposition contextComposition(ContextPresenter presenter) {
        return contextComposition(presenter, name -> null);
    }

    private static ContextComposition contextComposition(ContextPresenter presenter, Function<String, String> environmentLookup) {
        ContextComposition process = ContextComposition.process(environmentLookup);
        return new ContextComposition(process.loopbackTestToken(), process.exchange(), presenter);
    }

    private static CredentialProvider storedCredentials() {
        return new CredentialProvider() {
            @Override
            public Credential resolve() {
                return Credential.of("stored-production-token".getBytes(UTF_8));
            }

            @Override
            public ResolvedCredential resolveWithProvenance() {
                return new ResolvedCredential(resolve(), "environment BRAVE_API_KEY", false);
            }
        };
    }

    private RunResult runMain(ContextComposition context, String... args) {
        return runGuarded(
                () -> new Main(
                        new ExchangeRegistry(),
                        configComposition(),
                        Compositions.process(name -> null, new ExchangeRegistry())
                                .withWebSearch(webComposition())
                                .withContext(context),
                        name -> null),
                args);
    }

    private ConfigComposition configComposition() {
        return new ConfigComposition(
                storedCredentials(),
                unusedStore(),
                fromStdin -> {
                    throw new IllegalStateException("no secret input is read by these runs");
                },
                () -> home.resolve("config.json"));
    }

    /** Runs one composition under the guarded process entry with both streams captured. */
    private RunResult runGuarded(Supplier<Main> composition, String... args) {
        return runGuarded(composition, name -> null, args);
    }

    /** Runs one composition under the guarded process entry over an injected environment lookup. */
    private RunResult runGuarded(
            Supplier<Main> composition, Function<String, String> environmentLookup, String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        System.setOut(new PrintStream(stdout, true, UTF_8));
        System.setErr(new PrintStream(stderr, true, UTF_8));
        try {
            int exitCode = Main.runGuarded(composition, environmentLookup, args);
            return new RunResult(exitCode, stdout.toString(UTF_8), stderr.toString(UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private static WebSearchComposition webComposition() {
        WebSearchComposition process = WebSearchComposition.process(name -> null);
        return new WebSearchComposition(
                process.loopbackTestToken(), process.exchange(), unusedWebPresenter(), process.pagination(), process.pagedPresenter());
    }

    private static io.amscotti.bravesearch.adapter.cli.presentation.WebSearchPresenter unusedWebPresenter() {
        return new io.amscotti.bravesearch.adapter.cli.presentation.WebSearchPresenter() {
            @Override
            public int present(
                    Outcome<io.amscotti.bravesearch.domain.result.WebSearchResult> outcome,
                    io.amscotti.bravesearch.domain.request.WebSearchRequest request,
                    OutputRequest output,
                    ResultWriter results,
                    DiagnosticsSink diagnostics) {
                throw new IllegalStateException("the web command is not exercised by these runs");
            }
        };
    }

    /** A presenter that crashes with a chained cause, each link carrying a message of its own. */
    private static final class ChainedCrashPresenter implements ContextPresenter {

        @Override
        public int present(
                Outcome<ContextResult> outcome,
                ContextRequest request,
                OutputRequest output,
                ResultWriter results,
                DiagnosticsSink diagnostics) {
            throw new IllegalStateException(
                    "outer message sentinel", new IllegalArgumentException("inner message sentinel"));
        }
    }

    /** The crashing injected presenter: the runtime failure a composition defect produces mid-render. */
    private static final class CrashingPresenter implements ContextPresenter {
        @Override
        public int present(
                Outcome<ContextResult> outcome,
                ContextRequest request,
                OutputRequest output,
                ResultWriter results,
                DiagnosticsSink diagnostics) {
            throw new IllegalStateException("presenter crashed mid-render");
        }
    }

    /** The misconfigured presenter: a local-configuration failure escaping the command layer mid-render. */
    private static final class MisconfiguredPresenter implements ContextPresenter {
        @Override
        public int present(
                Outcome<ContextResult> outcome,
                ContextRequest request,
                OutputRequest output,
                ResultWriter results,
                DiagnosticsSink diagnostics) {
            throw LocalConfigException.misconfiguredPath("the credential-file location is set but relative");
        }
    }

    /** Throws an anonymous throwable carrying a suppressed exception and a cyclic cause chain. */
    private static final class ComplexCrashPresenter implements ContextPresenter {
        @Override
        public int present(
                Outcome<ContextResult> outcome,
                ContextRequest request,
                OutputRequest output,
                ResultWriter results,
                DiagnosticsSink diagnostics) {
            IllegalArgumentException inner = new IllegalArgumentException("cycle inner secret") {};
            IllegalStateException outer = new IllegalStateException("anonymous outer secret") {
                @Override
                public Throwable getCause() {
                    return inner;
                }
            };
            inner.initCause(outer);
            outer.addSuppressed(new IOException("suppressed secret"));
            throw outer;
        }
    }

    private static ConfigStore unusedStore() {
        return new ConfigStore() {
            @Override
            public io.amscotti.bravesearch.domain.config.ConfigState load() {
                throw new IllegalStateException("the store is not read by these runs");
            }

            @Override
            public io.amscotti.bravesearch.domain.config.ConfigFileSight peekSummary() {
                throw new IllegalStateException("the store is not read by these runs");
            }

            @Override
            public void store(Credential credential) {
                throw new IllegalStateException("the store is not read by these runs");
            }

            @Override
            public void clear() {
                throw new IllegalStateException("the store is not read by these runs");
            }

            @Override
            public io.amscotti.bravesearch.domain.config.PermissionRepair repairPermissions() {
                throw new IllegalStateException("the store is not read by these runs");
            }

        };
    }

    private record RunResult(int exitCode, String stdout, String stderr) {
        String describe() {
            return "exitCode=" + exitCode + ", stdout=<" + stdout + ">, stderr=<" + stderr + ">";
        }
    }
}

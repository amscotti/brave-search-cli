package io.amscotti.bravesearch.bootstrap;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
                "brave-search: local configuration error: the credential-file location is set but relative",
                lines.getFirst(),
                "the line is the failure's own redacted message");
        assertFalse(result.stderr().contains("\tat "), "no stack frames may reach stderr");
        assertFalse(
                result.stderr().contains("picocli") || result.stderr().contains("bravesearch.adapter"),
                "no internal class names: " + result.describe());
        assertEquals("", result.stdout(), "no stdout payload may escape: " + result.describe());
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

    private static ContextComposition contextComposition(ContextPresenter presenter) {
        return contextComposition(presenter, name -> null);
    }

    private static ContextComposition contextComposition(ContextPresenter presenter, Function<String, String> environmentLookup) {
        ContextComposition process = ContextComposition.process(storedCredentials(), environmentLookup);
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
        return runGuarded(() -> new Main(new ExchangeRegistry(), configComposition(), webComposition(), context), args);
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
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        System.setOut(new PrintStream(stdout, true, UTF_8));
        System.setErr(new PrintStream(stderr, true, UTF_8));
        try {
            int exitCode = Main.runGuarded(composition, args);
            return new RunResult(exitCode, stdout.toString(UTF_8), stderr.toString(UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private static WebSearchComposition webComposition() {
        WebSearchComposition process = WebSearchComposition.process(storedCredentials());
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

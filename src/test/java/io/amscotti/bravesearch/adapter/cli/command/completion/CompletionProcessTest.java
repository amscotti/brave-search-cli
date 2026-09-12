package io.amscotti.bravesearch.adapter.cli.command.completion;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.testsupport.ProcessHarness;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Whole-process evidence for the completion command against the JVM executable produced by
 * {@code installDist}: the documented exit statuses of the shell-argument grammar,
 * byte-stable generation across separate processes, syntax validation of the printed
 * scripts by real bash and zsh parsers, byte-identity with the packaged script copies, and
 * the credential-freedom of a script generated under sentinel-bearing credential variables
 * — the script may name options, never values.
 */
final class CompletionProcessTest {

    private static final Set<String> SCRUBBED =
            Set.of("BRAVE_API_KEY", "BRAVE_SEARCH_API_KEY", "BRAVE_SEARCH_TEST_KEY", "XDG_CONFIG_HOME");

    @Test
    @Timeout(120)
    void theShellGrammarKeepsTheUsageExitInWholeProcesses() throws Exception {
        assertEquals(2, run("completion").exitStatus(), "a bare invocation is a usage error");
        assertEquals(2, run("completion", "fish").exitStatus(), "an unsupported shell is a usage error");
        assertEquals(2, run("completion", "bash", "zsh").exitStatus(), "a second positional is a usage error");
    }

    @Test
    @Timeout(120)
    void separateProcessesPrintByteIdenticalScripts() throws Exception {
        byte[] first = run("completion", "bash").stdout();
        byte[] second = run("completion", "bash").stdout();

        assertArrayEquals(first, second, "two whole processes generate identical bash scripts");
        assertArrayEquals(
                run("completion", "zsh").stdout(), run("completion", "zsh").stdout(), "and identical zsh scripts");
    }

    @Test
    @Timeout(120)
    void thePrintedScriptsPassRealShellSyntaxChecks() throws Exception {
        Path bash = scratch("bash", run("completion", "bash").stdout());
        Path zsh = scratch("zsh", run("completion", "zsh").stdout());

        assertTrue(
                new ProcessHarness()
                                .launch(List.of("bash", "-n", bash.toString()), Map.of(), new byte[0])
                                .exitStatus()
                        == 0,
                "bash accepts the printed bash script");
        if (zshAvailable()) {
            assertTrue(
                    new ProcessHarness()
                                    .launch(List.of("zsh", "-n", zsh.toString()), Map.of(), new byte[0])
                                    .exitStatus()
                            == 0,
                    "zsh accepts the printed zsh script");
        }
    }

    @Test
    @Timeout(120)
    void thePackagedScriptCopiesAreByteIdenticalToWholeProcessOutput() throws Exception {
        assertArrayEquals(run("completion", "bash").stdout(), packagedScript("completions/brave-search.bash"));
        assertArrayEquals(run("completion", "zsh").stdout(), packagedScript("completions/brave-search.zsh"));
    }

    @Test
    @Timeout(120)
    void generationUnderSentinelCredentialsPrintsNoValueMaterial() throws Exception {
        String ambientKey = freshToken();
        String aliasKey = freshToken();
        ProcessHarness.ProcessResult result = new ProcessHarness().launch(
                launcherWith("completion", "bash"),
                Map.of("BRAVE_API_KEY", ambientKey, "BRAVE_SEARCH_API_KEY", aliasKey),
                new byte[0],
                SCRUBBED);

        assertEquals(0, result.exitStatus());
        String script = new String(result.stdout(), UTF_8);
        assertFalse(script.contains(ambientKey), "the ambient credential never reaches the script");
        assertFalse(script.contains(aliasKey), "the alias credential never reaches the script");
        assertTrue(script.contains("--output"), "option names are the script's own grammar content");
    }

    /** A unique random-looking token, alphanumeric only, so byte-level absence is absence at any depth. */
    private static String freshToken() {
        return "BSK" + Long.toUnsignedString(java.util.UUID.randomUUID().getMostSignificantBits(), 36)
                + Long.toUnsignedString(java.util.UUID.randomUUID().getLeastSignificantBits(), 36);
    }

    private static boolean zshAvailable() throws IOException {
        return new ProcessHarness()
                        .launch(List.of("zsh", "-c", "true"), Map.of(), new byte[0])
                        .exitStatus()
                == 0;
    }

    private static Path scratch(String shell, byte[] script) throws IOException {
        Path file = Files.createTempFile("brave-completion-", "." + shell);
        Files.write(file, script);
        file.toFile().deleteOnExit();
        return file;
    }

    private static byte[] packagedScript(String resource) throws IOException {
        try (InputStream script = CompletionProcessTest.class.getResourceAsStream("/" + resource)) {
            assertNotNull(script, () -> "packaged completion script missing: " + resource);
            return script.readAllBytes();
        }
    }

    private static ProcessHarness.ProcessResult run(String... arguments) throws IOException {
        return new ProcessHarness().launch(launcherWith(arguments), Map.of(), new byte[0], SCRUBBED);
    }

    private static List<String> launcherWith(String... arguments) {
        String launcherProperty = System.getProperty("brave.search.jvm.launcher");
        assertNotNull(launcherProperty, "brave.search.jvm.launcher must be injected by the build");
        Path launcher = Path.of(launcherProperty);
        assertTrue(Files.isRegularFile(launcher), () -> "installed launcher missing: " + launcher);
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(launcher.toString()), java.util.stream.Stream.of(arguments))
                .toList();
    }
}

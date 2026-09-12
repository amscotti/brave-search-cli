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
 * The same completion evidence as the JVM process suite, run against the exact native
 * executable produced by {@code nativeCompile}: the shell-argument grammar's exit statuses,
 * byte-identical generation matching the packaged copies, real shell syntax checks, the
 * credential-freedom of a script generated under sentinel-bearing credential variables — the
 * script may name options, never values — and the plain fixed human contract of a local
 * command.
 */
final class CompletionNativeSmokeTest {

    private static final Set<String> SCRUBBED =
            Set.of("BRAVE_API_KEY", "BRAVE_SEARCH_API_KEY", "BRAVE_SEARCH_TEST_KEY", "XDG_CONFIG_HOME");

    @Test
    @Timeout(120)
    void theShellGrammarKeepsTheUsageExitInTheNativeExecutable() throws Exception {
        assertEquals(2, run("completion").exitStatus(), "a bare invocation is a usage error");
        assertEquals(2, run("completion", "fish").exitStatus(), "an unsupported shell is a usage error");
        assertEquals(2, run("completion", "bash", "zsh").exitStatus(), "a second positional is a usage error");
    }

    @Test
    @Timeout(120)
    void theNativeBashScriptMatchesThePackagedCopyAndPassesBashSyntax() throws Exception {
        byte[] script = run("completion", "bash").stdout();

        assertArrayEquals(packagedScript("completions/brave-search.bash"), script);
        Path file = Files.createTempFile("brave-completion-native-", ".bash");
        file.toFile().deleteOnExit();
        Files.write(file, script);
        assertEquals(
                0,
                new ProcessHarness().launch(List.of("bash", "-n", file.toString()), Map.of(), new byte[0]).exitStatus(),
                "bash accepts the native-generated script");
    }

    @Test
    @Timeout(120)
    void theNativeZshScriptMatchesThePackagedCopyAndPassesZshSyntax() throws Exception {
        byte[] script = run("completion", "zsh").stdout();

        assertArrayEquals(packagedScript("completions/brave-search.zsh"), script);
        if (zshAvailable()) {
            Path file = Files.createTempFile("brave-completion-native-", ".zsh");
            file.toFile().deleteOnExit();
            Files.write(file, script);
            assertEquals(
                    0,
                    new ProcessHarness().launch(List.of("zsh", "-n", file.toString()), Map.of(), new byte[0]).exitStatus(),
                    "zsh accepts the native-generated script");
        }
    }

    @Test
    @Timeout(120)
    void twoNativeInvocationsPrintByteIdenticalScripts() throws Exception {
        assertArrayEquals(run("completion", "bash").stdout(), run("completion", "bash").stdout());
        assertTrue(
                new String(run("completion", "zsh").stdout(), UTF_8).startsWith("#compdef brave-search"),
                "the native zsh form registers through compdef");
    }

    @Test
    @Timeout(120)
    void generationUnderSentinelCredentialsPrintsNoValueMaterial() throws Exception {
        String ambientKey = freshToken();
        String aliasKey = freshToken();
        ProcessHarness.ProcessResult result =
                new ProcessHarness().launch(
                        command("completion", "bash"),
                        Map.of("BRAVE_API_KEY", ambientKey, "BRAVE_SEARCH_API_KEY", aliasKey),
                        new byte[0],
                        SCRUBBED);

        assertEquals(0, result.exitStatus());
        String script = new String(result.stdout(), UTF_8);
        assertFalse(script.contains(ambientKey), "the ambient credential never reaches the script");
        assertFalse(script.contains(aliasKey), "the alias credential never reaches the script");
        assertTrue(script.contains("--output"), "option names are the script's own grammar content");
    }

    /**
     * A unique random-looking token, alphanumeric only, so byte-level absence is absence at
     * any depth.
     */
    private static String freshToken() {
        return "BSK" + Long.toUnsignedString(java.util.UUID.randomUUID().getMostSignificantBits(), 36)
                + Long.toUnsignedString(java.util.UUID.randomUUID().getLeastSignificantBits(), 36);
    }

    /**
     * Whether a zsh on the path runs at all: an absent zsh is the skip the syntax-check guard
     * exists for, not a harness failure, so the launch refusal decodes to false the same way
     * {@code PtyHarness.probePython} decodes a missing python.
     */
    private static boolean zshAvailable() {
        try {
            return new ProcessHarness()
                            .launch(List.of("zsh", "-c", "true"), Map.of(), new byte[0])
                            .exitStatus()
                    == 0;
        } catch (IOException unavailable) {
            return false;
        }
    }

    private static byte[] packagedScript(String resource) throws IOException {
        try (InputStream script = CompletionNativeSmokeTest.class.getResourceAsStream("/" + resource)) {
            assertNotNull(script, () -> "packaged completion script missing: " + resource);
            return script.readAllBytes();
        }
    }

    private static ProcessHarness.ProcessResult run(String... arguments) throws IOException {
        return new ProcessHarness().launch(command(arguments), Map.of(), new byte[0], SCRUBBED);
    }

    /** The native executable plus the given command tokens. */
    private static List<String> command(String... arguments) {
        String binaryProperty = System.getProperty("brave.search.native.binary");
        assertNotNull(binaryProperty, "brave.search.native.binary must be injected by the build");
        Path binary = Path.of(binaryProperty);
        assertTrue(Files.isRegularFile(binary), () -> "native binary missing: " + binary);
        assertTrue(Files.isExecutable(binary), () -> "native binary not executable: " + binary);
        return java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(binary.toString()), java.util.stream.Stream.of(arguments))
                .toList();
    }
}

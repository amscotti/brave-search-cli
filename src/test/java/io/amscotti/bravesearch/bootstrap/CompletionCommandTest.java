package io.amscotti.bravesearch.bootstrap;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.presentation.OutputStreamResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * The completion command over the fully registered command line: exactly one shell argument
 * from the supported set, deterministic LF-only scripts generated from the live grammar,
 * hidden options offered nowhere, machine-output flags rejected before any output, the
 * result channel's write-failure verdicts (a broken pipe silent zero, any other failure the
 * transport status with one line), and packaged script copies byte-identical to what the
 * command prints.
 */
final class CompletionCommandTest {

    @Test
    void aBareInvocationIsAUsageError() {
        Run run = runCompletion();

        assertEquals(2, run.exit(), () -> run.describe());
        assertEquals("", run.stdout(), () -> run.describe());
    }

    @Test
    void anUnsupportedShellIsAUsageErrorNamingTheAcceptedSet() {
        Run run = runCompletion("fish");

        assertEquals(2, run.exit(), () -> run.describe());
        assertEquals("", run.stdout(), () -> run.describe());
        assertTrue(run.stderr().contains("bash"), () -> "the rejection names bash: " + run.describe());
        assertTrue(run.stderr().contains("zsh"), () -> "the rejection names zsh: " + run.describe());
    }

    @Test
    void aSecondPositionalIsAUsageError() {
        Run run = runCompletion("bash", "zsh");

        assertEquals(2, run.exit(), () -> run.describe());
        assertEquals("", run.stdout(), () -> run.describe());
    }

    @Test
    void machineOutputFlagsAreRejectedBeforeAnyScriptIsPrinted() {
        Run run = runCompletion("--output", "json", "bash");

        assertEquals(2, run.exit(), () -> run.describe());
        assertEquals("", run.stdout(), () -> run.describe());
    }

    @Test
    void theBashScriptIsLfOnlyTextGeneratedFromTheLiveGrammar() {
        Run run = runCompletion("bash");

        assertEquals(0, run.exit(), () -> run.describe());
        assertTrue(run.stdout().startsWith("#!/usr/bin/env bash\n"), () -> run.describe());
        assertTrue(run.stdout().contains("_complete_brave-search"), "the entry function names the command");
        assertTrue(run.stdout().contains("--no-color"), "a global option name is offered");
        assertTrue(run.stdout().contains("web"), "a subcommand name is offered");
        assertFalse(run.stdout().contains("\r"), "the script is LF-only");
        assertFalse(
                run.stdout().endsWith("\n\n"), "the script ends in exactly one line feed: " + run.describe());
        assertTrue(run.stdout().endsWith("\n"), "the script ends in one line feed");
    }

    @Test
    void theZshScriptRegistersThroughCompdefAndDiffersFromBash() {
        Run bash = runCompletion("bash");
        Run zsh = runCompletion("zsh");

        assertEquals(0, zsh.exit(), () -> zsh.describe());
        assertTrue(zsh.stdout().startsWith("#compdef brave-search\n"), () -> zsh.describe());
        assertFalse(zsh.stdout().startsWith("#!"), "the zsh form carries no bash shebang");
        assertNotEquals(bash.stdout(), zsh.stdout(), "the two shells print their own scripts");
        assertTrue(zsh.stdout().endsWith("\n"), "the script ends in one line feed");
        assertFalse(zsh.stdout().endsWith("\n\n"), "exactly one trailing line feed");
    }

    @Test
    void theHiddenBaseUrlOverrideIsOfferedByNoShellScript() {
        assertFalse(runCompletion("bash").stdout().contains("--base-url"), "hidden stays hidden in bash");
        assertFalse(runCompletion("zsh").stdout().contains("--base-url"), "hidden stays hidden in zsh");
    }

    @Test
    void twoInvocationsOverFreshlyAssembledGrammarsPrintByteIdenticalScripts() {
        String first = runCompletion("bash").stdout();
        String second = runCompletion("bash").stdout();

        assertEquals(first, second, "generation from the model is deterministic");
    }

    @Test
    void aFailedStdoutWriteExitsSixWithExactlyOneStderrLine() {
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        CommandLine collected = new Main().assemble(
                helpWriter(), new ResultWriter() {
                    @Override
                    public void write(byte[] document) {
                        throw new UncheckedIOException(new IOException("stdout is gone"));
                    }
                });
        collected.setErr(new PrintWriter(new OutputStreamWriter(stderr, StandardCharsets.UTF_8), true));

        int exit = collected.execute("completion", "bash");

        assertEquals(6, exit, "a failed stdout write keeps the transport status");
        List<String> diagnostics = stderr.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals(1, diagnostics.size(), () -> "exactly one diagnostic line: <" + stderr + ">");
        assertFalse(diagnostics.getFirst().contains("Exception"), "no stack trace may reach stderr");
    }

    @Test
    void aDownstreamBrokenPipeIsSilentZero() {
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        CommandLine collected = new Main().assemble(
                helpWriter(), new ResultWriter() {
                    @Override
                    public void write(byte[] document) {
                        throw new UncheckedIOException(new IOException("Broken pipe"));
                    }
                });
        collected.setErr(new PrintWriter(new OutputStreamWriter(stderr, StandardCharsets.UTF_8), true));

        int exit = collected.execute("completion", "bash");

        assertEquals(0, exit, "a downstream broken pipe is silent success");
        assertEquals("", stderr.toString(StandardCharsets.UTF_8), "a broken pipe stays silent on stderr");
    }

    @Test
    void thePackagedScriptCopiesAreByteIdenticalToTheCommandOutput() throws IOException {
        assertEquals(
                runCompletion("bash").stdout(), packagedScript("completions/brave-search.bash"), "bash copy");
        assertEquals(runCompletion("zsh").stdout(), packagedScript("completions/brave-search.zsh"), "zsh copy");
    }

    private static String packagedScript(String resource) throws IOException {
        try (InputStream script = CompletionCommandTest.class.getResourceAsStream("/" + resource)) {
            if (script == null) {
                throw new IOException("packaged completion script missing from the classpath: " + resource);
            }
            return new String(script.readAllBytes(), UTF_8);
        }
    }

    private static Run runCompletion(String... arguments) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintWriter stdoutWriter = new PrintWriter(new OutputStreamWriter(stdout, UTF_8), true);
        CommandLine collected = new Main().assemble(stdoutWriter, new OutputStreamResultWriter(stdout));
        collected.setErr(new PrintWriter(new OutputStreamWriter(stderr, UTF_8), true));
        int exit = collected.execute(arguments.length == 0
                ? new String[] {"completion"}
                : join("completion", arguments));
        return new Run(exit, stdout.toString(UTF_8), stderr.toString(UTF_8));
    }

    private static String[] join(String head, String... tail) {
        String[] joined = new String[tail.length + 1];
        joined[0] = head;
        System.arraycopy(tail, 0, joined, 1, tail.length);
        return joined;
    }

    /** A help-channel writer over nothing: the script travels through the result channel. */
    private static PrintWriter helpWriter() {
        return new PrintWriter(new java.io.StringWriter());
    }

    private record Run(int exit, String stdout, String stderr) {

        String describe() {
            return "exit=" + exit + ", stdout=<" + stdout + ">, stderr=<" + stderr + ">";
        }
    }
}

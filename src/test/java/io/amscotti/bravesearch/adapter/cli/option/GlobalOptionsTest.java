package io.amscotti.bravesearch.adapter.cli.option;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.presentation.TerminalDetector;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Placement and exclusivity contracts of the all-command globals: the flags parse before and
 * after the subcommand token into one shared instance, the verbosity pair is mutually
 * exclusive in both orders, and a repeated flag is a usage error.
 */
final class GlobalOptionsTest {

    @Test
    void globalsParseBeforeTheSubcommandTokenIntoTheSharedInstance() {
        Shared shared = new Shared();
        RunResult result = run(shared, "--verbose", "--no-color", "sub");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertTrue(shared.globals.verbose(), "--verbose parsed before the command token");
        assertTrue(shared.globals.noColor(), "--no-color parsed before the command token");
    }

    @Test
    void globalsParseAfterTheSubcommandTokenIntoTheSharedInstance() {
        Shared shared = new Shared();
        RunResult result = run(shared, "sub", "--verbose", "--no-color");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertTrue(shared.globals.verbose(), "--verbose parsed after the command token");
        assertTrue(shared.globals.noColor(), "--no-color parsed after the command token");
    }

    @Test
    void verboseAndQuietAreMutuallyExclusiveInBothOrders() {
        assertEquals(2, run(new Shared(), "--verbose", "--quiet", "sub").exitCode());
        assertEquals(2, run(new Shared(), "--quiet", "--verbose", "sub").exitCode());
        assertEquals(2, run(new Shared(), "sub", "--quiet", "--verbose").exitCode());
    }

    @Test
    void aRepeatedGlobalFlagIsAUsageError() {
        RunResult repeated = run(new Shared(), "--verbose", "--verbose", "sub");

        assertEquals(2, repeated.exitCode(), () -> repeated.describe());
        assertTrue(
                repeated.stderr().contains("only once"),
                () -> "the rejection names the repeated flag: " + repeated.describe());
    }

    @Test
    void theRenderWidthComesFromTheTerminalDetectorAndDefaultsToOneHundred() {
        assertEquals(72, new GlobalOptions(new TerminalDetector(() -> true, () -> 72)).outputWidth());
        assertEquals(100, new GlobalOptions(new TerminalDetector(() -> true)).outputWidth());
        assertEquals(100, new GlobalOptions().outputWidth(), "the hermetic shape keeps the default width");
    }

    @Test
    void theShortOutputAliasBelongsToTheRemoteMixinOnly() {
        RunResult result = run(new Shared(), "-o", "json", "sub");

        assertEquals(2, result.exitCode(), "the root grammar holds no output option");
    }

    @Test
    void colorNeedsBothATerminalAndTheAbsenceOfTheNoColorFlag() {
        assertTrue(
                new GlobalOptions(new TerminalDetector(() -> true)).colorEnabled(),
                "a detected terminal enables color");
        assertFalse(
                new GlobalOptions(new TerminalDetector(() -> false)).colorEnabled(),
                "an undetected terminal keeps color off");
        GlobalOptions terminalWithFlag = new GlobalOptions(new TerminalDetector(() -> true));
        RunResult flagged = run(new Shared(terminalWithFlag), "--no-color", "sub");
        assertEquals(0, flagged.exitCode(), () -> flagged.describe());
        assertFalse(terminalWithFlag.colorEnabled(), "--no-color overrides a detected terminal");
    }

    @Test
    void colorStaysOffWhenNoDetectorWasInjected() {
        assertFalse(new GlobalOptions().colorEnabled(), "an absent detector is uncertainty, and uncertainty disables");
    }

    /** Holds the one globals instance shared by the root and the assertions. */
    private static final class Shared {
        final GlobalOptions globals;

        Shared() {
            this(new GlobalOptions());
        }

        Shared(GlobalOptions globals) {
            this.globals = globals;
        }
    }

    @Command(name = "brave-search")
    static final class Root implements Runnable {
        @Mixin
        GlobalOptions globals;

        @Spec CommandSpec spec;

        @Override
        public void run() {
            spec.commandLine().usage(spec.commandLine().getOut());
        }
    }

    @Command(name = "sub")
    static final class Sub implements Callable<Integer> {
        @Override
        public Integer call() {
            return 0;
        }
    }

    private static RunResult run(Shared shared, String... args) {
        Root root = new Root();
        root.globals = shared.globals;
        CommandLine commandLine = new CommandLine(root);
        commandLine.addSubcommand("sub", new CommandLine(new Sub()));
        StrictOptionParsing.apply(commandLine);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        commandLine.setOut(new PrintWriter(stdout, true));
        commandLine.setErr(new PrintWriter(stderr, true));
        int exitCode = commandLine.execute(args);
        return new RunResult(exitCode, stdout.toString(UTF_8), stderr.toString(UTF_8));
    }

    private record RunResult(int exitCode, String stdout, String stderr) {
        String describe() {
            return "exitCode=" + exitCode + ", stdout=<" + stdout + ">, stderr=<" + stderr + ">";
        }
    }
}

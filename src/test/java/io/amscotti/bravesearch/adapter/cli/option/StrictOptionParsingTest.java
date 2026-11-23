package io.amscotti.bravesearch.adapter.cli.option;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * The strict parser configuration the whole command tree shares: abbreviated long options and
 * unmatched options are rejected, and a repeated single-valued option is a usage error, so a
 * script's meaning can never drift when a new option name appears — an invocation means
 * exactly what it meant when it was written.
 */
final class StrictOptionParsingTest {

    @Test
    void abbreviatedLongOptionsAreRejectedNotCompleted() {
        RunResult abbreviated = run("--time", "30s");

        assertEquals(2, abbreviated.exitCode(), () -> abbreviated.describe());
        assertTrue(
                abbreviated.stderr().contains("Unknown option"),
                () -> "the rejection names the unmatched spelling: " + abbreviated.describe());
    }

    @Test
    void unmatchedOptionsAreErrorsNeverPositionalParameters() {
        RunResult unmatched = run("--definitely-bogus");

        assertEquals(2, unmatched.exitCode(), () -> unmatched.describe());
        assertTrue(
                unmatched.stderr().contains("--definitely-bogus"),
                () -> "the rejection names the unmatched option: " + unmatched.describe());
    }

    @Test
    void anUnknownOptionIsNamedWithoutTheArgumentsItsFailedParseCouldNotPlace() {
        // the typo'd option's own value and the query text follow it on the command line;
        // neither is an option, and naming them as unknown options blames the user's query
        RunResult typo = run("--timout", "30s", "heat pump efficiency");

        assertEquals(2, typo.exitCode(), () -> typo.describe());
        assertEquals(
                "Unknown option: '--timout'",
                typo.stderr().lines().findFirst().orElse(""),
                () -> "the report names the offending option alone: " + typo.describe());
        assertTrue(
                typo.stderr().lines().skip(1).noneMatch(line -> line.contains("heat pump") || line.startsWith("Unknown")),
                () -> "the misplaced arguments are not reported as options: " + typo.describe());
        assertTrue(
                typo.stderr().contains("--timeout"),
                () -> "the suggestion of the intended option stays: " + typo.describe());
    }

    @Test
    void aRepeatedSingleValuedOptionIsAUsageError() {
        RunResult repeated = run("--budget", "30s", "--budget", "45s");

        assertEquals(2, repeated.exitCode(), () -> repeated.describe());
        assertTrue(
                repeated.stderr().contains("only once"),
                () -> "the rejection names the repeated option: " + repeated.describe());
    }

    @Command(name = "prog")
    static final class Prog implements Callable<Integer> {

        @Option(names = "--budget", description = "One budget.")
        String budget;

        @Option(names = "--timeout", description = "One timeout.")
        String timeout;

        @Override
        public Integer call() {
            return 0;
        }
    }

    private static RunResult run(String... args) {
        CommandLine commandLine = new CommandLine(new Prog());
        StrictOptionParsing.apply(commandLine);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        commandLine.setOut(new PrintWriter(stdout, true));
        commandLine.setErr(new PrintWriter(stderr, true));
        int exitCode = commandLine.execute(args);
        return new RunResult(exitCode, stdout.toString(), stderr.toString());
    }

    private record RunResult(int exitCode, String stdout, String stderr) {
        String describe() {
            return "exitCode=" + exitCode + ", stdout=<" + stdout + ">, stderr=<" + stderr + ">";
        }
    }
}

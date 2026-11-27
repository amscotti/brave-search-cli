package io.amscotti.bravesearch.adapter.cli.option;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

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

    @Test
    void aSubcommandTypoKeepsItsOwnReportAndSubcommandSuggestion() {
        CommandLine root = new CommandLine(new RootWithSubcommand());
        root.addSubcommand("web", new CommandLine(new WebSubcommand()));

        RunResult typo = runOn(root, "webs", "--count", "5", "q");

        assertEquals(2, typo.exitCode(), () -> typo.describe());
        String firstLine = typo.stderr().lines().findFirst().orElse("");
        assertTrue(
                firstLine.contains("'webs'"),
                () -> "the misspelled subcommand stays named in the error line: " + typo.describe());
        assertFalse(
                firstLine.startsWith("Unknown option:"),
                () -> "the subcommand's own option must not be mislabeled as the root's unknown option: "
                        + typo.describe());
        assertTrue(
                typo.stderr().contains("Possible solutions: web"),
                () -> "subcommand suggestions survive: " + typo.describe());
    }

    @Test
    void extraPositionalsContainingBareHyphenAreNotStrippedOrMislabeled() {
        RunResult extra = runOn(new CommandLine(new ProgWithQuery()), "query", "extra1", "-");

        assertEquals(2, extra.exitCode(), () -> extra.describe());
        String firstLine = extra.stderr().lines().findFirst().orElse("");
        assertTrue(
                firstLine.contains("'extra1'") && firstLine.contains("'-'"),
                () -> "both extra positionals stay reported: " + extra.describe());
        assertFalse(firstLine.startsWith("Unknown option:"), () -> extra.describe());
    }

    @Test
    void extraOptionLikePositionalsAfterEndOfOptionsAreNotStrippedToUnknownOptions() {
        RunResult extra = runOn(new CommandLine(new ProgWithQuery()), "--", "query", "-extra", "extra2");

        assertEquals(2, extra.exitCode(), () -> extra.describe());
        String firstLine = extra.stderr().lines().findFirst().orElse("");
        assertTrue(
                firstLine.contains("'-extra'") && firstLine.contains("'extra2'"),
                () -> "all extra positionals after '--' stay reported: " + extra.describe());
        assertFalse(firstLine.startsWith("Unknown option:"), () -> extra.describe());
    }

    @Test
    void anUnknownOptionBeforeEndOfOptionsNeverReportsOptionLikePositionalsAfterIt() {
        RunResult mixed = run("--timout", "30s", "--", "-query");

        assertEquals(2, mixed.exitCode(), () -> mixed.describe());
        assertEquals(
                "Unknown option: '--timout'",
                mixed.stderr().lines().findFirst().orElse(""),
                () -> "only the unknown option before '--' is named: " + mixed.describe());
        assertFalse(mixed.stderr().contains("-query"), () -> mixed.describe());
    }

    @Test
    void aClusteredUnknownShortOptionReportsOnlyTheUnknownFlag() {
        RunResult clustered = runOn(new CommandLine(new ProgWithQuery()), "-Vz", "heat pump");

        assertEquals(2, clustered.exitCode(), () -> clustered.describe());
        assertEquals(
                "Unknown option: '-z' (while processing option: '-Vz')",
                clustered.stderr().lines().findFirst().orElse(""),
                () -> "the trailing query is dropped while the clustered unknown flag stays named: "
                        + clustered.describe());
        assertFalse(clustered.stderr().contains("heat pump"), () -> clustered.describe());
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

    @Command(name = "prog")
    static final class ProgWithQuery implements Callable<Integer> {

        @Option(names = "-V", description = "Verbose.")
        boolean verbose;

        @Parameters(index = "0", arity = "0..1", description = "Query.")
        String query;

        @Override
        public Integer call() {
            return 0;
        }
    }

    @Command(name = "root")
    static final class RootWithSubcommand implements Runnable {

        @Override
        public void run() {}
    }

    @Command(name = "web")
    static final class WebSubcommand implements Callable<Integer> {

        @Option(names = "--count", description = "Count.")
        int count;

        @Parameters(index = "0", description = "Query.")
        String query;

        @Override
        public Integer call() {
            return 0;
        }
    }

    private static RunResult run(String... args) {
        return runOn(new CommandLine(new Prog()), args);
    }

    private static RunResult runOn(CommandLine commandLine, String... args) {
        StrictOptionParsing.apply(commandLine);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        commandLine.setOut(new PrintWriter(new OutputStreamWriter(stdout, UTF_8), true));
        commandLine.setErr(new PrintWriter(new OutputStreamWriter(stderr, UTF_8), true));
        int exitCode = commandLine.execute(args);
        return new RunResult(exitCode, stdout.toString(UTF_8), stderr.toString(UTF_8));
    }

    private record RunResult(int exitCode, String stdout, String stderr) {
        String describe() {
            return "exitCode=" + exitCode + ", stdout=<" + stdout + ">, stderr=<" + stderr + ">";
        }
    }
}

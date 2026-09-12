package io.amscotti.bravesearch.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Version and usage contracts of the root command, asserted through the testable entry point. */
final class MainTest {

    @Test
    void versionVariantsPrintIdenticalSingleLfTerminatedLine() throws IOException {
        RunResult longFlag = runMain("--version");
        RunResult shortFlag = runMain("-V");
        RunResult subcommand = runMain("version");

        assertEquals(0, longFlag.exitCode(), "--version stdout: " + longFlag.describe());
        assertEquals(0, shortFlag.exitCode(), "-V stdout: " + shortFlag.describe());
        assertEquals(0, subcommand.exitCode(), "version stdout: " + subcommand.describe());

        String expected = expectedVersionLine();
        assertEquals(expected, longFlag.stdout(), "--version bytes differ");
        assertEquals(expected, shortFlag.stdout(), "-V bytes differ");
        assertEquals(expected, subcommand.stdout(), "version subcommand bytes differ");
        assertEquals("", longFlag.stderr(), "--version stderr");
        assertEquals("", shortFlag.stderr(), "-V stderr");
        assertEquals("", subcommand.stderr(), "version subcommand stderr");
    }

    @Test
    void unknownOptionFailsWithNonZeroExitAndUsageOnStderr() {
        RunResult result = runMain("--definitely-bogus");

        assertNotEquals(0, result.exitCode(), result.describe());
        assertTrue(
                result.stderr().contains("Usage: brave-search"),
                () -> "usage help missing from stderr: " + result.describe());
    }

    @Test
    void aFailedStdoutWriteOfTheVersionCommandExitsSixWithExactlyOneStderrLine() {
        java.io.ByteArrayOutputStream stderr = new java.io.ByteArrayOutputStream();
        java.io.PrintWriter broken = new java.io.PrintWriter(
                new java.io.OutputStreamWriter(
                        new java.io.OutputStream() {
                            @Override
                            public void write(int b) throws java.io.IOException {
                                throw new java.io.IOException("stdout is gone");
                            }
                        },
                        java.nio.charset.StandardCharsets.UTF_8),
                true);
        picocli.CommandLine collected = new Main().assemble(broken);
        collected.setErr(new java.io.PrintWriter(new java.io.OutputStreamWriter(stderr, java.nio.charset.StandardCharsets.UTF_8), true));

        int exit = collected.execute("version");

        org.junit.jupiter.api.Assertions.assertEquals(6, exit, "a failed stdout write keeps the transport status");
        java.util.List<String> diagnostics =
                stderr.toString(java.nio.charset.StandardCharsets.UTF_8).lines().toList();
        org.junit.jupiter.api.Assertions.assertEquals(
                1, diagnostics.size(), () -> "exactly one diagnostic line: <" + stderr + ">");
    }

    @Test
    void rootInvocationWithoutArgumentsPrintsUsageHelpToStdoutAndExitsZero() {
        RunResult result = runMain();

        assertEquals(0, result.exitCode(), result.describe());
        assertTrue(
                result.stdout().contains("Usage: brave-search"),
                () -> "usage help missing from stdout: " + result.describe());
        assertEquals("", result.stderr(), result.describe());
    }

    /**
     * Reads the same generated {@code brave-search-version.properties} the command reads, so the
     * test pins exact output bytes without duplicating the version literal.
     */
    private static String expectedVersionLine() throws IOException {
        Properties properties = new Properties();
        try (InputStream resource = MainTest.class.getResourceAsStream("/brave-search-version.properties")) {
            assertNotNull(resource, "brave-search-version.properties must be on the test classpath");
            properties.load(resource);
        }
        String version = properties.getProperty("version");
        assertNotNull(version, "brave-search-version.properties must carry a version entry");
        return Main.COMMAND_NAME + " " + version + "\n";
    }

    private record RunResult(int exitCode, String stdout, String stderr) {
        String describe() {
            return "exitCode=" + exitCode + ", stdout=<" + stdout + ">, stderr=<" + stderr + ">";
        }
    }

    private static RunResult runMain(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdoutSink = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrSink = new ByteArrayOutputStream();
        System.setOut(new PrintStream(stdoutSink, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(stderrSink, true, StandardCharsets.UTF_8));
        try {
            int exitCode = new Main().execute(args);
            return new RunResult(exitCode, stdoutSink.toString(StandardCharsets.UTF_8), stderrSink.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }
}

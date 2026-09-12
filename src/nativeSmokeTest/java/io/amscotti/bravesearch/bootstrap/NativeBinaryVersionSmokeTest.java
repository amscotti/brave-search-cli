package io.amscotti.bravesearch.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Runs the exact native executable produced by nativeCompile and asserts the on-disk {@code
 * --version} contract: exit code 0, exactly one LF-terminated version line on stdout, empty
 * stderr, and no ANSI escape bytes. Unknown options must fail with a non-zero exit code.
 */
final class NativeBinaryVersionSmokeTest {

    /** Full-output match: exactly one version line, nothing before or after it. */
    private static final Pattern VERSION_OUTPUT = Pattern.compile("brave-search \\S+\r?\n");

    @Test
    @Timeout(value = 120)
    void nativeBinaryPrintsSingleLfTerminatedVersionLine() throws Exception {
        CapturedProcess result = run(nativeBinary(), "--version");

        assertEquals(0, result.exitCode(), () -> "stderr was: <" + result.stderr() + ">");
        assertTrue(
                VERSION_OUTPUT.matcher(result.stdout()).matches(),
                () -> "stdout is not a single 'brave-search <version>' line: " + result.describe());
        assertFalse(result.stdout().contains("\r"), "version line must end in LF, not CRLF");
        assertEquals(
                result.stdout().length() - 1,
                result.stdout().lastIndexOf('\n'),
                "version output must end with exactly one LF");
        assertEquals("", result.stderr(), "stderr must stay empty for --version");
        assertFalse(
                result.stdout().contains("\u001b") || result.stderr().contains("\u001b"),
                "output must not contain ANSI escape bytes");
    }

    @Test
    @Timeout(value = 120)
    void nativeBinaryRejectsUnknownOptionWithNonZeroExitAndUsageOnStderr() throws Exception {
        CapturedProcess result = run(nativeBinary(), "--definitely-bogus");

        assertNotEquals(0, result.exitCode(), () -> "stderr was: <" + result.stderr() + ">");
        assertTrue(
                result.stderr().contains("Usage: brave-search"),
                () -> "usage help missing from stderr: " + result.describe());
    }

    private record CapturedProcess(int exitCode, String stdout, String stderr) {
        String describe() {
            return "exitCode=" + exitCode + ", stdout=<" + stdout + ">, stderr=<" + stderr + ">";
        }
    }

    private static Path nativeBinary() {
        String binaryProperty = System.getProperty("brave.search.native.binary");
        assertNotNull(binaryProperty, "brave.search.native.binary must be injected by the build");
        Path binary = Path.of(binaryProperty);
        assertTrue(Files.isRegularFile(binary), () -> "native binary missing: " + binary);
        assertTrue(Files.isExecutable(binary), () -> "native binary not executable: " + binary);
        return binary;
    }

    /**
     * Starts the binary and drains both output streams on dedicated threads so a full pipe can
     * never deadlock the child process. The child is destroyed on every path so a failed
     * assertion or timeout can never leak it.
     */
    private static CapturedProcess run(Path binary, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(binary.toString());
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).start();
        try {
            ByteArrayOutputStream stdoutSink = new ByteArrayOutputStream();
            ByteArrayOutputStream stderrSink = new ByteArrayOutputStream();
            Thread stdoutDrainer = drain(process.getInputStream(), stdoutSink);
            Thread stderrDrainer = drain(process.getErrorStream(), stderrSink);
            boolean finishedWithinTimeout = process.waitFor(60, TimeUnit.SECONDS);
            stdoutDrainer.join(10_000);
            stderrDrainer.join(10_000);

            assertTrue(finishedWithinTimeout, () -> binary + " did not exit within 60 seconds");

            return new CapturedProcess(
                    process.exitValue(),
                    stdoutSink.toString(StandardCharsets.UTF_8),
                    stderrSink.toString(StandardCharsets.UTF_8));
        } finally {
            // destroying an already-exited process is a no-op; this only bites on failure paths
            process.destroyForcibly();
        }
    }

    private static Thread drain(InputStream stream, ByteArrayOutputStream sink) {
        Thread drainer =
                new Thread(
                        () -> {
                            try (InputStream in = stream) {
                                in.transferTo(sink);
                            } catch (IOException e) {
                                // the child died or closed the pipe; assertions decide success
                            }
                        },
                        "process-output-drain");
        drainer.setDaemon(true);
        drainer.start();
        return drainer;
    }
}

package io.amscotti.bravesearch.testsupport;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Terminal-facing behavior of the pseudo-terminal harness: terminal output larger than the
 * operating-system pipe buffer still arrives whole, and the deadline path kills the
 * slave-side process group outright, so a child that ignores the terminal's hangup signal
 * cannot survive its own deadline as an orphan — neither when the expiry lands before the
 * helper has reported the child's pid, nor when the deadline runs past the helper's own
 * former drain bound.
 */
final class PtyHarnessTest {

    /** Terminal bytes well past a typical operating-system pipe buffer (~64KiB). */
    private static final int PIPE_BUFFER_BUSTER_BYTES = 256 * 1024;

    private static String python;

    @BeforeAll
    static void python3MustBeAvailableToAllocateAPseudoTerminal() {
        python = PtyHarness.python3OnPath();
        assumeTrue(python != null, "SKIPPED: no python 3 on the path, so no pseudo-terminal can be allocated");
    }

    @Test
    @Timeout(120)
    void capturesTerminalOutputBeyondTheOperatingSystemPipeBuffer() throws Exception {
        PtyHarness harness = new PtyHarness(python);

        PtyHarness.PtyResult result = harness.run(
                List.of("/usr/bin/head", "-c", Integer.toString(PIPE_BUFFER_BUSTER_BYTES), "/dev/zero"),
                Map.of(),
                Set.of(),
                false);

        assertEquals(0, result.exitStatus());
        assertEquals(
                PIPE_BUFFER_BUSTER_BYTES,
                result.stdout().length,
                "every terminal byte must be captured even past the operating-system pipe buffer");
    }

    @Test
    @Timeout(120)
    void deadlineExpiryKillsOnlyTheSlaveSideProcessGroup() throws Exception {
        Path pidMarker = Files.createTempFile("brave-pty-orphan-probe", ".txt");
        try (ProcessHarness.Session bystander = new ProcessHarness().start(
                List.of("/bin/sleep", "30"), Map.of())) {
            // the probe ignores SIGHUP — the signal a closing master would deliver — so only
            // an outright kill of its process group can end it
            String hangupIgnoringProbe = "import os, signal, sys, time\n"
                    + "signal.signal(signal.SIGHUP, signal.SIG_IGN)\n"
                    + "open(sys.argv[1], 'w').write(str(os.getpid()))\n"
                    + "time.sleep(30)\n";
            PtyHarness harness = new PtyHarness(python);

            IOException failure = assertThrows(
                    IOException.class,
                    () -> harness.run(
                            List.of(python, "-c", hangupIgnoringProbe, pidMarker.toString()),
                            Map.of(),
                            Set.of(),
                            false,
                            Duration.ofMillis(750)));

            assertTrue(failure.getMessage().contains("outlived its deadline"), failure.getMessage());
            long probe = awaitPidMarker(pidMarker, Duration.ofSeconds(10));
            assertTrue(
                    awaitGone(probe, Duration.ofSeconds(15)),
                    "a hangup-ignoring child must not survive the deadline as an orphan");
            assertTrue(
                    ProcessHandle.of(bystander.pid()).map(ProcessHandle::isAlive).orElse(false),
                    "deadline cleanup must leave processes outside the slave-side group alive");
        } finally {
            Files.deleteIfExists(pidMarker);
        }
    }

    @Test
    @Timeout(120)
    void deadlineExpiryBeforeThePidIsReportedStillKillsTheSlaveSideProcessGroup() throws Exception {
        Path pidMarker = Files.createTempFile("brave-pty-early-orphan-probe", ".txt");
        try {
            // the probe ignores SIGHUP — the signal a closing master would deliver — so only
            // an outright kill of its process group can end it
            String hangupIgnoringProbe = "import os, signal, sys, time\n"
                    + "signal.signal(signal.SIGHUP, signal.SIG_IGN)\n"
                    + "open(sys.argv[1], 'w').write(str(os.getpid()))\n"
                    + "time.sleep(30)\n";
            PtyHarness harness = new PtyHarness(python);

            // a one-millisecond deadline expires while the helper is still starting, before
            // it can report the child's pid through the pid file
            IOException failure = assertThrows(
                    IOException.class,
                    () -> harness.run(
                            List.of(python, "-c", hangupIgnoringProbe, pidMarker.toString()),
                            Map.of(),
                            Set.of(),
                            false,
                            Duration.ofMillis(1)));

            assertTrue(failure.getMessage().contains("outlived its deadline"), failure.getMessage());
            // a probe that never reported was killed before its opening statement could run —
            // SIGKILL cannot be ignored — so the absence itself is proof no orphan exists
            OptionalLong reported = probePid(pidMarker, Duration.ofSeconds(10));
            if (reported.isPresent()) {
                assertTrue(
                        awaitGone(reported.getAsLong(), Duration.ofSeconds(15)),
                        "a hangup-ignoring child must not survive an early deadline as an orphan");
            }
        } finally {
            Files.deleteIfExists(pidMarker);
        }
    }

    @Test
    @Timeout(120)
    void deadlineLongerThanSixtySecondsStillReportsExpiry() throws Exception {
        // the helper's drain bound must follow the given deadline instead of resolving the
        // run at its own earlier limit and returning a killed child as a normal result
        String sleeper = "import time\n" + "time.sleep(120)\n";
        PtyHarness harness = new PtyHarness(python);

        IOException failure = assertThrows(
                IOException.class,
                () -> harness.run(
                        List.of(python, "-c", sleeper), Map.of(), Set.of(), false, Duration.ofSeconds(65)));

        assertTrue(failure.getMessage().contains("outlived its deadline"), failure.getMessage());
    }

    /** The child's pid once its probe file names it, so the orphan check knows what to watch. */
    private static long awaitPidMarker(Path pidMarker, Duration bound) throws Exception {
        return probePid(pidMarker, bound)
                .orElseThrow(() -> new AssertionError("the hangup-ignoring probe never reported its pid"));
    }

    /**
     * The pid the probe file names once the child managed to report it, or empty when the
     * bound passes with no report.
     */
    private static OptionalLong probePid(Path pidMarker, Duration bound) throws Exception {
        long deadline = System.nanoTime() + bound.toNanos();
        while (System.nanoTime() < deadline) {
            String reported = Files.readString(pidMarker, UTF_8).trim();
            if (!reported.isEmpty()) {
                return OptionalLong.of(Long.parseLong(reported));
            }
            Thread.sleep(25);
        }
        return OptionalLong.empty();
    }

    private static boolean awaitGone(long pid, Duration bound) throws InterruptedException {
        long deadline = System.nanoTime() + bound.toNanos();
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true)) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }
}

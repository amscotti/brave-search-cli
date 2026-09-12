package io.amscotti.bravesearch.testsupport;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Child-process behavior of the harness: stdout/stderr bytes arrive captured and separate, stdin
 * is piped in, deadline expiry kills the process tree and reports the timeout, signal deaths
 * decode as 128+signum exit statuses, heavy interleaved output cannot deadlock the drains, and
 * reports never leak environment values.
 */
final class ProcessHarnessTest {

    private final ProcessHarness harness = new ProcessHarness();

    @Test
    void capturesStdoutBytesOfASimpleChild() throws IOException {
        ProcessHarness.ProcessResult result = harness.launch(List.of("/bin/echo", "-n", "hi"), Map.of(), new byte[0]);

        assertEquals(0, result.exitStatus());
        assertEquals("hi", new String(result.stdout(), UTF_8));
        assertEquals(0, result.stderr().length);
        assertFalse(result.timedOut());
        assertEquals(List.of("/bin/echo", "-n", "hi"), result.command());
    }

    @Test
    void pipesProvidedStdinIntoTheChild() throws IOException {
        ProcessHarness.ProcessResult result = harness.launch(List.of("/usr/bin/wc", "-c"), Map.of(), "hello".getBytes(UTF_8));

        assertEquals(0, result.exitStatus());
        assertEquals("5", new String(result.stdout(), UTF_8).trim());
    }

    @Test
    void closesChildStdinWhenThePayloadIsEmptySoEofReadersFinish() throws IOException {
        ProcessHarness.ProcessResult result = harness.launch(List.of("/usr/bin/wc", "-c"), Map.of(), new byte[0]);

        assertFalse(result.timedOut(), "a child reading stdin to EOF must finish on its own, never hit the deadline");
        assertEquals(0, result.exitStatus());
        assertEquals("0", new String(result.stdout(), UTF_8).trim());
    }

    @Test
    void reportsTimeoutAndKillsAChildThatNeverExits() throws IOException {
        ProcessHarness.ProcessResult result =
                harness.launch(List.of("/bin/sleep", "30"), Map.of(), new byte[0], Duration.ofMillis(750));

        assertTrue(result.timedOut());
        assertEquals(137, result.exitStatus(), "forced cleanup is SIGKILL, reported as 128+9 on POSIX");
    }

    @Test
    void deadlineCleanupKillsTheWholeDescendantTreeAtEveryDepth() throws Exception {
        // three levels below the harness — outer shell, inner shell, sleeper — because the
        // kill must reach past the direct child's own children to the bottom of the tree
        ProcessHarness.ProcessResult result = harness.launch(
                List.of("/bin/sh", "-c", "sh -c 'sleep 30 & echo $!; wait'; sleep 30"),
                Map.of(),
                new byte[0],
                Duration.ofMillis(750));

        assertTrue(result.timedOut());
        long deepest = Long.parseLong(new String(result.stdout(), UTF_8).trim());
        assertTrue(
                awaitGone(deepest, Duration.ofSeconds(10)),
                "the deepest descendant must die with the harness's process-tree cleanup, not survive it reparented");
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

    @Test
    void decodesInterruptSignalDeathAsOffsetExitStatus() throws IOException {
        ProcessHarness.ProcessResult result = harness.launch(List.of("/bin/sh", "-c", "kill -INT $$"), Map.of(), new byte[0]);

        assertFalse(result.timedOut());
        assertEquals(130, result.exitStatus(), "POSIX waitFor reports 128+signum, so SIGINT decodes as 130");
    }

    @Test
    void drainsInterleavedStreamsHeavilyWithoutDeadlocking() throws IOException {
        String child = "i=0; while [ $i -lt 15000 ]; do echo out-$i; echo err-$i 1>&2; i=$((i+1)); done";
        ProcessHarness.ProcessResult result = harness.launch(
                List.of("/bin/sh", "-c", child), Map.of(), new byte[0], Duration.ofSeconds(60));

        assertEquals(0, result.exitStatus());
        String stdout = new String(result.stdout(), UTF_8);
        String stderr = new String(result.stderr(), UTF_8);
        assertEquals(15000, stdout.lines().count());
        assertEquals(15000, stderr.lines().count());
        assertTrue(stdout.endsWith("out-14999\n"));
        assertTrue(stderr.endsWith("err-14999\n"));
    }

    @Test
    void neverExposesEnvironmentValuesInReports() throws IOException {
        String secret = "fixture-secret-value-that-must-never-surface";
        Map<String, String> environment = Map.of("SECRET_TOKEN", secret);

        ProcessHarness.ProcessResult result =
                harness.launch(List.of("/bin/echo", "-n", "ok"), environment, new byte[0]);
        assertFalse(result.toString().contains(secret));

        IOException failure = assertThrows(
                IOException.class,
                () -> harness.launch(List.of("/definitely/missing/program"), environment, new byte[0]));
        assertFalse(failure.getMessage().contains(secret));
        assertTrue(failure.getMessage().contains("definitely"), "launch failures still name the command");
    }

    @Test
    void scrubsNamedVariablesBeforeLayeringAdditions() throws IOException {
        ProcessHarness.ProcessResult additions =
                harness.launch(
                        List.of("/bin/sh", "-c", "printf %s \"${HARNESS_SCRATCH:-absent}\""),
                        Map.of("HARNESS_SCRATCH", "set"),
                        new byte[0],
                        java.util.Set.of("HARNESS_SCRATCH"));
        ProcessHarness.ProcessResult removed =
                harness.launch(
                        List.of("/bin/sh", "-c", "printf %s \"${HARNESS_SCRATCH:-absent}\""),
                        Map.of(),
                        new byte[0],
                        java.util.Set.of("HARNESS_SCRATCH"));

        assertEquals("set", new String(additions.stdout(), UTF_8), "layered additions are applied after the scrub");
        assertEquals("absent", new String(removed.stdout(), UTF_8), "scrubbed variables never reach the child");
    }

    @Test
    void sessionStreamsStdoutIncrementallyAndReportsTheNaturalExitStatus() throws Exception {
        ProcessHarness.Session session = harness.start(List.of("/bin/echo", "-n", "partial"), Map.of());

        try (session) {
            byte[] seen = readUntilAvailable(session.stdout(), 5);
            assertEquals("partial", new String(seen, UTF_8));
            assertTrue(session.awaitExit(Duration.ofSeconds(10)), "a quick child must exit on its own");
            assertEquals(0, session.exitValue());
        }
    }

    @Test
    void sessionSignalDeliversPosixInterruptAndDecodesItAs130() throws Exception {
        ProcessHarness.Session session = harness.start(List.of("/bin/sleep", "30"), Map.of());

        try (session) {
            session.signal("INT");
            assertTrue(session.awaitExit(Duration.ofSeconds(10)), "an interrupted sleep must die promptly");
            assertEquals(130, session.exitValue(), "/bin/kill -INT through the harness must reach the child");
        }
    }

    @Test
    void signalingAnAlreadyExitedChildIsAToleratedNoOp() throws Exception {
        ProcessHarness.Session session = harness.start(List.of("/bin/echo", "-n", "done"), Map.of());

        try (session) {
            assertTrue(session.awaitExit(Duration.ofSeconds(10)), "the quick child must exit first");
            session.signal("INT");
            assertEquals(0, session.exitValue(), "the no-op signal must leave the observed status alone");
        }
    }

    @Test
    void closingTheSessionStdoutPipeBreaksAWriterChildWithSigpipe() throws Exception {
        ProcessHarness.Session session =
                harness.start(List.of("/bin/sh", "-c", "while true; do echo flooding; done"), Map.of());

        try (session) {
            readUntilAvailable(session.stdout(), 16);
            session.stdout().close();
            assertTrue(session.awaitExit(Duration.ofSeconds(10)), "a SIGPIPE'd writer must die promptly");
            assertEquals(141, session.exitValue(), "an unhandled broken pipe on the child side is 128+13");
        }
    }

    @Test
    void sessionStderrCapturesEverythingTheChildEmitted() throws Exception {
        ProcessHarness.Session session =
                harness.start(List.of("/bin/sh", "-c", "echo out; echo err 1>&2"), Map.of());

        try (session) {
            assertTrue(session.awaitExit(Duration.ofSeconds(10)));
            assertEquals("err" + System.lineSeparator(), new String(session.stderr(), UTF_8));
        }
    }

    private static byte[] readUntilAvailable(InputStream stream, int atLeast) throws IOException, InterruptedException {
        ByteArrayOutputStream seen = new ByteArrayOutputStream();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (seen.size() < atLeast && System.nanoTime() < deadline) {
            int readable = stream.available();
            if (readable > 0) {
                byte[] buffer = new byte[readable];
                int read = stream.read(buffer, 0, readable);
                if (read > 0) {
                    seen.write(buffer, 0, read);
                }
            } else {
                Thread.sleep(20);
            }
        }
        return seen.toByteArray();
    }
}

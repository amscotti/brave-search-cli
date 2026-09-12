package io.amscotti.bravesearch.testsupport;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * The streaming lifecycle scenarios of the hidden stream probe, run against a whole probe
 * process and a scripted loopback server. Each scenario takes the command prefix that launches
 * the probe — the installed JVM launcher or the native binary — so both executables must show
 * identical evidence: natural interrupt as 130 with a server-side closed-connection witness, a
 * broken stdout pipe as a silent 0, a drained stream as 0 byte-exactly, an idle deadline or an
 * abruptly cut connection as 6 with one stderr diagnostic, a silent peer bounded by the wall
 * budget as 6, a non-loopback destination as usage error 2 before any connection, and zero or
 * negative duration budgets as usage error 2 before any request.
 */
public final class StreamProbeScenarios {

    /** How long a probe process may take to start, connect, and react. */
    private static final Duration PROBE_DEADLINE = Duration.ofSeconds(30);

    private StreamProbeScenarios() {}

    /**
     * A natural interrupt exits 130 with byte-exact pre-interrupt stdout, and the server
     * witnesses the closed connection: after the probe is gone, a handler-side write must fail.
     */
    public static void sigintExits130Naturally(List<String> probeCommand) throws Exception {
        byte[] preKill = frame("tick", "before-interrupt");
        CountDownLatch releasePostInterruptWrite = new CountDownLatch(1);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                        .header("Content-Type", "text/event-stream")
                        .writeBytes(preKill)
                        .flush()
                        .stallUntil(releasePostInterruptWrite)
                        .heartbeatUntilClosed("after-interrupt")
                        .start();
                ProcessHarness.Session session = new ProcessHarness()
                        .start(withUrl(probeCommand, server.baseUrl() + "events"), java.util.Map.of())) {
            assertTrue(server.awaitFirstRequest(PROBE_DEADLINE), "the probe must reach the scripted server");
            byte[] seen = readUntilContains(session.stdout(), preKill, PROBE_DEADLINE);

            session.signal("INT");

            assertTrue(session.awaitExit(PROBE_DEADLINE), "an interrupted probe must exit promptly");
            assertEquals(130, session.exitValue(), "SIGINT must surface as the conventional shell status");
            byte[] everything = concat(seen, drainAfterExit(session));
            assertArrayEquals(preKill, everything, "stdout must hold exactly the pre-interrupt bytes");
            assertNoBanner(session, "interrupt");

            releasePostInterruptWrite.countDown();
            assertTrue(
                    server.awaitConnectionClosed(Duration.ofSeconds(10)),
                    "the server must observe the closed connection once the interrupted probe is gone");
        } finally {
            releasePostInterruptWrite.countDown();
        }
    }

    /** Closing the reader end of the probe's stdout is the EPIPE path and must stay silent. */
    public static void epipeExitsZeroSilently(List<String> probeCommand) throws Exception {
        byte[] chunk = pattern64K();
        ScriptedSseServer.Builder script = ScriptedSseServer.builder().header("Content-Type", "text/event-stream");
        for (int i = 0; i < 512; i++) {
            script.writeBytes(chunk).flush();
        }
        try (ScriptedSseServer server = script.start();
                ProcessHarness.Session session = new ProcessHarness()
                        .start(withUrl(probeCommand, server.baseUrl() + "events"), java.util.Map.of())) {
            assertTrue(server.awaitFirstRequest(PROBE_DEADLINE), "the probe must reach the scripted server");

            readExactly(session.stdout(), 256 * 1024);
            session.stdout().close();

            assertTrue(session.awaitExit(PROBE_DEADLINE), "a probe with a broken stdout must exit promptly");
            assertEquals(0, session.exitValue(), "a broken pipe is successful early consumer termination");
            assertNoBanner(session, "broken pipe");
        }
    }

    /** A fully delivered script drains to stdout byte-exactly and closes with status 0. */
    public static void normalCompletionExitsZero(List<String> probeCommand) throws Exception {
        byte[] first = frame("one", "first-payload");
        byte[] second = new byte[] {0x0D, 0x0A, (byte) 0xC3, (byte) 0xA9, 0x00, (byte) 0xFF, 0x7F, 0x0A};
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                        .header("Content-Type", "text/event-stream")
                        .writeBytes(first)
                        .flush()
                        .writeBytes(second)
                        .start();
                ProcessHarness.Session session = new ProcessHarness()
                        .start(withUrl(probeCommand, server.baseUrl() + "events"), java.util.Map.of())) {
            assertTrue(session.awaitExit(PROBE_DEADLINE), "a drained probe must exit on its own");
            assertEquals(0, session.exitValue());
            byte[] everything = drainAfterExit(session);
            assertArrayEquals(concat(first, second), everything, "stdout must be the scripted bytes exactly");
            assertNoBanner(session, "normal completion");
        }
    }

    /** A stalled server plus a one-second idle budget ends the run as 6 with one stderr line. */
    public static void idleTimeoutExits6(List<String> probeCommand) throws Exception {
        byte[] delivered = frame("only", "then-silence");
        CountDownLatch neverRelease = new CountDownLatch(1);
        try (ScriptedSseServer server = stalledAfter(delivered, neverRelease);
                ProcessHarness.Session session = new ProcessHarness()
                        .start(
                                withUrlAndArgs(probeCommand, server.baseUrl() + "events", "--idle-timeout", "1s"),
                                java.util.Map.of())) {
            assertTrue(server.awaitFirstRequest(PROBE_DEADLINE), "the probe must reach the scripted server");

            assertTrue(session.awaitExit(PROBE_DEADLINE), "the idle deadline must end the run promptly");
            assertEquals(6, session.exitValue(), "an idle timeout is the generic streaming failure status");
            byte[] everything = drainAfterExit(session);
            assertArrayEquals(delivered, everything, "only the delivered bytes may reach stdout");
            List<String> lines = new String(session.stderr(), UTF_8).lines().toList();
            assertEquals(1, lines.size(), () -> "exactly one diagnostic line: <" + new String(session.stderr(), UTF_8) + ">");
            assertTrue(lines.getFirst().contains("IDLE_TIMEOUT"), () -> "diagnostic must name the cause: " + lines);
        } finally {
            neverRelease.countDown();
        }
    }

    /**
     * A server that cuts the connection mid-body must end the probe as a transport failure:
     * exit 6, exactly one stderr diagnostic, and never a hang waiting for a cause.
     */
    public static void abruptCloseExits6WithOneDiagnostic(List<String> probeCommand) throws Exception {
        byte[] delivered = frame("partial", "then-cut");
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                        .header("Content-Type", "text/event-stream")
                        .writeBytes(delivered)
                        .flush()
                        .abruptClose()
                        .start();
                ProcessHarness.Session session = new ProcessHarness()
                        .start(withUrl(probeCommand, server.baseUrl() + "events"), java.util.Map.of())) {
            assertTrue(server.awaitFirstRequest(PROBE_DEADLINE), "the probe must reach the scripted server");

            assertTrue(session.awaitExit(PROBE_DEADLINE), "an abruptly cut stream must end the run, never hang");
            assertEquals(6, session.exitValue(), "a mid-stream transport failure is the generic streaming failure status");
            byte[] everything = drainAfterExit(session);
            assertArrayEquals(delivered, everything, "only the bytes before the cut may reach stdout");
            List<String> lines = new String(session.stderr(), UTF_8).lines().toList();
            assertEquals(1, lines.size(), () -> "exactly one diagnostic line: <" + new String(session.stderr(), UTF_8) + ">");
            assertTrue(lines.getFirst().contains("TRANSPORT_FAILURE"), () -> "diagnostic must name the cause: " + lines);
        }
    }

    /** Any destination outside the literal loopback set is a usage error before connecting. */
    public static void nonLoopbackUrlRejected(List<String> probeCommand) throws Exception {
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                        .writeBytes("never delivered".getBytes(UTF_8))
                        .start();
                ProcessHarness.Session session = new ProcessHarness()
                        .start(
                                withUrl(probeCommand, "http://example.com/events"),
                                java.util.Map.of())) {
            assertTrue(session.awaitExit(PROBE_DEADLINE), "a refused URL must fail fast");
            assertEquals(2, session.exitValue(), "a non-loopback destination is a usage error");
            String stderr = new String(session.stderr(), UTF_8);
            assertTrue(stderr.contains("Usage:"), () -> "usage must appear on stderr: <" + stderr + ">");
            assertTrue(stderr.contains("refusing"), () -> "the refusal must be explained: <" + stderr + ">");
            assertFalse(
                    server.awaitFirstRequest(Duration.ofSeconds(2)), "no server may observe any request");
            assertTrue(server.requests().isEmpty(), "the scripted server recorded nothing");
        }
    }

    /**
     * A peer that accepts the connection but never sends headers must not outlive the run's wall
     * budget: the opening phase ends as 6 with one diagnostic, never a parked process.
     */
    public static void silentHeadersEndWithinTheWallBudgetAs6(List<String> probeCommand) throws Exception {
        CountDownLatch neverSendHeaders = new CountDownLatch(1);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                        .holdHeadersUntil(neverSendHeaders)
                        .writeBytes("never delivered".getBytes(UTF_8))
                        .start();
                ProcessHarness.Session session = new ProcessHarness()
                        .start(
                                withUrlAndArgs(probeCommand, server.baseUrl() + "events", "--wall-timeout", "2s"),
                                java.util.Map.of())) {
            assertTrue(server.awaitFirstRequest(PROBE_DEADLINE), "the probe must reach the scripted server");

            assertTrue(
                    session.awaitExit(PROBE_DEADLINE), "a silent peer must not park the probe past its budget");
            assertEquals(6, session.exitValue(), "a headers-phase timeout is the generic streaming failure status");
            List<String> lines = new String(session.stderr(), UTF_8).lines().toList();
            assertEquals(1, lines.size(), () -> "exactly one diagnostic line: <" + new String(session.stderr(), UTF_8) + ">");
            assertTrue(
                    lines.getFirst().contains("cannot open stream"),
                    () -> "the diagnostic must name the failed open: " + lines);
        } finally {
            neverSendHeaders.countDown();
        }
    }

    /** Zero and negative duration budgets are usage errors rejected before any connection. */
    public static void zeroOrNegativeDurationIsAUsageErrorBeforeAnyRequest(List<String> probeCommand)
            throws Exception {
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                        .writeBytes("never delivered".getBytes(UTF_8))
                        .start()) {
            for (String candidate : List.of("--idle-timeout:0s", "--wall-timeout:PT-1S")) {
                String[] flagAndValue = candidate.split(":", 2);
                try (ProcessHarness.Session session = new ProcessHarness()
                        .start(
                                withUrlAndArgs(
                                        probeCommand,
                                        server.baseUrl() + "events",
                                        flagAndValue[0],
                                        flagAndValue[1]),
                                java.util.Map.of())) {
                    assertTrue(session.awaitExit(PROBE_DEADLINE), "a refused budget must fail fast");
                    assertEquals(
                            2,
                            session.exitValue(),
                            () -> candidate + " must be a usage error");
                    String stderr = new String(session.stderr(), UTF_8);
                    assertTrue(
                            stderr.contains("Usage:"), () -> candidate + " must print usage: <" + stderr + ">");
                }
            }
            assertFalse(server.awaitFirstRequest(Duration.ofSeconds(2)), "no server may observe any request");
            assertTrue(server.requests().isEmpty(), "the scripted server recorded nothing");
        }
    }

    private static ScriptedSseServer stalledAfter(byte[] bytes, CountDownLatch forever) throws IOException {
        return ScriptedSseServer.builder()
                .header("Content-Type", "text/event-stream")
                .writeBytes(bytes)
                .flush()
                .stallUntil(forever)
                .start();
    }

    private static List<String> withUrl(List<String> probeCommand, String url) {
        return withUrlAndArgs(probeCommand, url);
    }

    private static List<String> withUrlAndArgs(List<String> probeCommand, String url, String... extraArgs) {
        List<String> command = new ArrayList<>(probeCommand);
        command.add("--url");
        command.add(url);
        command.addAll(List.of(extraArgs));
        return command;
    }

    /** An SSE-looking frame with a few non-ASCII bytes, so byte-exactness is not ASCII luck. */
    private static byte[] frame(String event, String data) {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.writeBytes(("event: " + event + "\ndata: " + data + "\n\n").getBytes(UTF_8));
        frame.writeBytes(new byte[] {(byte) 0xC3, (byte) 0xA9, (byte) 0xFE});
        return frame.toByteArray();
    }

    private static byte[] pattern64K() {
        byte[] chunk = new byte[64 * 1024];
        for (int i = 0; i < chunk.length; i++) {
            chunk[i] = (byte) (i * 31 + (i >> 8));
        }
        return chunk;
    }

    private static void readExactly(InputStream stream, int count) throws IOException {
        byte[] buffer = new byte[8192];
        int total = 0;
        while (total < count) {
            int read = stream.read(buffer, 0, Math.min(buffer.length, count - total));
            if (read < 0) {
                throw new IOException("stdout closed after " + total + " of " + count + " expected bytes");
            }
            total += read;
        }
    }

    private static byte[] readUntilContains(InputStream stream, byte[] expected, Duration timeout)
            throws IOException, InterruptedException {
        ByteArrayOutputStream seen = new ByteArrayOutputStream();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            int readable = stream.available();
            if (readable > 0) {
                byte[] buffer = new byte[readable];
                int read = stream.read(buffer, 0, readable);
                if (read > 0) {
                    seen.write(buffer, 0, read);
                    if (contains(seen.toByteArray(), expected)) {
                        return seen.toByteArray();
                    }
                }
            } else {
                Thread.sleep(20);
            }
        }
        throw new IOException(
                "timed out waiting for stdout to contain <" + new String(expected, UTF_8) + ">, saw <"
                        + seen + ">");
    }

    /** Everything still in flight once the probe has exited; silence means end of stream here. */
    private static byte[] drainAfterExit(ProcessHarness.Session session) throws IOException, InterruptedException {
        ByteArrayOutputStream rest = new ByteArrayOutputStream();
        long hardDeadline = System.nanoTime() + PROBE_DEADLINE.toNanos();
        long quietSince = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < hardDeadline) {
            int readable = session.stdout().available();
            if (readable > 0) {
                byte[] buffer = new byte[readable];
                int read = session.stdout().read(buffer, 0, readable);
                if (read > 0) {
                    rest.write(buffer, 0, read);
                    quietSince = System.nanoTime() + Duration.ofSeconds(2).toNanos();
                }
            } else if (System.nanoTime() > quietSince) {
                break;
            } else {
                Thread.sleep(20);
            }
        }
        return rest.toByteArray();
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int start = 0; start + needle.length <= haystack.length; start++) {
            for (int i = 0; i < needle.length; i++) {
                if (haystack[start + i] != needle[i]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = new byte[first.length + second.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    private static void assertNoBanner(ProcessHarness.Session session, String scenario) {
        assertEquals(
                0,
                session.stderr().length,
                () -> scenario + " must leave stderr silent, saw <" + new String(session.stderr(), UTF_8) + ">");
    }
}

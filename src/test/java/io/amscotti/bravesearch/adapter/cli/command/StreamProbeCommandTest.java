package io.amscotti.bravesearch.adapter.cli.command;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.presentation.WriterDiagnosticsSink;
import io.amscotti.bravesearch.application.port.out.StreamProbePort;
import io.amscotti.bravesearch.application.port.out.StreamProbePort.ProbeStream;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.bootstrap.ExchangeRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import picocli.CommandLine;

/**
 * Presentation contracts of the hidden stream probe: destinations outside the literal loopback
 * set are usage errors raised before any exchange opens, scripted bytes reach stdout exactly,
 * and each terminal cause — interrupt, broken pipe, idle deadline, closed — maps to its exit
 * code with diagnostics only on stderr.
 */
final class StreamProbeCommandTest {

    @Test
    void refusesEveryNonLoopbackDestinationAsAUsageErrorBeforeOpening() {
        List<String> refused =
                List.of(
                        "http://example.com/events",
                        "https://127.0.0.1:9/events",
                        "tcp://127.0.0.1:9/events",
                        "http://127.0.0.2/events",
                        "http://user:secret@127.0.0.1:9/events",
                        "http://localhost.evil.test/events",
                        "definitely not a uri");

        for (String candidate : refused) {
            RecordingPort port = new RecordingPort(List.of());
            RunResult result = run(port, "--url", candidate);
            assertEquals(2, result.exitCode(), () -> candidate + " must be a usage error: " + result.describe());
            assertTrue(
                    result.stderr().contains("Usage:"),
                    () -> candidate + " must print usage: " + result.describe());
            assertFalse(port.opened.get(), () -> candidate + " must never reach the transport");
        }
    }

    @Test
    void missingUrlOptionIsAUsageError() {
        RecordingPort port = new RecordingPort(List.of());

        RunResult result = run(port);

        assertEquals(2, result.exitCode(), () -> result.describe());
        assertFalse(port.opened.get(), "no exchange may open without a URL");
    }

    @Test
    void relaysScriptedBytesByteExactlyAndExitsZeroOnCompletion() {
        byte[] first = "event: tick\ndata: a\n\n".getBytes(UTF_8);
        byte[] second = new byte[] {(byte) 0xC3, (byte) 0xA9, 0x0A, (byte) 0xFF, 0x00, 0x7F};
        RecordingPort port = new RecordingPort(List.of(first, second));

        RunResult result = run(port, "--url", "http://127.0.0.1:9/events");

        assertEquals(0, result.exitCode(), () -> result.describe());
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.writeBytes(first);
        expected.writeBytes(second);
        assertArrayEquals(expected.toByteArray(), result.stdout(), "stdout must be the payload bytes exactly");
        assertEquals("", result.stderr(), () -> result.describe());
        assertTrue(port.closed.get(), "the exchange must be closed after completion");
    }

    @Test
    void bracketedIpv6LoopbackIsAcceptedAsALoopbackDestination() {
        RecordingPort port = new RecordingPort(List.of("x".getBytes(UTF_8)));

        RunResult result = run(port, "--url", "http://[::1]:9/events");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals("x", new String(result.stdout(), UTF_8));
        assertEquals(URI.create("http://[::1]:9/events"), port.openedUrl.get(), "the URL must pass through unmodified");
    }

    @Test
    void aWriterThatSurfacesAnErrorIsASilentZeroExit() {
        RecordingPort port = new RecordingPort(List.of("before-break\n".getBytes(UTF_8), "never-written".getBytes(UTF_8)));

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        PrintWriter broken = new PrintWriter(new OutputStreamWriter(new OutputStream() {
            private final AtomicInteger writes = new AtomicInteger();

            @Override
            public void write(int b) throws IOException {
                if (writes.incrementAndGet() > 13) {
                    throw new IOException("broken pipe");
                }
                stdout.write(b);
            }
        }, StandardCharsets.ISO_8859_1));
        CommandLine commandLine = new CommandLine(new StreamProbeCommand(port, new ExchangeRegistry(), WriterDiagnosticsSink::new, Clock.systemUTC()));
        commandLine.setOut(broken);
        commandLine.setErr(new PrintWriter(new ByteArrayOutputStream(), true));

        int exitCode = commandLine.execute("--url", "http://127.0.0.1:9/events");

        assertEquals(0, exitCode, "a broken stdout pipe is a successful early termination");
        assertEquals("before-break\n", stdout.toString(UTF_8), "bytes before the break must be through");
    }

    @Test
    void anInterruptLatchedMidStreamExits130() {
        RecordingPort port = RecordingPort.silentAfter("event: slow\ndata: x\n\n".getBytes(UTF_8));
        ExchangeRegistry registry = new ExchangeRegistry();
        Thread interrupter = new Thread(
                () -> {
                    awaitOpened(port);
                    registry.interruptLiveExchanges();
                },
                "test-interrupter");
        CommandLine commandLine = new CommandLine(new StreamProbeCommand(port, registry, WriterDiagnosticsSink::new, Clock.systemUTC()));
        commandLine.setOut(new PrintWriter(new ByteArrayOutputStream(), true));
        commandLine.setErr(new PrintWriter(new ByteArrayOutputStream(), true));
        interrupter.start();

        int exitCode = commandLine.execute("--url", "http://localhost:9/events");

        try {
            interrupter.join(5_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        assertEquals(130, exitCode, "the interrupt cause must decide the exit code");
        assertTrue(port.closed.get(), "the exchange must still be closed on the interrupt path");
    }

    @Test
    void aTerminationLatchedMidStreamExits143() {
        RecordingPort port = RecordingPort.silentAfter("event: slow\ndata: x\n\n".getBytes(UTF_8));
        ExchangeRegistry registry = new ExchangeRegistry();
        Thread terminator = new Thread(
                () -> {
                    awaitOpened(port);
                    registry.terminateLiveExchanges();
                },
                "test-terminator");
        CommandLine commandLine = new CommandLine(new StreamProbeCommand(port, registry, WriterDiagnosticsSink::new, Clock.systemUTC()));
        commandLine.setOut(new PrintWriter(new ByteArrayOutputStream(), true));
        commandLine.setErr(new PrintWriter(new ByteArrayOutputStream(), true));
        terminator.start();

        int exitCode = commandLine.execute("--url", "http://localhost:9/events");

        try {
            terminator.join(5_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        assertEquals(143, exitCode, "the termination cause must decide the exit code");
        assertTrue(port.closed.get(), "the exchange must still be closed on the termination path");
    }

    @Test
    void anInterruptLatchedDuringTheConnectWindowExits130EvenWhenOpenFails() {
        CountDownLatch openAttempted = new CountDownLatch(1);
        CountDownLatch interruptLanded = new CountDownLatch(1);
        StreamProbePort port = (url, cancellation, idleTimeout, wallTimeout) -> {
            openAttempted.countDown();
            awaitRelease(interruptLanded);
            throw new IOException("connection refused");
        };
        ExchangeRegistry registry = new ExchangeRegistry();
        Thread interrupter = new Thread(
                () -> {
                    awaitRelease(openAttempted);
                    registry.interruptLiveExchanges();
                    interruptLanded.countDown();
                },
                "test-connect-window-interrupter");
        CommandLine commandLine = new CommandLine(new StreamProbeCommand(port, registry, WriterDiagnosticsSink::new, Clock.systemUTC()));
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        commandLine.setOut(new PrintWriter(new ByteArrayOutputStream(), true));
        commandLine.setErr(new PrintWriter(stderr, true));
        interrupter.start();

        int exitCode = commandLine.execute("--url", "http://127.0.0.1:9/events");

        try {
            interrupter.join(5_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        assertEquals(130, exitCode, "an interrupt that won during the connect window decides the exit code");
        assertEquals("", stderr.toString(StandardCharsets.UTF_8), "the interrupt path stays silent");
    }

    @Test
    void idleDeadlineOnASilentStreamExits6WithOneDiagnosticLine() {
        RecordingPort port = RecordingPort.silent();

        RunResult result = run(port, "--url", "http://127.0.0.1:9/events", "--idle-timeout", "100ms");

        assertEquals(6, result.exitCode(), () -> result.describe());
        List<String> lines = result.stderr().lines().toList();
        assertEquals(1, lines.size(), () -> "exactly one diagnostic line: " + result.describe());
        assertTrue(lines.getFirst().contains("IDLE_TIMEOUT"), () -> result.describe());
        assertEquals(0, result.stdout().length, "deadline diagnostics never reach stdout: " + result.describe());
        assertTrue(port.closed.get(), "the exchange must be closed after the deadline");
    }

    @Test
    void deadlineDiagnosticsBypassPicocliPrintlnAndEndInExactlyOneLf() {
        assertLfTerminatedDiagnosticWithoutPrintln(
                RecordingPort.silent(),
                "--url",
                "http://127.0.0.1:9/events",
                "--idle-timeout",
                "100ms");
    }

    @Test
    void openFailureDiagnosticsBypassPicocliPrintlnAndEndInExactlyOneLf() {
        StreamProbePort refusingPort = (url, cancellation, idleTimeout, wallTimeout) -> {
            throw new IOException("connection refused");
        };

        assertLfTerminatedDiagnosticWithoutPrintln(refusingPort, "--url", "http://127.0.0.1:9/events");
    }

    /**
     * Diagnostics must leave through the writer-based diagnostics sink — message plus exactly one
     * LF — and never through picocli's {@code println}, whose line separator is the platform's.
     */
    private static void assertLfTerminatedDiagnosticWithoutPrintln(StreamProbePort port, String... args) {
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        AtomicBoolean usedPrintln = new AtomicBoolean(false);
        PrintWriter err = new PrintWriter(new OutputStreamWriter(stderr, StandardCharsets.UTF_8)) {
            @Override
            public void println(String x) {
                usedPrintln.set(true);
                super.println(x);
            }
        };
        CommandLine commandLine = new CommandLine(new StreamProbeCommand(port, new ExchangeRegistry(), WriterDiagnosticsSink::new, Clock.systemUTC()));
        commandLine.setOut(new PrintWriter(new ByteArrayOutputStream(), true));
        commandLine.setErr(err);

        int exitCode = commandLine.execute(args);

        err.flush();
        assertEquals(6, exitCode, "the failure must keep its stream-failure status");
        assertFalse(usedPrintln.get(), "diagnostics must not use the platform-line-separator println");
        String diagnostic = stderr.toString(StandardCharsets.UTF_8);
        assertTrue(diagnostic.endsWith("\n") && !diagnostic.contains("\r"), () -> "one LF, no CR: <" + diagnostic + ">");
        assertEquals(1, diagnostic.lines().count(), () -> "exactly one diagnostic line: <" + diagnostic + ">");
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aTransportFailureMidStreamExits6WithOneDiagnosticLine() {
        ErroringPort port = new ErroringPort("event: tick\ndata: x\n\n".getBytes(UTF_8));

        RunResult result = run(port, "--url", "http://127.0.0.1:9/events");

        assertEquals(6, result.exitCode(), () -> "a mid-stream transport failure is a stream failure: " + result.describe());
        List<String> lines = result.stderr().lines().toList();
        assertEquals(1, lines.size(), () -> "exactly one diagnostic line: " + result.describe());
        assertTrue(lines.getFirst().contains("TRANSPORT_FAILURE"), () -> result.describe());
        assertArrayEquals(
                "event: tick\ndata: x\n\n".getBytes(UTF_8),
                result.stdout(),
                "bytes before the failure must be through: " + result.describe());
    }

    @Test
    void shorthandDurationsAreAcceptedAndIsoFormsAreUsageErrors() {
        RecordingPort silentPort = RecordingPort.silent();

        RunResult idle = run(silentPort, "--url", "http://127.0.0.1:9/events", "--idle-timeout", "150ms");
        RunResult wall = run(RecordingPort.silent(), "--url", "http://127.0.0.1:9/events", "--wall-timeout", "200ms");

        assertEquals(6, idle.exitCode(), () -> idle.describe());
        assertEquals(6, wall.exitCode(), () -> wall.describe());
        assertTrue(idle.stderr().contains("IDLE_TIMEOUT"), () -> idle.describe());
        assertTrue(wall.stderr().contains("WALL_TIMEOUT"), () -> wall.describe());

        RunResult nonsense = run(silentPort, "--url", "http://127.0.0.1:9/events", "--idle-timeout", "soon");
        assertEquals(2, nonsense.exitCode(), () -> nonsense.describe());

        RunResult iso = run(silentPort, "--url", "http://127.0.0.1:9/events", "--wall-timeout", "PT0.15S");
        assertEquals(
                2, iso.exitCode(), () -> "the duration grammar is ms, s, and m only: " + iso.describe());
    }

    @Test
    void zeroAndNegativeDurationsAreUsageErrorsBeforeAnyRequest() {
        RecordingPort port = new RecordingPort(List.of());
        List<String> refused = List.of("0s", "0ms", "PT0S", "PT-1S");

        for (String candidate : refused) {
            RunResult idle = run(port, "--url", "http://127.0.0.1:9/events", "--idle-timeout", candidate);
            assertEquals(2, idle.exitCode(), () -> candidate + " as idle budget must be a usage error: " + idle.describe());
            assertTrue(idle.stderr().contains("Usage:"), () -> candidate + " must print usage: " + idle.describe());
            assertFalse(port.opened.get(), () -> candidate + " must never reach the transport");

            RunResult wall = run(port, "--url", "http://127.0.0.1:9/events", "--wall-timeout", candidate);
            assertEquals(2, wall.exitCode(), () -> candidate + " as wall budget must be a usage error: " + wall.describe());
            assertFalse(port.opened.get(), () -> candidate + " must never reach the transport");
        }
    }

    private static void awaitOpened(RecordingPort port) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!port.opened.get() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private static void awaitRelease(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "the test's gating latch must open");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting the test's gating latch", interrupted);
        }
    }

    @Test
    void aFailedOpenExplainsItselfWithAFixedDiagnosticThatQuotesNoCauseText() {
        // the JDK's own IOException messages embed addresses and URIs; the open-failure
        // diagnostic must stay a fixed redacted line, like every transport diagnostic
        StreamProbePort leaking = (url, cancellation, idleTimeout, wallTimeout) -> {
            throw new IOException("connection to http://127.0.0.1:9/events failed: token BSKleak material");
        };
        RunResult result = run(leaking, "--url", "http://127.0.0.1:9/events");

        assertEquals(6, result.exitCode(), () -> "a failed open keeps the transport status: " + result.describe());
        List<String> diagnostics = result.stderr().lines().toList();
        assertEquals(1, diagnostics.size(), () -> "exactly one diagnostic line: " + result.describe());
        assertTrue(
                diagnostics.getFirst().contains("cannot open stream"),
                () -> "the diagnostic names the failed open: " + result.describe());
        assertFalse(
                result.stderr().contains("127.0.0.1"),
                "the diagnostic quotes no address text: " + result.describe());
        assertFalse(result.stderr().contains("events"), "the diagnostic quotes no URI path: " + result.describe());
        assertFalse(result.stderr().contains("BSKleak"), "the diagnostic quotes no cause detail: " + result.describe());
    }

    private static RunResult run(StreamProbePort port, String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        CommandLine commandLine = new CommandLine(new StreamProbeCommand(port, new ExchangeRegistry(), WriterDiagnosticsSink::new, Clock.systemUTC()));
        commandLine.setOut(byteExact(stdout));
        commandLine.setErr(new PrintWriter(stderr, true));
        int exitCode = commandLine.execute(args);
        return new RunResult(
                exitCode, stdout.toByteArray(), stderr.toString(StandardCharsets.UTF_8));
    }

    private static PrintWriter byteExact(ByteArrayOutputStream sink) {
        return new PrintWriter(new java.io.OutputStreamWriter(
                new OutputStream() {
                    @Override
                    public void write(int b) {
                        sink.write(b);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) {
                        sink.write(b, off, len);
                    }
                },
                StandardCharsets.ISO_8859_1));
    }

    private record RunResult(int exitCode, byte[] stdout, String stderr) {
        String describe() {
            return "exitCode=" + exitCode + ", stdout=<" + new String(stdout, UTF_8) + ">, stderr=<" + stderr + ">";
        }
    }

    /** Port stub that replays scripted chunks on request and mirrors the publisher's cause rules. */
    private static final class RecordingPort implements StreamProbePort {
        private final List<byte[]> script;
        private final boolean staysSilentAfterScript;
        private final AtomicBoolean opened = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<URI> openedUrl = new AtomicReference<>();

        RecordingPort(List<byte[]> script) {
            this(script, false);
        }

        private RecordingPort(List<byte[]> script, boolean staysSilentAfterScript) {
            this.script = script;
            this.staysSilentAfterScript = staysSilentAfterScript;
        }

        /** A stream that delivers nothing and never ends, like a server stalled forever. */
        static RecordingPort silent() {
            return new RecordingPort(List.of(), true);
        }

        /** A stream that delivers the script once and then stalls forever. */
        static RecordingPort silentAfter(byte[] chunk) {
            return new RecordingPort(List.of(chunk), true);
        }

        @Override
        public ProbeStream open(
                URI url, CancellationContext cancellation, Duration idleTimeout, Duration wallTimeout) {
            opened.set(true);
            openedUrl.set(url);
            return new ProbeStream(scriptedPublisher(cancellation), () -> closed.set(true));
        }

        private Flow.Publisher<byte[]> scriptedPublisher(CancellationContext cancellation) {
            Queue<byte[]> pending = new ArrayDeque<>(script);
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    while (n-- > 0 && !pending.isEmpty() && !cancellation.cancelled()) {
                        subscriber.onNext(pending.poll());
                    }
                    if (pending.isEmpty() && !staysSilentAfterScript && !cancellation.cancelled()) {
                        cancellation.latch(CancellationContext.Cause.CLOSED);
                        subscriber.onComplete();
                    }
                }

                @Override
                public void cancel() {
                    // the real publisher latches CLOSED only when no cause won first
                }
            });
        }
    }

    /**
     * Port stub shaped like a reset connection: it delivers one chunk and then errors the
     * subscriber without latching any cause first, exactly the raw mid-body transport failure
     * the relay must turn into the run's terminal cause.
     */
    private static final class ErroringPort implements StreamProbePort {

        private final byte[] chunk;

        ErroringPort(byte[] chunk) {
            this.chunk = chunk.clone();
        }

        @Override
        public ProbeStream open(URI url, CancellationContext cancellation, Duration idleTimeout, Duration wallTimeout) {
            return new ProbeStream(
                    subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                        @Override
                        public void request(long n) {
                            if (n > 0) {
                                subscriber.onNext(chunk);
                                subscriber.onError(new IOException("connection reset"));
                            }
                        }

                        @Override
                        public void cancel() {
                            // nothing to release: the stub owns no transport
                        }
                    }),
                    () -> {});
        }
    }
}

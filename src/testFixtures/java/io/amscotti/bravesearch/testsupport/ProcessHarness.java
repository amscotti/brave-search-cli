package io.amscotti.bravesearch.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Robust child-process runner for JVM process tests.
 *
 * <p>{@link #launch} starts the command with the given environment additions layered over the
 * inherited one, pipes the provided stdin bytes in, and drains stdout and stderr concurrently on
 * dedicated threads as raw byte arrays, so a child flooding both streams cannot deadlock on a
 * full pipe. A hard deadline (sixty seconds unless overridden) bounds the run: on expiry the
 * whole process tree is killed — descendants first, then the process itself — inside a
 * {@code finally} block, so no path out of {@code launch} can leak a live child.
 *
 * <p>On POSIX, {@link Process#waitFor()} reports a signal death as {@code 128 + signum}: SIGINT
 * decodes as 130, SIGTERM as 143, and the harness's forced SIGKILL cleanup as 137.
 *
 * <p>Environment values are a leak risk in reports: they never appear in any message this class
 * produces, and {@link ProcessResult#toString()} renders the command and exit status only.
 */
public final class ProcessHarness {

    /** Deadline applied when no explicit one is given. */
    public static final Duration DEFAULT_DEADLINE = Duration.ofSeconds(60);

    /** Grace period for the drain threads to observe EOF once the child is gone. */
    private static final Duration DRAIN_JOIN_GRACE = Duration.ofSeconds(10);

    public ProcessResult launch(List<String> command, Map<String, String> environment, byte[] stdin)
            throws IOException {
        return launch(command, environment, stdin, DEFAULT_DEADLINE);
    }

    /**
     * Starts the child with the given environment layered over the inherited one after the named
     * variables were removed from it, so tests can scrub inherited credentials from the child's
     * view before adding their own values. Removal happens before layering, so an added value
     * always wins over a scrubbed name.
     */
    public ProcessResult launch(
            List<String> command, Map<String, String> environment, byte[] stdin, Set<String> removedEnvironment)
            throws IOException {
        return launch(command, environment, stdin, DEFAULT_DEADLINE, removedEnvironment);
    }

    /**
     * Starts the command without waiting for it, for tests that interact with the child while it
     * runs: the returned session exposes the live stdout pipe, the child's process id, POSIX
     * signal delivery through {@code /bin/kill}, and the drained stderr.
     *
     * <p>See {@link #start(List, Map, Set)} for the environment rules.
     */
    public Session start(List<String> command, Map<String, String> environment) throws IOException {
        return start(command, environment, Set.of());
    }

    /**
     * Starts the command without waiting for it, with the named inherited variables removed
     * before the additions are layered on, so a live child sees the same scrubbed environment
     * {@link #launch(List, Map, byte[], Set)} produces.
     *
     * <p>The child's stdin is closed immediately and stderr is drained on a virtual thread, so
     * the only pipe the test owns is stdout; closing that pipe early is how broken-pipe
     * scenarios are staged. The session must be closed to guarantee cleanup of a child that
     * outlives the test.
     */
    public Session start(List<String> command, Map<String, String> environment, Set<String> removedEnvironment)
            throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder
                .environment()
                .keySet()
                .removeAll(Objects.requireNonNull(removedEnvironment, "removedEnvironment"));
        processBuilder.environment().putAll(environment);
        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException failure) {
            throw new IOException("failed to launch " + command, failure);
        }
        process.getOutputStream().close();
        ByteArrayOutputStream stderrSink = new ByteArrayOutputStream();
        Thread stderrDrain = drainThread("session-stderr", process.getErrorStream(), stderrSink);
        stderrDrain.start();
        return new Session(process, stderrDrain, stderrSink, command);
    }

    /** A started child the test interacts with while it runs; closing it never leaks the child. */
    public static final class Session implements AutoCloseable {

        private final Process process;
        private final Thread stderrDrain;
        private final ByteArrayOutputStream stderrSink;
        private final List<String> command;
        private boolean closed;

        private Session(
                Process process, Thread stderrDrain, ByteArrayOutputStream stderrSink, List<String> command) {
            this.process = process;
            this.stderrDrain = stderrDrain;
            this.stderrSink = stderrSink;
            this.command = List.copyOf(command);
        }

        /** The child's operating-system process id, for {@code /bin/kill}. */
        public long pid() {
            return process.pid();
        }

        /** The child's live stdout pipe; closing it stages a broken pipe on the child's side. */
        public InputStream stdout() {
            return process.getInputStream();
        }

        /** The stderr bytes drained so far; complete once the child has exited. */
        public byte[] stderr() {
            if (!process.isAlive()) {
                try {
                    stderrDrain.join(DRAIN_JOIN_GRACE.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            synchronized (stderrSink) {
                return stderrSink.toByteArray();
            }
        }

        /** Waits for the child to exit on its own within {@code timeout}. */
        public boolean awaitExit(Duration timeout) throws InterruptedException {
            return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        /** The child's exit status; POSIX signal deaths decode as 128 plus the signal number. */
        public int exitValue() {
            return process.exitValue();
        }

        /**
         * Delivers {@code signal} (for example {@code INT} or {@code TERM}) to the child through
         * {@code /bin/kill}, so the kernel path a real interrupt takes is the one exercised; a
         * child that already exited has no one left to signal and the call is a no-op —
         * including the race where the child exits between the liveness check and the kill,
         * which the kernel reports as a failed kill of a gone process. Between the liveness
         * check and the kill a reaped pid could in principle be reused by an unrelated
         * process — a residual risk the check narrows but cannot eliminate.
         */
        public void signal(String signal) throws IOException, InterruptedException {
            if (!process.isAlive()) {
                return;
            }
            Process killer = new ProcessBuilder("/bin/kill", "-" + signal, Long.toString(process.pid()))
                    .start();
            if (!killer.waitFor(10, TimeUnit.SECONDS)) {
                throw new IOException("kill -" + signal + " " + process.pid() + " timed out for " + command);
            }
            if (killer.exitValue() != 0 && process.isAlive()) {
                // only a failed kill of a still-live child is a harness failure; a gone
                // child is the documented no-op
                throw new IOException("kill -" + signal + " " + process.pid() + " failed for " + command);
            }
        }

        /** Destroys the whole child tree if it is still alive and stops the drains. */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            awaitDestruction(process);
            joinDrain(stderrDrain);
        }
    }

    public ProcessResult launch(List<String> command, Map<String, String> environment, byte[] stdin, Duration deadline)
            throws IOException {
        return launch(command, environment, stdin, deadline, Set.of());
    }

    public ProcessResult launch(
            List<String> command,
            Map<String, String> environment,
            byte[] stdin,
            Duration deadline,
            Set<String> removedEnvironment) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder
                .environment()
                .keySet()
                .removeAll(Objects.requireNonNull(removedEnvironment, "removedEnvironment"));
        processBuilder.environment().putAll(environment);
        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException failure) {
            throw new IOException("failed to launch " + command, failure);
        }

        ByteArrayOutputStream stdoutSink = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrSink = new ByteArrayOutputStream();
        Thread stdoutDrain = drainThread("stdout", process.getInputStream(), stdoutSink);
        Thread stderrDrain = drainThread("stderr", process.getErrorStream(), stderrSink);
        stdoutDrain.start();
        stderrDrain.start();
        if (stdin.length > 0) {
            feederThread(process.getOutputStream(), stdin).start();
        } else {
            // the child's stdin must still reach EOF: a reader waiting for end-of-input
            // would otherwise block until the deadline and be misreported as a timeout
            process.getOutputStream().close();
        }

        boolean exited = false;
        try {
            exited = process.waitFor(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            awaitDestruction(process);
        }
        joinDrain(stdoutDrain);
        joinDrain(stderrDrain);
        return new ProcessResult(
                process.exitValue(), stdoutSink.toByteArray(), stderrSink.toByteArray(), !exited, command);
    }

    /** One finished child run: captured streams, exit decoding, and the command for reports. */
    public record ProcessResult(int exitStatus, byte[] stdout, byte[] stderr, boolean timedOut, List<String> command) {

        public ProcessResult {
            command = List.copyOf(command);
            stdout = stdout.clone();
            stderr = stderr.clone();
        }

        /**
         * Command and exit status only: captured output is bulky and environment values must
         * never surface in reports, so neither is rendered.
         */
        @Override
        public String toString() {
            return "ProcessResult[command=" + command + ", exitStatus=" + exitStatus + "]";
        }
    }

    /**
     * Force-kills the process tree and waits for the process to be gone. An interrupt during
     * the wait re-asserts the kill and keeps waiting, so no interruption can leave this method
     * with the child still alive.
     */
    private static void awaitDestruction(Process process) {
        boolean interrupted = false;
        while (process.isAlive()) {
            destroyProcessTree(process);
            try {
                process.waitFor();
            } catch (InterruptedException duringWait) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void destroyProcessTree(Process process) {
        // every descendant, deepest first: killing a parent before its own children would
        // reparent the survivors out of this walk, leaving them alive though unreachable
        process.descendants()
                .sorted(Comparator.comparingInt(
                                (ProcessHandle descendant) -> depthBelow(descendant, process.pid()))
                        .reversed())
                .forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /**
     * The descendant's distance below the direct children of {@code rootPid}: a direct child
     * scores zero, a grandchild one, and so on. A chain that no longer reaches the root —
     * an already reparented orphan — still counts its remaining steps and is killed anyway.
     */
    private static int depthBelow(ProcessHandle handle, long rootPid) {
        int depth = 0;
        for (Optional<ProcessHandle> parent = handle.parent();
                parent.isPresent() && parent.get().pid() != rootPid;
                parent = parent.get().parent()) {
            depth++;
        }
        return depth;
    }

    /**
     * Drains the stream into the sink chunk by chunk, publishing each write under the sink's
     * monitor so concurrent readers always observe a consistent snapshot.
     */
    private static Thread drainThread(String role, InputStream stream, ByteArrayOutputStream sink) {
        return Thread.ofVirtual()
                .name("process-harness-" + role)
                .unstarted(
                        () -> {
                            try (stream) {
                                byte[] buffer = new byte[8192];
                                int read;
                                while ((read = stream.read(buffer)) != -1) {
                                    synchronized (sink) {
                                        sink.write(buffer, 0, read);
                                    }
                                }
                            } catch (IOException failedDrain) {
                                // a killed child closes its pipes abruptly; drained bytes are kept
                            }
                        });
    }

    private static Thread feederThread(OutputStream processStdin, byte[] payload) {
        return Thread.ofVirtual()
                .name("process-harness-stdin")
                .unstarted(
                        () -> {
                            try (processStdin) {
                                processStdin.write(payload);
                            } catch (IOException failedFeed) {
                                // the child exited or never read its stdin; its pipes close on exit
                            }
                        });
    }

    private static void joinDrain(Thread drain) {
        try {
            drain.join(DRAIN_JOIN_GRACE.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}

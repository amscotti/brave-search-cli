package io.amscotti.bravesearch.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Runs one child command with its standard streams attached to a real pseudo-terminal,
 * allocated by Python's {@code pty} module, and reports the terminal-visible output bytes
 * and the child's own exit status (a signal death decodes as 128 plus the signal number).
 *
 * <p>Two stream layouts prove the ANSI decision's two halves: the default attaches stdin,
 * stdout, and stderr to the terminal slave, so the child process provably has a console
 * that is a terminal; the stdout-pipe layout keeps stdin on the slave but routes stdout
 * through a plain pipe, so a console can exist while stdout is not a terminal — the case
 * where color must stay off even though a console is attached. Only the pipe's bytes are
 * reported in that layout, unmodified by the terminal's line discipline.
 *
 * <p>The environment is the harness process's own with the given names removed and the
 * given values layered on, mirroring {@link ProcessHarness}: environment values never
 * appear in any message this class produces, and {@link PtyResult#toString()} renders the
 * exit status only.
 *
 * <p>The helper's own stdout and stderr are drained on dedicated threads while it runs, so
 * a run whose captured terminal bytes exceed an operating-system pipe buffer still
 * completes. A hard deadline bounds the whole run: on expiry the slave-side process group —
 * the child leads it, because {@code pty.fork} makes it a session leader — is SIGKILLed
 * before the failure is reported, so a child that ignores the terminal's hangup signal
 * cannot outlive the harness as an orphan, even when the expiry lands before the helper has
 * reported the child's pid. The deadline given to a run is the deadline everywhere: the
 * helper's own drain bound derives from it, so an expiry is always this harness's
 * {@link IOException} rather than a run the helper silently resolved on its own.
 */
public final class PtyHarness {

    /**
     * The Python allocation helper, embedded so the harness needs nothing beyond a
     * {@code python3} on the path. It forks the command onto the pty slave — optionally
     * routing the child's stdout through a plain pipe — reports the child's pid through the
     * pid file so the harness can kill the child's process group if the deadline passes,
     * drains until the child exits or the drain bound given as {@code --deadline-millis}
     * passes, then prints the captured stdout bytes to its own stdout and exits with the
     * child's decoded wait status. The bound comes from the harness's own deadline plus a
     * margin, so the harness — which reports an expiry as a failure — always observes its
     * deadline before the helper's backstop does.
     */
    private static final String HELPER = """
            import os, pty, select, signal, sys, time

            environment = {}
            remove = []
            stdout_through_pipe = False
            pid_file = None
            deadline_millis = None
            command = None
            arguments = sys.argv[1:]
            index = 0
            while index < len(arguments):
                if arguments[index] == '--env':
                    index += 1
                    name, value = arguments[index].split('=', 1)
                    environment[name] = value
                elif arguments[index] == '--remove':
                    index += 1
                    remove.append(arguments[index])
                elif arguments[index] == '--stdout-pipe':
                    stdout_through_pipe = True
                elif arguments[index] == '--pid-file':
                    index += 1
                    pid_file = arguments[index]
                elif arguments[index] == '--deadline-millis':
                    index += 1
                    deadline_millis = int(arguments[index])
                elif arguments[index] == '--':
                    command = arguments[index + 1:]
                    break
                index += 1
            if command is None:
                sys.stderr.write('pty helper: no command after --')
                sys.exit(99)
            if deadline_millis is None:
                sys.stderr.write('pty helper: no --deadline-millis drain bound given')
                sys.exit(98)

            for name in remove:
                os.environ.pop(name, None)
            os.environ.update(environment)

            pipe_read, pipe_write = os.pipe() if stdout_through_pipe else (None, None)
            pid, master = pty.fork()
            if pid == 0:
                if stdout_through_pipe:
                    os.dup2(pipe_write, 1)
                    os.close(pipe_read)
                    os.close(pipe_write)
                try:
                    os.execvp(command[0], command)
                except OSError:
                    os._exit(127)

            if stdout_through_pipe:
                os.close(pipe_write)

            if pid_file is not None:
                with open(pid_file, 'w') as pid_out:
                    pid_out.write(str(pid))

            captured = []
            sources = {master}
            if stdout_through_pipe:
                sources.add(pipe_read)
            deadline = time.time() + deadline_millis / 1000.0
            while sources and time.time() < deadline:
                ready, _, _ = select.select(list(sources), [], [], 1.0)
                for source in ready:
                    try:
                        data = os.read(source, 65536)
                    except OSError:
                        data = b''
                    if not data:
                        os.close(source)
                        sources.discard(source)
                    elif source is pipe_read or not stdout_through_pipe:
                        captured.append(data)
            if sources:
                try:
                    os.kill(pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass

            status = 0
            try:
                _, status = os.waitpid(pid, 0)
            except ChildProcessError:
                pass
            if os.WIFEXITED(status):
                result = os.WEXITSTATUS(status)
            elif os.WIFSIGNALED(status):
                result = 128 + os.WTERMSIG(status)
            else:
                result = 99
            sys.stdout.buffer.write(b''.join(captured))
            sys.stdout.buffer.flush()
            sys.exit(result)
            """;

    /** How long the whole child run may take before the harness gives up on it. */
    public static final Duration DEADLINE = Duration.ofSeconds(60);

    /**
     * Margin added to the run's deadline when the helper's own drain bound is set: the
     * helper's bound is a backstop for a wedged harness, never a competing timer, so an
     * expiry is always this harness's failure rather than a silently shortened run.
     */
    private static final Duration HELPER_BOUND_MARGIN = Duration.ofSeconds(10);

    /**
     * Grace after the deadline's expiry: how long the helper may take to report the child's
     * pid and then to exit on its own once the slave-side group is killed, before the helper
     * itself is forced down.
     */
    private static final Duration EXPIRY_GRACE = Duration.ofSeconds(5);

    private final String python;

    /** @param python the python 3 executable that allocates the pseudo-terminal */
    public PtyHarness(String python) {
        this.python = Objects.requireNonNull(python, "python");
    }

    /** The first python 3 executable on the path, or null when none is available. */
    public static String python3OnPath() {
        for (String candidate : List.of("python3", "python")) {
            String found = probePython(candidate);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String probePython(String candidate) {
        Process probe;
        try {
            probe = new ProcessBuilder(candidate, "--version").start();
        } catch (IOException unavailable) {
            return null;
        }
        try (var out = probe.getInputStream();
                var err = probe.getErrorStream();
                var in = probe.getOutputStream()) {
            in.close();
            String banner = new String(out.readAllBytes(), StandardCharsets.UTF_8)
                    + new String(err.readAllBytes(), StandardCharsets.UTF_8);
            if (probe.waitFor(10, TimeUnit.SECONDS) && probe.exitValue() == 0 && banner.contains("Python 3")) {
                return candidate;
            }
        } catch (IOException | InterruptedException failed) {
            if (failed instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        return null;
    }

    /**
     * Runs {@code command} under a fresh pseudo-terminal with the environment additions
     * layered over the inherited one after the named variables were removed from it.
     *
     * @param stdoutThroughPipe whether stdout leaves the terminal through a plain pipe
     */
    public PtyResult run(
            List<String> command,
            Map<String, String> environment,
            Set<String> removedEnvironment,
            boolean stdoutThroughPipe)
            throws IOException, InterruptedException {
        return run(command, environment, removedEnvironment, stdoutThroughPipe, DEADLINE);
    }

    /**
     * The same run with an explicit deadline, so harness tests exercise the expiry path
     * quickly; every shared scenario keeps the default deadline. The deadline governs the
     * run end to end — the helper's own drain bound derives from it plus a backstop margin —
     * so an expiry is always reported here as an {@link IOException}, whatever the duration.
     */
    PtyResult run(
            List<String> command,
            Map<String, String> environment,
            Set<String> removedEnvironment,
            boolean stdoutThroughPipe,
            Duration deadline)
            throws IOException, InterruptedException {
        Path helper = Files.createTempFile("brave-pty-helper", ".py");
        Path childPidFile = Files.createTempFile("brave-pty-child-pid", ".txt");
        try {
            Files.writeString(helper, HELPER, StandardCharsets.UTF_8);
            List<String> argv = new ArrayList<>();
            argv.add(python);
            argv.add(helper.toString());
            for (Map.Entry<String, String> entry : environment.entrySet()) {
                argv.add("--env");
                argv.add(entry.getKey() + "=" + entry.getValue());
            }
            for (String name : removedEnvironment) {
                argv.add("--remove");
                argv.add(name);
            }
            if (stdoutThroughPipe) {
                argv.add("--stdout-pipe");
            }
            argv.add("--pid-file");
            argv.add(childPidFile.toString());
            argv.add("--deadline-millis");
            argv.add(Long.toString(deadline.plus(HELPER_BOUND_MARGIN).toMillis()));
            argv.add("--");
            argv.addAll(command);
            ProcessBuilder builder = new ProcessBuilder(argv);
            builder.environment().keySet().removeAll(removedEnvironment);
            builder.environment().putAll(environment);
            Process child = builder.start();
            ByteArrayOutputStream stdoutSink = new ByteArrayOutputStream();
            ByteArrayOutputStream stderrSink = new ByteArrayOutputStream();
            Thread stdoutDrain = drainThread("stdout", child.getInputStream(), stdoutSink);
            Thread stderrDrain = drainThread("stderr", child.getErrorStream(), stderrSink);
            stdoutDrain.start();
            stderrDrain.start();
            if (!child.waitFor(deadline.toMillis(), TimeUnit.MILLISECONDS)) {
                killSlaveProcessGroup(childPidFile, child, EXPIRY_GRACE);
                throw new IOException("the pty child outlived its deadline: " + command.get(0));
            }
            joinDrain(stdoutDrain);
            joinDrain(stderrDrain);
            return new PtyResult(child.exitValue(), stdoutSink.toByteArray(), stderrSink.toByteArray());
        } finally {
            Files.deleteIfExists(helper);
            Files.deleteIfExists(childPidFile);
        }
    }

    /**
     * SIGKILLs the process group the helper reported for the slave-side child: {@code pty.fork}
     * makes the child a session leader, so its pid names its group and every descendant that
     * stayed in the group dies with it — including one that ignores the SIGHUP a closing
     * master would otherwise deliver. The deadline can expire before the helper has reported
     * the pid — it may still be starting — so the pid file is awaited through the grace
     * period instead of read once, and it is read once more after the helper is finally
     * forced down: forcing only the helper down would leave a hangup-ignoring slave-side
     * child alive with its pid never acted on.
     */
    private static void killSlaveProcessGroup(Path childPidFile, Process helper, Duration grace)
            throws InterruptedException {
        long pid = awaitReportedPid(childPidFile, helper, grace);
        if (pid > 0) {
            killProcessGroup(pid);
        }
        // the killed child ends the master side, so a healthy helper exits on its own; the
        // forced kill covers a helper wedged for any other reason
        if (!helper.waitFor(grace.toMillis(), TimeUnit.MILLISECONDS)) {
            helper.destroyForcibly();
            helper.waitFor();
        }
        if (pid <= 0) {
            long latePid = reportedPid(childPidFile);
            if (latePid > 0) {
                killProcessGroup(latePid);
            }
        }
    }

    /**
     * The pid the helper reports for the slave-side child, polled until it appears, the
     * helper exits without ever reporting one, or the bound passes: negative when no pid was
     * reported in time.
     */
    private static long awaitReportedPid(Path childPidFile, Process helper, Duration bound)
            throws InterruptedException {
        long pid = reportedPid(childPidFile);
        long end = System.nanoTime() + bound.toNanos();
        while (pid <= 0 && helper.isAlive() && System.nanoTime() < end) {
            Thread.sleep(25);
            pid = reportedPid(childPidFile);
        }
        return pid;
    }

    /** The pid the pid file names, or negative while the helper has not reported one. */
    private static long reportedPid(Path childPidFile) {
        try {
            return Long.parseLong(Files.readString(childPidFile, StandardCharsets.UTF_8).trim());
        } catch (IOException | NumberFormatException unreported) {
            return -1;
        }
    }

    private static void killProcessGroup(long pid) throws InterruptedException {
        try {
            // terminate option parsing before the negative process-group id: procps kill
            // can otherwise interpret its leading digits as options and signal other groups
            Process killer = new ProcessBuilder("/bin/kill", "-9", "--", "-" + pid).start();
            if (!killer.waitFor(10, TimeUnit.SECONDS)) {
                killer.destroyForcibly();
            }
        } catch (IOException failedKill) {
            // a failed kill of a possibly already-gone group must not mask the deadline
        }
    }

    /**
     * Drains the helper stream into the sink chunk by chunk, so the helper's captured bytes
     * — larger than an operating-system pipe buffer — can never wedge it mid-write.
     */
    private static Thread drainThread(String role, InputStream stream, ByteArrayOutputStream sink) {
        return Thread.ofVirtual()
                .name("pty-harness-" + role)
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
                                // a killed helper closes its pipes abruptly; drained bytes are kept
                            }
                        });
    }

    private static void joinDrain(Thread drain) {
        try {
            drain.join(Duration.ofSeconds(10).toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** One finished pseudo-terminal run: the terminal-visible bytes and the child's status. */
    public record PtyResult(int exitStatus, byte[] stdout, byte[] stderr) {

        public PtyResult {
            stdout = stdout.clone();
            stderr = stderr.clone();
        }

        /** Exit status only: captured output is bulky and environment values never surface. */
        @Override
        public String toString() {
            return "PtyResult[exitStatus=" + exitStatus + "]";
        }
    }
}

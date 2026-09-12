package io.amscotti.bravesearch.adapter.cli.command;

import io.amscotti.bravesearch.adapter.cli.option.SimpleDurationConverter;
import io.amscotti.bravesearch.adapter.cli.presentation.DiagnosticsSink;
import io.amscotti.bravesearch.application.port.out.StreamProbePort;
import io.amscotti.bravesearch.application.port.out.StreamProbePort.ProbeStream;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.CancellationRegistry;
import io.amscotti.bravesearch.application.stream.DeadlineWatchdog;
import io.amscotti.bravesearch.application.stream.StreamBodyRelay;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

/**
 * Hidden streaming lifecycle probe: relays the bytes of one loopback event stream to stdout and
 * turns the run's terminal cause into its exit code — interrupt as 130, a broken stdout pipe as
 * a silent 0, a clean end of stream as 0, and deadline, subscriber, or transport failures as 6
 * with exactly one diagnostic line on stderr.
 *
 * <p>The command drives presentation and run policy only: the bytes come from the injected
 * {@link StreamProbePort} transport, the sink and deadline policies come from the application's
 * streaming machinery, both budgets read the injected {@link Clock} — the single
 * time-source seam of the process, so a test can pin time instead of waiting it — and the
 * diagnostics channel is built by the injected sink factory so the
 * command itself never constructs a presentation adapter, and the process-wide
 * {@link CancellationRegistry} receives the run's {@link CancellationContext} before the
 * exchange opens, so an interrupt reaching the bootstrap can latch the cause even while the
 * connect is still in flight.
 */
@Command(
        name = StreamProbeCommand.NAME,
        hidden = true,
        description = "Relay one loopback event stream to stdout; probes interrupt and broken-pipe lifecycle.")
public final class StreamProbeCommand implements Callable<Integer> {

    /** Registration name of the hidden probe under the root command. */
    public static final String NAME = "stream-probe";

    private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "localhost", "::1");

    private final StreamProbePort streams;
    private final CancellationRegistry registry;
    private final Function<Writer, DiagnosticsSink> diagnosticsFactory;
    private final Clock clock;

    @Spec CommandSpec spec;

    @Option(
            names = "--url",
            required = true,
            description = "Literal loopback http URL to stream; every other destination is refused before connecting.")
    private String url;

    @Option(
            names = "--idle-timeout",
            converter = SimpleDurationConverter.class,
            description = "Maximum silence between delivered bytes, for example 1s or 250ms.")
    private Duration idleTimeout;

    @Option(
            names = "--wall-timeout",
            converter = SimpleDurationConverter.class,
            description = "Total wall-clock budget of the run, for example 5s or 1m.")
    private Duration wallTimeout;

    public StreamProbeCommand(
            StreamProbePort streams,
            CancellationRegistry registry,
            Function<Writer, DiagnosticsSink> diagnosticsFactory,
            Clock clock) {
        this.streams = Objects.requireNonNull(streams, "streams");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.diagnosticsFactory = Objects.requireNonNull(diagnosticsFactory, "diagnosticsFactory");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Integer call() {
        URI target = validatedUrl();
        requirePositiveBudget(idleTimeout, "--idle-timeout");
        requirePositiveBudget(wallTimeout, "--wall-timeout");
        CommandLine commandLine = spec.commandLine();
        DiagnosticsSink diagnostics = diagnosticsFactory.apply(commandLine.getErr());
        CancellationContext cancellation = new CancellationContext();
        Runnable detach = registry.register(cancellation);
        try (ProbeStream stream = streams.open(target, cancellation, idleTimeout, wallTimeout)) {
            return relay(stream, cancellation, commandLine.getOut(), diagnostics);
        } catch (IOException | InterruptedException openingFailure) {
            if (openingFailure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // a cause that already won — an interrupt latched during the connect window —
            // decides the exit code; otherwise the failed open is the run's transport
            // failure, named by a fixed redacted diagnostic — the JDK's own IOException
            // messages embed addresses and URIs, so none of that text may travel
            if (cancellation.latch(CancellationContext.Cause.TRANSPORT_FAILURE)) {
                diagnostics.emit(NAME + ": cannot open stream: the streaming connection failed to open");
                return CancellationContext.Cause.TRANSPORT_FAILURE.exitCode();
            }
            return exitCodeFor(cancellation, diagnostics);
        } finally {
            detach.run();
        }
    }

    private URI validatedUrl() {
        URI target;
        try {
            target = URI.create(url);
        } catch (RuntimeException malformed) {
            throw refused("not a parsable URI");
        }
        if (!"http".equalsIgnoreCase(target.getScheme())) {
            throw refused("only plain http is streamed");
        }
        String host = target.getHost();
        if (host == null) {
            throw refused("no host in URL");
        }
        String literal = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (!LOOPBACK_HOSTS.contains(literal.toLowerCase(Locale.ROOT))) {
            throw refused("only literal loopback hosts 127.0.0.1, localhost, and [::1] are streamed");
        }
        if (target.getUserInfo() != null) {
            throw refused("URLs carrying user information are refused");
        }
        return target;
    }

    private ParameterException refused(String reason) {
        return new ParameterException(spec.commandLine(), NAME + ": --url " + reason + ", refusing: " + url);
    }

    /** Defense in depth: the option converter already rejects non-positive budgets before this. */
    private void requirePositiveBudget(Duration budget, String option) {
        if (budget != null && (budget.isZero() || budget.isNegative())) {
            throw new ParameterException(spec.commandLine(), NAME + ": " + option + " must be greater than zero");
        }
    }

    private int relay(ProbeStream stream, CancellationContext cancellation, PrintWriter out, DiagnosticsSink diagnostics) {
        AtomicReference<Instant> lastProgress = new AtomicReference<>(clock.instant());
        try (DeadlineWatchdog watchdog =
                DeadlineWatchdog.armed(stream, cancellation, idleTimeout, wallTimeout, lastProgress, clock)) {
            stream.bytes().subscribe(new StreamBodyRelay(out, cancellation, lastProgress, clock));
            cancellation.awaitUninterruptibly();
        }
        return exitCodeFor(cancellation, diagnostics);
    }

    /** The exit status is the terminal cause's own code; only failure causes explain themselves. */
    private int exitCodeFor(CancellationContext cancellation, DiagnosticsSink diagnostics) {
        CancellationContext.Cause cause = cancellation.cause().orElse(CancellationContext.Cause.CLOSED);
        return switch (cause) {
            case SIGINT, SIGTERM, BROKEN_PIPE, CLOSED -> cause.exitCode();
            case IDLE_TIMEOUT, WALL_TIMEOUT, SUBSCRIBER_FAILURE, TRANSPORT_FAILURE -> {
                diagnostics.emit(NAME + ": stream aborted: " + cause);
                yield cause.exitCode();
            }
        };
    }
}

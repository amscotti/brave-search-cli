package io.amscotti.bravesearch.application.stream;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Wall-clock enforcement of the idle and total budgets of one streaming run, for the window
 * where the body publisher cannot see time pass: its reader evaluates both deadlines only
 * between reads, so a read parked inside a silent body needs this side clock to latch the
 * terminal cause and close the exchange — which is exactly what unblocks such a read.
 *
 * <p>Both budgets read the injected {@link Clock}, the same time-source seam the body publisher
 * uses, so tests advance time by advancing the clock and production runs share one system
 * clock. Progress is the instant of the last delivered chunk, published by the writer relay
 * through the shared reference.
 *
 * <p>When a budget elapses, the watchdog latches the matching cause on the shared {@link
 * CancellationContext} and closes the exchange — unconditionally, because the publisher that
 * won the same deadline race latches but never closes, and only this close releases the run's
 * registry entry, reader executor, and transport; closing is idempotent, so a winner that
 * already closed pays nothing. The worker is a daemon thread ticking a few dozen times per
 * budget — one distinguishably named thread per armed instance — so an armed watchdog costs
 * nothing while a run streams and never keeps a process alive.
 */
public final class DeadlineWatchdog implements AutoCloseable {

    private static final long TICK_NANOS = TimeUnit.MILLISECONDS.toNanos(20);

    private final AutoCloseable exchange;
    private final CancellationContext cancellation;
    private final Duration idleTimeout;
    private final Duration wallTimeout;
    private final AtomicReference<Instant> lastProgress;
    private final Clock clock;
    private final Instant startedAt;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread worker;

    private DeadlineWatchdog(
            AutoCloseable exchange,
            CancellationContext cancellation,
            Duration idleTimeout,
            Duration wallTimeout,
            AtomicReference<Instant> lastProgress,
            Clock clock,
            boolean armed) {
        this.exchange = exchange;
        this.cancellation = cancellation;
        this.idleTimeout = idleTimeout;
        this.wallTimeout = wallTimeout;
        this.lastProgress = lastProgress;
        this.clock = clock;
        this.startedAt = clock.instant();
        this.worker = armed ? watchdogWorker() : null;
        if (this.worker != null) {
            this.worker.start();
        }
    }

    /**
     * The daemon worker of an armed watchdog, named by the JVM's own thread identity so every
     * concurrent watchdog stays distinguishable in a thread dump without any shared counter.
     */
    private Thread watchdogWorker() {
        Thread worker = Thread.ofPlatform().daemon(true).unstarted(this::enforce);
        worker.setName("stream-deadline-watchdog-" + worker.threadId());
        return worker;
    }

    /**
     * Arms both budgets from now; a {@code null} budget disables its side, and with both null
     * the watchdog stays unarmed and costs no thread.
     *
     * @param exchange     the run's exchange, closed when a watchdog cause wins
     * @param cancellation the run's terminal-cause latch
     * @param idleTimeout  maximum silence between delivered bytes, or {@code null}
     * @param wallTimeout  total budget of the run, or {@code null}
     * @param lastProgress the relay's progress clock, refreshed on every delivered chunk
     * @param clock        the time source both budgets read
     */
    public static DeadlineWatchdog armed(
            AutoCloseable exchange,
            CancellationContext cancellation,
            Duration idleTimeout,
            Duration wallTimeout,
            AtomicReference<Instant> lastProgress,
            Clock clock) {
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(lastProgress, "lastProgress");
        Objects.requireNonNull(clock, "clock");
        boolean needed = idleTimeout != null || wallTimeout != null;
        return new DeadlineWatchdog(exchange, cancellation, idleTimeout, wallTimeout, lastProgress, clock, needed);
    }

    private void enforce() {
        while (running.get()) {
            Instant now = clock.instant();
            // an elapsed budget fires even when the latch is already decided — the
            // publisher may have won the same race without ever closing the exchange
            if (idleTimeout != null && Duration.between(lastProgress.get(), now).compareTo(idleTimeout) >= 0) {
                fire(CancellationContext.Cause.IDLE_TIMEOUT);
                return;
            }
            if (wallTimeout != null && Duration.between(startedAt, now).compareTo(wallTimeout) >= 0) {
                fire(CancellationContext.Cause.WALL_TIMEOUT);
                return;
            }
            if (cancellation.cancelled()) {
                return;
            }
            LockSupport.parkNanos(TICK_NANOS);
        }
    }

    /**
     * Latches the elapsed cause — first-wins, so a publisher that already latched keeps its
     * cause — and closes the exchange on every path, because the latch decides the run's
     * cause while this close decides its cleanup, and only together do they leave nothing
     * behind.
     */
    private void fire(CancellationContext.Cause cause) {
        cancellation.latch(cause);
        try {
            exchange.close();
        } catch (Exception closeFailure) {
            // closing is the unblock path; a failing close still left the cause latched
        }
    }

    /** Stops the worker; the run's own terminal state has already been decided by then. */
    @Override
    public void close() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
        }
    }
}

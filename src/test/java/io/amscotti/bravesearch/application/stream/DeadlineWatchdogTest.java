package io.amscotti.bravesearch.application.stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * Deadline contracts of the watchdog: both budgets read the injected clock — the same time
 * seam the body publisher uses — so elapsed budgets fire from the clock alone, and concurrent
 * watchdogs run on distinguishable threads.
 */
final class DeadlineWatchdogTest {

    @Test
    void idleBudgetFiresFromTheInjectedClock() throws Exception {
        MutableClock clock = new MutableClock();
        CancellationContext cancellation = new CancellationContext();
        AtomicReference<Instant> lastProgress = new AtomicReference<>(clock.instant());
        AtomicBoolean closed = new AtomicBoolean();
        try (DeadlineWatchdog ignored = DeadlineWatchdog.armed(
                () -> closed.set(true), cancellation, Duration.ofSeconds(2), null, lastProgress, clock)) {

            clock.advance(Duration.ofSeconds(3));

            assertTrue(
                    awaitTrue(() -> cancellation.cause().orElse(null) == CancellationContext.Cause.IDLE_TIMEOUT),
                    "advancing the injected clock past the idle budget must latch the cause");
            assertTrue(awaitTrue(closed::get), "the winning watchdog must close the exchange");
        }
    }

    @Test
    void wallBudgetFiresFromTheInjectedClock() throws Exception {
        MutableClock clock = new MutableClock();
        CancellationContext cancellation = new CancellationContext();
        AtomicBoolean closed = new AtomicBoolean();
        try (DeadlineWatchdog ignored = DeadlineWatchdog.armed(
                () -> closed.set(true),
                cancellation,
                null,
                Duration.ofSeconds(1),
                new AtomicReference<>(clock.instant()),
                clock)) {

            clock.advance(Duration.ofSeconds(2));

            assertTrue(
                    awaitTrue(() -> cancellation.cause().orElse(null) == CancellationContext.Cause.WALL_TIMEOUT),
                    "advancing the injected clock past the wall budget must latch the cause");
            assertTrue(awaitTrue(closed::get), "the winning watchdog must close the exchange");
        }
    }

    @Test
    void aLatchThePublisherWonStillLeavesTheFiredWatchdogClosing() throws Exception {
        MutableClock clock = new MutableClock();
        CancellationContext cancellation = new CancellationContext();
        AtomicReference<Instant> lastProgress = new AtomicReference<>(clock.instant());
        AtomicBoolean closed = new AtomicBoolean();
        try (DeadlineWatchdog ignored = DeadlineWatchdog.armed(
                () -> closed.set(true), cancellation, Duration.ofSeconds(2), null, lastProgress, clock)) {

            // the publisher's own deadline check wins the terminal race...
            assertTrue(cancellation.latch(CancellationContext.Cause.IDLE_TIMEOUT));
            // ...and the elapsed budget still stands: the watchdog's fired deadline must
            // close the exchange regardless, or the run's registry entry, reader executor,
            // and transport leak until the process exits
            clock.advance(Duration.ofSeconds(3));

            assertTrue(awaitTrue(closed::get), "a fired deadline closes even when its latch lost the race");
        }
    }

    @Test
    void concurrentWatchdogsRunOnDistinguishableThreads() throws Exception {
        MutableClock clock = new MutableClock();
        CancellationContext cancellation = new CancellationContext();
        AtomicBoolean closed = new AtomicBoolean();
        DeadlineWatchdog first = DeadlineWatchdog.armed(
                () -> closed.set(true), cancellation, Duration.ofHours(1), Duration.ofHours(1),
                new AtomicReference<>(clock.instant()), clock);
        Set<String> names;
        try (first) {
            DeadlineWatchdog second = DeadlineWatchdog.armed(
                    () -> closed.set(true), cancellation, Duration.ofHours(1), Duration.ofHours(1),
                    new AtomicReference<>(clock.instant()), clock);
            try (second) {
                names = Thread.getAllStackTraces().keySet().stream()
                        .map(Thread::getName)
                        .filter(name -> name.startsWith("stream-deadline-watchdog"))
                        .collect(java.util.stream.Collectors.toSet());
            }
        }
        assertTrue(names.size() >= 2, "two armed watchdogs need distinct thread names, saw: " + names);
    }

    private static boolean awaitTrue(BooleanSupplier condition) {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() - deadlineNanos < 0) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    /** Clock whose instant moves only when the test advances it. */
    private static final class MutableClock extends Clock {

        private volatile Instant now = Instant.ofEpochSecond(0);

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }
    }
}

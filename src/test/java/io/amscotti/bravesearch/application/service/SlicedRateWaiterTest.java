package io.amscotti.bravesearch.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.application.stream.CancellationContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * The pacing wait spans only the reset still outstanding when it starts: the upstream reset
 * counts from the observation of the rate-limit headers, so the time a previous page spent
 * reading and processing its body must never be waited a second time, and a reset the body
 * read already outlived contributes no wait at all.
 */
final class SlicedRateWaiterTest {

    @Test
    void outstandingUntilMeasuresTheResetStillOutstandingAgainstTheClock() {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(1_700_000_000));
        SlicedRateWaiter waiter = new SlicedRateWaiter(clock);

        assertEquals(
                Duration.ofSeconds(90),
                waiter.outstandingUntil(clock.instant().plus(Duration.ofSeconds(90))),
                "the outstanding time is measured now, against the waiter's own clock");
        assertEquals(
                Duration.ZERO,
                waiter.outstandingUntil(clock.instant().minus(Duration.ofSeconds(90))),
                "a reset the run already outlived owes nothing");
    }

    @Test
    void anInterruptEndsTheSleepAtOnceWithTheFlagRestoredForTheCaller() throws Exception {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(1_700_000_000));
        SlicedRateWaiter waiter = new SlicedRateWaiter(clock);
        java.util.concurrent.CountDownLatch sleeping = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> thrown = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean flagRestored = new java.util.concurrent.atomic.AtomicBoolean();
        Thread waiting = new Thread(() -> {
            sleeping.countDown();
            try {
                // thirty seconds to wait: only the interrupt can end this promptly
                waiter.await(clock.instant().plus(Duration.ofSeconds(30)), new CancellationContext());
            } catch (InterruptedException interrupted) {
                thrown.set(interrupted);
                flagRestored.set(Thread.currentThread().isInterrupted());
            }
        });
        waiting.start();
        assertTrue(sleeping.await(5, java.util.concurrent.TimeUnit.SECONDS), "the waiter must be asleep");

        long startedAt = System.nanoTime();
        waiting.interrupt();
        waiting.join(java.util.concurrent.TimeUnit.SECONDS.toMillis(5));
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertFalse(waiting.isAlive(), "the interrupt ends the sleep");
        assertTrue(elapsedMs < 2000, "promptly, not after the outstanding window: " + elapsedMs + " ms");
        assertTrue(thrown.get() instanceof InterruptedException, "the interrupt reaches the caller as the checked signal");
        assertTrue(flagRestored.get(), "with the thread's interrupt flag left set");
    }

    @Test
    void waitsOnlyTheResetStillOutstandingWhenTheWaitStarts() throws Exception {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(1_700_000_000));
        SlicedRateWaiter waiter = new SlicedRateWaiter(clock);
        long beforeNanos = System.nanoTime();

        Duration waited = waiter.await(clock.instant().plus(Duration.ofMillis(90)), new CancellationContext());

        long sleptNanos = System.nanoTime() - beforeNanos;
        assertEquals(
                Duration.ofMillis(90), waited, "the reported wait is the outstanding part of the reset");
        assertTrue(
                sleptNanos >= Duration.ofMillis(90).toNanos(),
                "the waiter really sleeps the outstanding reset, not the reset re-anchored at wait start");
    }

    @Test
    void aResetTheElapsedBodyReadAlreadyOutlivedWaitsNothing() throws Exception {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(1_700_000_000));
        SlicedRateWaiter waiter = new SlicedRateWaiter(clock);
        long beforeNanos = System.nanoTime();

        Duration waited = waiter.await(clock.instant().minus(Duration.ofHours(1)), new CancellationContext());

        assertEquals(Duration.ZERO, waited, "a past reset instant contributes no wait");
        assertTrue(
                System.nanoTime() - beforeNanos < Duration.ofSeconds(1).toNanos(),
                "a past reset returns without sleeping");
    }

    @Test
    void aCancelledRunAbortsTheWaitWithoutAnInterruptFlag() {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(1_700_000_000));
        CancellationContext cancelled = new CancellationContext();
        cancelled.latch(CancellationContext.Cause.SIGINT);
        SlicedRateWaiter waiter = new SlicedRateWaiter(clock);

        InterruptedException aborted = assertThrows(
                InterruptedException.class,
                () -> waiter.await(clock.instant().plus(Duration.ofMinutes(1)), cancelled));

        assertFalse(
                Thread.currentThread().isInterrupted(), "a cancellation abort leaves no phantom interrupt flag");
        assertEquals("pagination wait cancelled", aborted.getMessage());
    }

    @Test
    void aSubMillisecondRemainderSkipsWaitingAndReportsZero() throws Exception {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(1_700_000_000));
        SlicedRateWaiter waiter = new SlicedRateWaiter(clock);

        Duration waited = waiter.await(clock.instant().plusNanos(500_000), new CancellationContext());

        assertEquals(Duration.ZERO, waited, "sub-millisecond remainders skip sleeping and report zero");
    }

    @Test
    void aMidWaitCancellationWakesPromptlyWithoutAnInterruptFlag() throws Exception {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(1_700_000_000));
        CancellationContext cancellation = new CancellationContext();
        SlicedRateWaiter waiter = new SlicedRateWaiter(clock);
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> thrown =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean interruptedAfter = new java.util.concurrent.atomic.AtomicBoolean();
        Thread waiting = new Thread(() -> {
            entered.countDown();
            try {
                waiter.await(clock.instant().plus(Duration.ofSeconds(30)), cancellation);
            } catch (InterruptedException aborted) {
                thrown.set(aborted);
                interruptedAfter.set(Thread.currentThread().isInterrupted());
            }
        });
        waiting.start();
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        Thread.sleep(30);

        long startedAt = System.nanoTime();
        cancellation.latch(CancellationContext.Cause.SIGINT);
        waiting.join(java.util.concurrent.TimeUnit.SECONDS.toMillis(5));
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertFalse(waiting.isAlive(), "latching cancellation ends the wait promptly");
        assertTrue(elapsedMs < 2000, "woke promptly instead of waiting 30s: " + elapsedMs + " ms");
        assertTrue(thrown.get() instanceof InterruptedException);
        assertEquals("pagination wait cancelled", thrown.get().getMessage());
        assertFalse(interruptedAfter.get(), "cancellation abort leaves no phantom interrupt flag");
    }

    /** Clock whose instant moves only when the test advances it. */
    private static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

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
    }
}

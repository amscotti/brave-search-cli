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

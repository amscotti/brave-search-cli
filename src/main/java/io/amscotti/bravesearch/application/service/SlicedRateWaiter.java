package io.amscotti.bravesearch.application.service;

import io.amscotti.bravesearch.application.stream.CancellationContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The process pacing waiter of a sequential page walk: waits out the time still
 * outstanding until the paced window's reset instant, sleeping it in short slices and
 * aborting with the interrupt as soon as the run's cancellation latch fires, so a signal
 * never waits out a whole rate window. The remaining time is measured through the injected
 * {@link Clock} — the same time source that stamped the snapshot's observation instant —
 * because the upstream reset counts from the observation of the headers: time the previous
 * page spent reading and processing its body is never waited again, and a reset the body
 * read already outlived waits nothing. A cancellation abort throws without any thread
 * interrupt; a genuine interrupt of the sleep restores the thread's flag before
 * propagating, so the caller can tell the two apart by the flag.
 */
public final class SlicedRateWaiter implements PaginationService.RateWaiter {

    private final Clock clock;

    public SlicedRateWaiter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Duration await(Instant resetAt, CancellationContext cancellation) throws InterruptedException {
        Objects.requireNonNull(resetAt, "resetAt");
        Objects.requireNonNull(cancellation, "cancellation");
        Duration outstanding = Duration.between(clock.instant(), resetAt);
        long remainingMs = outstanding.isNegative() ? 0 : outstanding.toMillis();
        while (remainingMs > 0) {
            if (cancellation.cancelled()) {
                throw new InterruptedException("pagination wait cancelled");
            }
            long slice = Math.min(remainingMs, 50L);
            try {
                Thread.sleep(slice);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            remainingMs -= slice;
        }
        return outstanding.isNegative() ? Duration.ZERO : outstanding;
    }
}

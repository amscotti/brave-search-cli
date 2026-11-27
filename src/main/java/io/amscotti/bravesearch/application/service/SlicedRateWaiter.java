package io.amscotti.bravesearch.application.service;

import io.amscotti.bravesearch.application.stream.CancellationContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The process pacing waiter of a sequential page walk: waits out the time still
 * outstanding until the paced window's reset instant by parking on the run's cancellation
 * latch for the remaining duration, so a signal aborts the wait immediately without polling.
 * The remaining time is measured through the injected {@link Clock} — the same time source
 * that stamped the snapshot's observation instant — because the upstream reset counts from
 * the observation of the headers: time the previous page spent reading and processing its
 * body is never waited again, and a reset the body read already outlived (or left with less
 * than one millisecond outstanding) waits nothing. A cancellation abort throws without any
 * thread interrupt; a genuine interrupt of the wait restores the thread's flag before
 * propagating, so the caller can tell the two apart by the flag.
 */
public final class SlicedRateWaiter implements PaginationService.RateWaiter {

    private final Clock clock;

    public SlicedRateWaiter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Duration outstandingUntil(Instant resetAt) {
        Objects.requireNonNull(resetAt, "resetAt");
        Duration outstanding = Duration.between(clock.instant(), resetAt);
        return outstanding.isNegative() ? Duration.ZERO : outstanding;
    }

    @Override
    public Duration await(Instant resetAt, CancellationContext cancellation) throws InterruptedException {
        Objects.requireNonNull(resetAt, "resetAt");
        Objects.requireNonNull(cancellation, "cancellation");
        Duration outstanding = outstandingUntil(resetAt);
        if (outstanding.toMillis() == 0) {
            return Duration.ZERO;
        }
        boolean cancelled;
        try {
            cancelled = cancellation.await(outstanding);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        if (cancelled) {
            throw new InterruptedException("pagination wait cancelled");
        }
        return outstanding;
    }
}

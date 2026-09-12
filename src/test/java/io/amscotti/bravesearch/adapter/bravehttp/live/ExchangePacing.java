package io.amscotti.bravesearch.adapter.bravehttp.live;

import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import io.amscotti.bravesearch.domain.metadata.RateLimitWindow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;

/**
 * The live suite's pacing gate: shared state that spaces every live dispatch at least
 * {@link #MINIMUM_INTER_EXCHANGE_GAP} apart — the observed one-request-per-second upstream
 * policy plus margin — and the bounded decision governing the single retry of a rate-limited
 * exchange.
 *
 * <p>This is test instrumentation, never product retry: a dispatch that answers 429 is
 * retried exactly once, and only after waiting the rate-limit snapshot the failure carries.
 * The wait honors the smallest applicable window reset — the windows that show exhaustion
 * govern, with every observed window as the fallback when none does — bounded by
 * {@link #MAXIMUM_RATE_LIMIT_WAIT}; when even the smallest applicable reset outlives that
 * budget (an exhausted monthly window, for example), the exchange skips with the fixed
 * {@link #RATE_WINDOW_SKIP_MESSAGE} instead of stalling the run, and a paced retry that
 * answers rate-limited again skips the same way through
 * {@link #RATE_LIMITED_AFTER_RETRY_SKIP_MESSAGE}. A snapshot without any window leaves the
 * pacing gap as the only defensible wait.
 *
 * <p>Time and sleeping are injected, so the whole behavior is hermetically provable: the
 * clock decides when a slot is free, and the sleeper receives exactly the requested waits.
 * After a sleep the clock is re-read, because a real sleep can overshoot and the next gap
 * must be measured from the actually observed dispatch instant.
 */
final class ExchangePacing {

    /** The observed upstream policy is one request per second; the gap adds scheduling margin. */
    static final Duration MINIMUM_INTER_EXCHANGE_GAP = Duration.ofMillis(1100);

    /** The longest wait the smoke budget ever spends on a rate-limited exchange's single retry. */
    static final Duration MAXIMUM_RATE_LIMIT_WAIT = Duration.ofSeconds(5);

    /** The fixed skip reason when the smallest applicable window reset outlives the budget. */
    static final String RATE_WINDOW_SKIP_MESSAGE = "rate window requires a longer wait than the smoke budget";

    /**
     * The fixed skip reason when the single paced retry answers rate-limited again: the key is
     * being throttled beyond what one in-budget retry can ride out — an environmental
     * condition of the run, never a protocol failure.
     */
    static final String RATE_LIMITED_AFTER_RETRY_SKIP_MESSAGE =
            "rate limit persisted after the single paced retry";

    /** One requested wait, delivered by the injected sleeper. */
    @FunctionalInterface
    interface Sleeper {

        void sleep(Duration duration) throws InterruptedException;
    }

    /** One live exchange dispatch, opened and closed by its caller. */
    @FunctionalInterface
    interface Exchange<T> {

        Outcome<T> dispatch() throws Exception;
    }

    private final Clock clock;

    private final Sleeper sleeper;

    private Instant lastDispatchAt;

    ExchangePacing(Clock clock, Sleeper sleeper) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    /**
     * Sleeps until the next exchange slot is free, then marks the dispatch; call before every
     * live dispatch while holding the live guard's exchange lock.
     */
    synchronized void awaitExchangeSlot() throws InterruptedException {
        Instant now = clock.instant();
        if (lastDispatchAt == null) {
            lastDispatchAt = now;
            return;
        }
        Instant earliestNext = lastDispatchAt.plus(MINIMUM_INTER_EXCHANGE_GAP);
        if (now.isBefore(earliestNext)) {
            sleeper.sleep(Duration.between(now, earliestNext));
            Instant afterSleep = clock.instant();
            lastDispatchAt = earliestNext.isAfter(afterSleep) ? earliestNext : afterSleep;
        } else {
            lastDispatchAt = now;
        }
    }

    /** The injected clock's current instant. */
    synchronized Instant now() {
        return clock.instant();
    }

    /** Delivers one wait through the injected sleeper. */
    void sleepFor(Duration duration) throws InterruptedException {
        sleeper.sleep(duration);
    }

    /**
     * One live exchange through the pacing gate: dispatch in the next slot, and when the
     * answer is a rate-limited failure, wait the snapshot's bounded reset and retry the
     * dispatch exactly once. A reset beyond the budget skips with the fixed message, and so
     * does a paced retry that answers rate-limited again — a key still throttled after one
     * in-budget retry is an environmental condition, not a protocol verdict. Any other
     * outcome — success or failure — is the caller's, untouched.
     */
    <T> Outcome<T> dispatchPaced(Exchange<T> exchange) throws Exception {
        awaitExchangeSlot();
        Outcome<T> first = exchange.dispatch();
        if (!(first instanceof Outcome.Failure<T> failure) || failure.kind() != FailureKind.RATE_LIMITED) {
            return first;
        }
        Optional<Duration> wait = boundedRetryWait(failure.rateLimits(), now());
        if (wait.isEmpty()) {
            Assumptions.abort(RATE_WINDOW_SKIP_MESSAGE);
        }
        sleepFor(wait.orElseThrow());
        awaitExchangeSlot();
        Outcome<T> retry = exchange.dispatch();
        if (retry instanceof Outcome.Failure<T> repeated && repeated.kind() == FailureKind.RATE_LIMITED) {
            Assumptions.abort(RATE_LIMITED_AFTER_RETRY_SKIP_MESSAGE);
        }
        return retry;
    }

    /**
     * The bounded wait governing the single retry of a rate-limited exchange: the smallest
     * applicable window reset still outstanding, empty when even that smallest reset exceeds
     * the smoke budget. Exhausted windows — a positive limit with no remaining — govern; when
     * none is exhausted, every observed window is applicable. A snapshot without windows
     * leaves only the pacing gap. Past resets contribute zero, never a negative wait.
     */
    static Optional<Duration> boundedRetryWait(RateLimitSnapshot snapshot, Instant now) {
        if (snapshot == null || snapshot.windows().isEmpty()) {
            return Optional.of(MINIMUM_INTER_EXCHANGE_GAP);
        }
        List<RateLimitWindow> exhausted = snapshot.windows().stream()
                .filter(window -> window.limit() > 0 && window.remaining() == 0)
                .toList();
        List<RateLimitWindow> applicable = exhausted.isEmpty() ? snapshot.windows() : exhausted;
        Duration smallest = applicable.stream()
                .map(window -> outstanding(snapshot.resetAtOf(window), now))
                .min(Duration::compareTo)
                .orElseThrow();
        return smallest.compareTo(MAXIMUM_RATE_LIMIT_WAIT) > 0
                ? Optional.empty()
                : Optional.of(smallest);
    }

    /** The outstanding part of a reset instant, clamped so a past reset contributes zero. */
    private static Duration outstanding(Instant resetAt, Instant now) {
        Duration between = Duration.between(now, resetAt);
        return between.isNegative() ? Duration.ZERO : between;
    }
}

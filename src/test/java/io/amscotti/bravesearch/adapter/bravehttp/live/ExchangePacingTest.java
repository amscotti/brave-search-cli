package io.amscotti.bravesearch.adapter.bravehttp.live;

import static java.time.Duration.ZERO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import io.amscotti.bravesearch.domain.metadata.RateLimitWindow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

/**
 * Hermetic behavior of the live suite's exchange pacing: consecutive live dispatches are
 * spaced by the upstream one-request-per-second policy (with margin), a rate-limited
 * exchange retries exactly once after the observed window reset — skipping with the fixed
 * message when the smallest applicable reset outlives the smoke budget, and skipping the
 * same way when the paced retry itself answers rate-limited again — and the
 * places-entitlement classification of this repository's development key rides along as
 * observed fact. Every clock and sleeper here is fake; no test performs a network exchange
 * or a real sleep.
 */
final class ExchangePacingTest {

    private static final Instant T0 = Instant.ofEpochSecond(1_700_000_000);

    private static final Duration WINDOW_GAP = Duration.ofMillis(1100);

    /** The observed live shapes: one request per second, plus a monthly quota window. */
    private static final String PER_SECOND_POLICY = "1;w=1";

    private static final String MONTHLY_POLICY = "2000;w=2592000";

    /** Clock whose instant moves only when a sleep (or the test) advances it. */
    private static final class AdvancingClock extends Clock {

        private Instant now;

        AdvancingClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
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

    /** Sleeper that records every requested wait and moves time by the wait plus any overshoot. */
    private static final class RecordingSleeper implements ExchangePacing.Sleeper {

        private final AdvancingClock clock;

        private final Duration overshoot;

        private final List<Duration> slept = new ArrayList<>();

        RecordingSleeper(AdvancingClock clock, Duration overshoot) {
            this.clock = clock;
            this.overshoot = overshoot;
        }

        @Override
        public void sleep(Duration duration) {
            slept.add(duration);
            clock.advance(duration.plus(overshoot));
        }
    }

    /** Exchange script that counts dispatches and answers in the scripted order. */
    private static final class ScriptedExchange implements ExchangePacing.Exchange<String> {

        private final Queue<Outcome<String>> script = new ArrayDeque<>();

        private int dispatches;

        void thenDispatch(Outcome<String> outcome) {
            script.add(outcome);
        }

        @Override
        public Outcome<String> dispatch() {
            dispatches++;
            return script.remove();
        }
    }

    @Test
    void firstExchangeDispatchesWithoutWaiting() throws Exception {
        AdvancingClock clock = new AdvancingClock(T0);
        RecordingSleeper sleeper = new RecordingSleeper(clock, ZERO);
        ExchangePacing pacing = new ExchangePacing(clock, sleeper);

        pacing.awaitExchangeSlot();

        assertTrue(sleeper.slept.isEmpty(), "the very first live exchange has no predecessor to wait for");
    }

    @Test
    void backToBackExchangesSleepUntilTheMinimumGapElapses() throws Exception {
        AdvancingClock clock = new AdvancingClock(T0);
        RecordingSleeper sleeper = new RecordingSleeper(clock, ZERO);
        ExchangePacing pacing = new ExchangePacing(clock, sleeper);

        pacing.awaitExchangeSlot();
        clock.advance(Duration.ofMillis(200));
        pacing.awaitExchangeSlot();

        assertEquals(
                List.of(WINDOW_GAP.minus(Duration.ofMillis(200))),
                sleeper.slept,
                "a follow-up exchange inside the policy window sleeps exactly the remaining gap");
        assertEquals(WINDOW_GAP, ExchangePacing.MINIMUM_INTER_EXCHANGE_GAP, "the gap is the second-window policy plus margin");
    }

    @Test
    void aSleepOvershootStillSpacesTheFollowingExchange() throws Exception {
        AdvancingClock clock = new AdvancingClock(T0);
        RecordingSleeper sleeper = new RecordingSleeper(clock, Duration.ofMillis(100));
        ExchangePacing pacing = new ExchangePacing(clock, sleeper);

        pacing.awaitExchangeSlot();
        clock.advance(Duration.ofMillis(200));
        pacing.awaitExchangeSlot();
        clock.advance(Duration.ofMillis(50));
        pacing.awaitExchangeSlot();

        assertEquals(
                List.of(
                        WINDOW_GAP.minus(Duration.ofMillis(200)),
                        WINDOW_GAP.minus(Duration.ofMillis(50))),
                sleeper.slept,
                "an overshooting sleep must push the next slot one full gap past the actually"
                        + " observed dispatch instant, not the idealized slot");
    }

    @Test
    void exhaustedSecondWindowGovernsTheRetryWait() {
        RateLimitSnapshot observed = snapshotAt(
                T0,
                new RateLimitWindow(PER_SECOND_POLICY, 1, 0, Duration.ofSeconds(1)),
                new RateLimitWindow(MONTHLY_POLICY, 2000, 1999, Duration.ofDays(30)));
        assertEquals(
                Optional.of(Duration.ofSeconds(1)),
                ExchangePacing.boundedRetryWait(observed, T0),
                "the exhausted per-second window governs; the unexhausted monthly window is irrelevant");
    }

    @Test
    void anExhaustedMonthlyWindowBeyondTheBudgetSkips() {
        RateLimitSnapshot observed =
                snapshotAt(T0, new RateLimitWindow(MONTHLY_POLICY, 2000, 0, Duration.ofDays(30)));
        assertEquals(
                Optional.empty(),
                ExchangePacing.boundedRetryWait(observed, T0),
                "a monthly window with no quota left outlives any smoke budget, so no wait applies");
        assertEquals(Duration.ofSeconds(5), ExchangePacing.MAXIMUM_RATE_LIMIT_WAIT, "the budget stays bounded");
    }

    @Test
    void smallestApplicableResetWinsWhenSeveralWindowsAreExhausted() {
        RateLimitSnapshot observed = snapshotAt(
                T0,
                new RateLimitWindow(MONTHLY_POLICY, 2000, 0, Duration.ofDays(30)),
                new RateLimitWindow(PER_SECOND_POLICY, 1, 0, Duration.ofMillis(800)));
        assertEquals(
                Optional.of(Duration.ofMillis(800)),
                ExchangePacing.boundedRetryWait(observed, T0),
                "the smallest applicable reset decides; a skip happens only when even it exceeds the budget");
    }

    @Test
    void unexhaustedWindowsFallBackToTheSmallestObservedReset() {
        RateLimitSnapshot observed = snapshotAt(
                T0,
                new RateLimitWindow(PER_SECOND_POLICY, 1, 1, Duration.ofSeconds(1)),
                new RateLimitWindow(MONTHLY_POLICY, 2000, 1999, Duration.ofDays(30)));
        assertEquals(
                Optional.of(Duration.ofSeconds(1)),
                ExchangePacing.boundedRetryWait(observed, T0),
                "a 429 that exhausted no observed window still honors the smallest observed reset");
    }

    @Test
    void anUnlimitedWindowDeclarationNeverGoverns() {
        RateLimitSnapshot observed = snapshotAt(
                T0,
                new RateLimitWindow("unlimited", 0, 0, Duration.ofDays(30)),
                new RateLimitWindow(PER_SECOND_POLICY, 1, 0, Duration.ofSeconds(2)));
        assertEquals(
                Optional.of(Duration.ofSeconds(2)),
                ExchangePacing.boundedRetryWait(observed, T0),
                "a zero limit declares unlimited capacity, so its zero remaining is not exhaustion");
    }

    @Test
    void aSnapshotWithoutWindowsWaitsThePacingGap() {
        assertEquals(
                Optional.of(WINDOW_GAP),
                ExchangePacing.boundedRetryWait(RateLimitSnapshot.empty(), T0),
                "no observed window leaves the pacing gap as the only defensible wait");
        assertEquals(
                Optional.of(WINDOW_GAP),
                ExchangePacing.boundedRetryWait(null, T0),
                "a rate-limited break without any header observation waits the pacing gap too");
    }

    @Test
    void anElapsedWindowResetClampsItsRetryWaitToZero() {
        RateLimitSnapshot observed =
                snapshotAt(T0, new RateLimitWindow(PER_SECOND_POLICY, 1, 0, Duration.ofSeconds(1)));
        assertEquals(
                Optional.of(ZERO),
                ExchangePacing.boundedRetryWait(observed, T0.plus(Duration.ofSeconds(5))),
                "a reset instant already in the past contributes no negative wait");
    }

    @Test
    void rateLimitedExchangeRetriesOnceAfterTheObservedWindowReset() throws Exception {
        AdvancingClock clock = new AdvancingClock(T0);
        RecordingSleeper sleeper = new RecordingSleeper(clock, ZERO);
        ExchangePacing pacing = new ExchangePacing(clock, sleeper);
        ScriptedExchange exchange = new ScriptedExchange();
        exchange.thenDispatch(rateLimitedAt(
                snapshotAt(T0, new RateLimitWindow(PER_SECOND_POLICY, 1, 0, Duration.ofSeconds(1)))));
        exchange.thenDispatch(new Outcome.Success<>("exchanged"));

        Outcome<String> outcome = pacing.dispatchPaced(exchange);

        assertEquals("exchanged", ((Outcome.Success<String>) outcome).value(), "the single retry answers the exchange");
        assertEquals(2, exchange.dispatches, "exactly one retry follows the rate-limited first dispatch");
        assertEquals(
                List.of(Duration.ofSeconds(1), WINDOW_GAP.minus(Duration.ofSeconds(1))),
                sleeper.slept,
                "the retry waits the observed window reset, then the remainder of the pacing gap");
    }

    @Test
    void anInBudgetRetryThatIsRateLimitedAgainSkipsThroughTheFixedMessage() throws Exception {
        AdvancingClock clock = new AdvancingClock(T0);
        RecordingSleeper sleeper = new RecordingSleeper(clock, ZERO);
        ExchangePacing pacing = new ExchangePacing(clock, sleeper);
        ScriptedExchange exchange = new ScriptedExchange();
        exchange.thenDispatch(rateLimitedAt(
                snapshotAt(T0, new RateLimitWindow(PER_SECOND_POLICY, 1, 0, Duration.ofSeconds(1)))));
        exchange.thenDispatch(rateLimitedAt(
                snapshotAt(T0, new RateLimitWindow(PER_SECOND_POLICY, 1, 0, Duration.ofSeconds(1)))));

        TestAbortedException aborted = assertThrows(TestAbortedException.class, () -> pacing.dispatchPaced(exchange));

        assertTrue(
                aborted.getMessage().endsWith(ExchangePacing.RATE_LIMITED_AFTER_RETRY_SKIP_MESSAGE),
                "a paced retry that answers rate-limited again is an environmental skip, never a red failure;"
                        + " Jupiter may only prefix its standard wording");
        assertEquals(
                "rate limit persisted after the single paced retry",
                ExchangePacing.RATE_LIMITED_AFTER_RETRY_SKIP_MESSAGE);
        assertEquals(2, exchange.dispatches, "the retry budget is one, never a loop");
    }

    @Test
    void nonRateLimitedFailureReturnsUntouchedWithoutAnyRetry() throws Exception {
        AdvancingClock clock = new AdvancingClock(T0);
        RecordingSleeper sleeper = new RecordingSleeper(clock, ZERO);
        ExchangePacing pacing = new ExchangePacing(clock, sleeper);
        ScriptedExchange exchange = new ScriptedExchange();
        exchange.thenDispatch(new Outcome.Failure<>(FailureKind.UPSTREAM, "upstream exchange failed with status 400", null, null, 400));

        Outcome<String> outcome = pacing.dispatchPaced(exchange);

        assertTrue(
                outcome instanceof Outcome.Failure<String> failure && failure.httpStatus() == 400,
                "a plain failure is the caller's to judge; pacing never retries it");
        assertEquals(1, exchange.dispatches, "no retry outside the rate-limited shape");
        assertTrue(sleeper.slept.isEmpty(), "no wait without a rate limit");
    }

    @Test
    void monthlyExhaustionSkipsThroughTheFixedMessage() throws Exception {
        AdvancingClock clock = new AdvancingClock(T0);
        RecordingSleeper sleeper = new RecordingSleeper(clock, ZERO);
        ExchangePacing pacing = new ExchangePacing(clock, sleeper);
        ScriptedExchange exchange = new ScriptedExchange();
        exchange.thenDispatch(
                rateLimitedAt(snapshotAt(T0, new RateLimitWindow(MONTHLY_POLICY, 2000, 0, Duration.ofDays(30)))));

        TestAbortedException aborted = assertThrows(TestAbortedException.class, () -> pacing.dispatchPaced(exchange));

        assertTrue(
                aborted.getMessage().endsWith(ExchangePacing.RATE_WINDOW_SKIP_MESSAGE),
                "the skip reason is the fixed message; Jupiter may only prefix its standard wording");
        assertEquals(
                "rate window requires a longer wait than the smoke budget",
                ExchangePacing.RATE_WINDOW_SKIP_MESSAGE);
        assertEquals(1, exchange.dispatches, "a skipped exchange never retries");
        assertTrue(sleeper.slept.isEmpty(), "a skipped exchange never waits");
    }

    private static RateLimitSnapshot snapshotAt(Instant observedAt, RateLimitWindow... windows) {
        return new RateLimitSnapshot(List.of(windows), List.of(), observedAt);
    }

    private static Outcome.Failure<String> rateLimitedAt(RateLimitSnapshot observed) {
        return new Outcome.Failure<>(FailureKind.RATE_LIMITED, "upstream exchange failed with status 429", null, observed, 429);
    }
}

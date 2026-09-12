package io.amscotti.bravesearch.adapter.bravehttp.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import io.amscotti.bravesearch.domain.metadata.RateLimitWindow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Comma-alignment contract of the four rate-limit headers: every header carries one
 * comma-separated token list, window i is built from the i-th token of each list, and no
 * malformed or missing token ever shifts the positions that follow it. The injected clock
 * fixes the observation instant, so the derived per-window reset instants are assertable.
 */
final class RateLimitHeaderParserTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-08-31T10:15:30Z");

    private static final Clock OBSERVATION_CLOCK = Clock.fixed(OBSERVED_AT, ZoneOffset.UTC);

    @Test
    void singleWindowParsesFromAllFourHeaders() {
        RateLimitSnapshot snapshot = RateLimitHeaderParser.parse(
                Map.of(
                        "X-RateLimit-Limit", List.of("15"),
                        "X-RateLimit-Policy", List.of("per-minute"),
                        "X-RateLimit-Remaining", List.of("14"),
                        "X-RateLimit-Reset", List.of("42")), OBSERVATION_CLOCK);

        assertEquals(
                List.of(new RateLimitWindow("per-minute", 15, 14, Duration.ofSeconds(42))),
                snapshot.windows());
        assertEquals(List.of(), snapshot.notes());
        assertEquals(OBSERVED_AT, snapshot.observedAt());
    }

    @Test
    void threeCommaAlignedWindowsParsePositionally() {
        RateLimitSnapshot snapshot = RateLimitHeaderParser.parse(
                Map.of(
                        "X-RateLimit-Limit", List.of("1,15,2000"),
                        "X-RateLimit-Policy", List.of("request,minute,month"),
                        "X-RateLimit-Remaining", List.of("0,14,1997"),
                        "X-RateLimit-Reset", List.of("1,42,26000")), OBSERVATION_CLOCK);

        assertEquals(
                List.of(
                        new RateLimitWindow("request", 1, 0, Duration.ofSeconds(1)),
                        new RateLimitWindow("minute", 15, 14, Duration.ofSeconds(42)),
                        new RateLimitWindow("month", 2000, 1997, Duration.ofSeconds(26000))),
                snapshot.windows());
    }

    @Test
    void repeatedPhysicalHeaderLinesFlattenInOrder() {
        RateLimitSnapshot snapshot = RateLimitHeaderParser.parse(
                Map.of(
                        "X-RateLimit-Limit", List.of("1", "15, 2000"),
                        "X-RateLimit-Policy", List.of("request", "minute,month"),
                        "X-RateLimit-Remaining", List.of("0", "14,1997"),
                        "X-RateLimit-Reset", List.of("1", "42,26000")), OBSERVATION_CLOCK);

        assertEquals(3, snapshot.windows().size());
        assertEquals(new RateLimitWindow("request", 1, 0, Duration.ofSeconds(1)), snapshot.windows().get(0));
        assertEquals(new RateLimitWindow("minute", 15, 14, Duration.ofSeconds(42)), snapshot.windows().get(1));
        assertEquals(new RateLimitWindow("month", 2000, 1997, Duration.ofSeconds(26000)), snapshot.windows().get(2));
    }

    @Test
    void headerNamesMatchCaseInsensitively() {
        RateLimitSnapshot snapshot = RateLimitHeaderParser.parse(
                Map.of(
                        "x-ratelimit-limit", List.of("15"),
                        "x-RateLimit-Policy", List.of("per-minute"),
                        "X-RATELIMIT-REMAINING", List.of("14"),
                        "X-RateLimit-reset", List.of("42")), OBSERVATION_CLOCK);

        assertEquals(1, snapshot.windows().size());
        assertEquals(new RateLimitWindow("per-minute", 15, 14, Duration.ofSeconds(42)), snapshot.windows().getFirst());
    }

    @Test
    void misalignedCommaListsKeepAlignedWindowsAndDropGapsWithoutShifting() {
        RateLimitSnapshot snapshot = RateLimitHeaderParser.parse(
                Map.of(
                        "X-RateLimit-Limit", List.of("1,15,2000"),
                        "X-RateLimit-Policy", List.of("request,minute,month"),
                        "X-RateLimit-Remaining", List.of("0,14"),
                        "X-RateLimit-Reset", List.of("1,42,26000")), OBSERVATION_CLOCK);

        assertEquals(
                List.of(
                        new RateLimitWindow("request", 1, 0, Duration.ofSeconds(1)),
                        new RateLimitWindow("minute", 15, 14, Duration.ofSeconds(42))),
                snapshot.windows(),
                "the first two positions are fully populated and keep their order");
        assertEquals(1, snapshot.notes().size(), "one gap note, not one per missing field");
        assertTrue(snapshot.notes().getFirst().contains("misaligned"), snapshot.notes()::toString);
        assertTrue(snapshot.notes().getFirst().contains("remaining=2"), snapshot.notes()::toString);
        assertTrue(snapshot.notes().getFirst().contains("limit=3"), snapshot.notes()::toString);
    }

    @Test
    void malformedNumericTokensDropTheWindowWithANoteForEachOffendingField() {
        RateLimitSnapshot badLimit = RateLimitHeaderParser.parse(
                Map.of(
                        "X-RateLimit-Limit", List.of("NaN,15"),
                        "X-RateLimit-Policy", List.of("request,minute"),
                        "X-RateLimit-Remaining", List.of("0,14"),
                        "X-RateLimit-Reset", List.of("1,42")), OBSERVATION_CLOCK);
        assertEquals(List.of(new RateLimitWindow("minute", 15, 14, Duration.ofSeconds(42))), badLimit.windows());
        assertEquals(1, badLimit.notes().size());
        assertTrue(badLimit.notes().getFirst().contains("x-ratelimit-limit"), badLimit.notes()::toString);
        assertTrue(badLimit.notes().getFirst().contains("position 0"), badLimit.notes()::toString);

        RateLimitSnapshot negativeRemaining = RateLimitHeaderParser.parse(
                Map.of(
                        "X-RateLimit-Limit", List.of("15"),
                        "X-RateLimit-Policy", List.of("minute"),
                        "X-RateLimit-Remaining", List.of("-3"),
                        "X-RateLimit-Reset", List.of("42")), OBSERVATION_CLOCK);
        assertEquals(List.of(), negativeRemaining.windows());
        assertTrue(
                negativeRemaining.notes().getFirst().contains("x-ratelimit-remaining"),
                negativeRemaining.notes()::toString);

        RateLimitSnapshot emptyResetToken = RateLimitHeaderParser.parse(
                Map.of(
                        "X-RateLimit-Limit", List.of("15,15"),
                        "X-RateLimit-Policy", List.of("minute,minute"),
                        "X-RateLimit-Remaining", List.of("14,14"),
                        "X-RateLimit-Reset", List.of("42,")), OBSERVATION_CLOCK);
        assertEquals(
                List.of(new RateLimitWindow("minute", 15, 14, Duration.ofSeconds(42))),
                emptyResetToken.windows(),
                "an empty token between or after commas is malformed, never a shifted window");
        assertTrue(emptyResetToken.notes().getFirst().contains("x-ratelimit-reset"), emptyResetToken.notes()::toString);
    }

    @Test
    void resetBeyondTheRepresentableHorizonDropsTheWindowWithANote() {
        RateLimitSnapshot snapshot = RateLimitHeaderParser.parse(
                Map.of(
                        "X-RateLimit-Limit", List.of("15"),
                        "X-RateLimit-Policy", List.of("per-minute"),
                        "X-RateLimit-Remaining", List.of("14"),
                        "X-RateLimit-Reset", List.of("9999999999999999")), OBSERVATION_CLOCK);

        assertEquals(
                List.of(),
                snapshot.windows(),
                "a reset no duration-to-millis rendering can hold never becomes a window");
        assertEquals(1, snapshot.notes().size());
        assertTrue(snapshot.notes().getFirst().contains("x-ratelimit-reset"), snapshot.notes()::toString);
        assertTrue(snapshot.notes().getFirst().contains("horizon"), snapshot.notes()::toString);
    }

    @Test
    void hostileResetTokensDegradeAccordingToTheirOwnFlavor() {
        RateLimitSnapshot beyondHorizon = RateLimitHeaderParser.parse(
                Map.of(
                        "X-RateLimit-Limit", List.of("15"),
                        "X-RateLimit-Policy", List.of("per-minute"),
                        "X-RateLimit-Remaining", List.of("14"),
                        "X-RateLimit-Reset", List.of("9223372036854775807")), OBSERVATION_CLOCK);
        assertEquals(List.of(), beyondHorizon.windows(), "a maximal long reset is far beyond the horizon");
        assertEquals(1, beyondHorizon.notes().size());
        assertTrue(beyondHorizon.notes().getFirst().contains("horizon"), beyondHorizon.notes()::toString);

        RateLimitSnapshot fractional = RateLimitHeaderParser.parse(
                Map.of(
                        "X-RateLimit-Limit", List.of("15"),
                        "X-RateLimit-Policy", List.of("per-minute"),
                        "X-RateLimit-Remaining", List.of("14"),
                        "X-RateLimit-Reset", List.of("42.5")), OBSERVATION_CLOCK);
        assertEquals(List.of(), fractional.windows());
        assertEquals(1, fractional.notes().size());
        assertTrue(
                fractional.notes().getFirst().contains("not a nonnegative integer"),
                fractional.notes()::toString);

        RateLimitSnapshot nonAsciiDigits = RateLimitHeaderParser.parse(
                Map.of(
                        "X-RateLimit-Limit", List.of("15"),
                        "X-RateLimit-Policy", List.of("per-minute"),
                        "X-RateLimit-Remaining", List.of("14"),
                        "X-RateLimit-Reset", List.of("٤٢")), OBSERVATION_CLOCK);
        assertEquals(
                List.of(),
                nonAsciiDigits.windows(),
                "the wire grammar is ASCII digits; Arabic-Indic digit forms never parse silently");
        assertEquals(1, nonAsciiDigits.notes().size());
        assertTrue(
                nonAsciiDigits.notes().getFirst().contains("not a nonnegative integer"),
                nonAsciiDigits.notes()::toString);
    }

    @Test
    void commasInsideAPolicyTokenHaveNoEscapeAndSplitPositionally() {
        RateLimitSnapshot snapshot = RateLimitHeaderParser.parse(
                Map.of(
                        "X-RateLimit-Limit", List.of("1"),
                        "X-RateLimit-Policy", List.of("name=\"a,b\""),
                        "X-RateLimit-Remaining", List.of("0"),
                        "X-RateLimit-Reset", List.of("1")), OBSERVATION_CLOCK);

        assertEquals(
                1,
                snapshot.windows().size(),
                "the split is positional, so the first fragment still pairs with the only window");
        assertEquals(
                "name=\"a",
                snapshot.windows().getFirst().policy(),
                "a split fragment is kept verbatim, never repaired or rejoined");
        assertEquals(1, snapshot.notes().size());
        assertTrue(snapshot.notes().getFirst().contains("misaligned"), snapshot.notes()::toString);
        assertTrue(snapshot.notes().getFirst().contains("policy=2"), snapshot.notes()::toString);
    }

    @Test
    void hostileWindowCountsCapAtSixtyFourWithATruncationNote() {
        RateLimitSnapshot snapshot = RateLimitHeaderParser.parse(
                Map.of(
                        "X-RateLimit-Limit", List.of(joinedTokens("1")),
                        "X-RateLimit-Policy", List.of(joinedTokens("p")),
                        "X-RateLimit-Remaining", List.of(joinedTokens("0")),
                        "X-RateLimit-Reset", List.of(joinedTokens("1"))), OBSERVATION_CLOCK);

        assertEquals(64, snapshot.windows().size(), "a hundred comma tokens never yield a hundred windows");
        assertEquals(1, snapshot.notes().size());
        assertTrue(
                snapshot.notes().getFirst().contains("capped"), snapshot.notes()::toString);
    }

    private static String joinedTokens(String token) {
        return String.join(",", java.util.Collections.nCopies(100, token));
    }

    @Test
    void zeroResetAndZeroLimitRemainValidWindows() {
        RateLimitSnapshot snapshot = RateLimitHeaderParser.parse(
                Map.of(
                        "X-RateLimit-Limit", List.of("0"),
                        "X-RateLimit-Policy", List.of("token"),
                        "X-RateLimit-Remaining", List.of("0"),
                        "X-RateLimit-Reset", List.of("0")), OBSERVATION_CLOCK);

        assertEquals(List.of(new RateLimitWindow("token", 0, 0, Duration.ZERO)), snapshot.windows());
        assertEquals(List.of(), snapshot.notes());
    }

    @Test
    void policyTokensKeepInternalStructureVerbatim() {
        String structured = "1;w=60;name=\"burst\"";
        RateLimitSnapshot snapshot = RateLimitHeaderParser.parse(
                Map.of(
                        "X-RateLimit-Limit", List.of("15"),
                        "X-RateLimit-Policy", List.of("  " + structured + "  "),
                        "X-RateLimit-Remaining", List.of("14"),
                        "X-RateLimit-Reset", List.of("42")), OBSERVATION_CLOCK);

        assertEquals(structured, snapshot.windows().getFirst().policy());
    }

    @Test
    void resetAtIsDerivedPerWindowAgainstTheObservedInstant() {
        RateLimitSnapshot snapshot = RateLimitHeaderParser.parse(
                Map.of(
                        "X-RateLimit-Limit", List.of("1,15"),
                        "X-RateLimit-Policy", List.of("request,minute"),
                        "X-RateLimit-Remaining", List.of("0,14"),
                        "X-RateLimit-Reset", List.of("7,26000")), OBSERVATION_CLOCK);

        assertEquals(OBSERVED_AT.plus(Duration.ofSeconds(7)), snapshot.resetAtOf(snapshot.windows().get(0)));
        assertEquals(OBSERVED_AT.plus(Duration.ofSeconds(26000)), snapshot.resetAtOf(snapshot.windows().get(1)));
    }

    @Test
    void absentHeadersYieldAnEmptySnapshotWithTheObservationInstant() {
        RateLimitSnapshot snapshot = RateLimitHeaderParser.parse(Map.of("Content-Type", List.of("application/json")), OBSERVATION_CLOCK);

        assertEquals(List.of(), snapshot.windows());
        assertEquals(List.of(), snapshot.notes());
        assertEquals(OBSERVED_AT, snapshot.observedAt());
    }

    @Test
    void notesNeverQuoteHeaderTokenText() {
        String sentinel = "sentinel-8f13a2";
        RateLimitSnapshot untrustedPolicy = RateLimitHeaderParser.parse(
                Map.of(
                        "X-RateLimit-Limit", List.of("1"),
                        "X-RateLimit-Policy", List.of(sentinel),
                        "X-RateLimit-Remaining", List.of("0"),
                        "X-RateLimit-Reset", List.of("1")), OBSERVATION_CLOCK);
        assertEquals(List.of(), untrustedPolicy.notes());
        assertFalse(
                untrustedPolicy.windows().getFirst().policy().contains("rate-limit"),
                "the policy value is data, but notes are the diagnostic channel");

        RateLimitSnapshot malformedWithSecret = RateLimitHeaderParser.parse(
                Map.of(
                        "X-RateLimit-Limit", List.of(sentinel),
                        "X-RateLimit-Policy", List.of("request"),
                        "X-RateLimit-Remaining", List.of("0"),
                        "X-RateLimit-Reset", List.of("1")), OBSERVATION_CLOCK);
        assertEquals(1, malformedWithSecret.notes().size());
        assertFalse(
                malformedWithSecret.notes().getFirst().contains(sentinel),
                "a note names the field and position, never the observed token text: "
                        + malformedWithSecret.notes());
    }
}

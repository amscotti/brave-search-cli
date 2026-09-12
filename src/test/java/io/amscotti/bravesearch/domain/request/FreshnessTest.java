package io.amscotti.bravesearch.domain.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.UsageValidationError;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Parsing and rendering of the freshness window: exactly the four recent-period tokens and
 * the strict ISO date-range form, impossible calendar dates and unordered ranges rejected,
 * and the range rendering pinned to {@code YYYY-MM-DDtoYYYY-MM-DD}.
 */
final class FreshnessTest {

    @Test
    void parsesEachRecentPeriodToken() {
        assertEquals("pd", new Freshness.RecentPd().wireValue());
        assertEquals("pw", new Freshness.RecentPw().wireValue());
        assertEquals("pm", new Freshness.RecentPm().wireValue());
        assertEquals("py", new Freshness.RecentPy().wireValue());
        assertEquals(new Freshness.RecentPd(), Freshness.parse("pd"));
        assertEquals(new Freshness.RecentPw(), Freshness.parse("pw"));
        assertEquals(new Freshness.RecentPm(), Freshness.parse("pm"));
        assertEquals(new Freshness.RecentPy(), Freshness.parse("py"));
    }

    @Test
    void parsesADateRangeWithItsExactRendering() {
        Freshness parsed = Freshness.parse("2026-01-01to2026-02-28");
        assertEquals(new Freshness.DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28)), parsed);
        assertEquals("2026-01-01to2026-02-28", parsed.wireValue());
    }

    @Test
    void acceptsASingleDayRange() {
        assertEquals("2026-01-01to2026-01-01", Freshness.parse("2026-01-01to2026-01-01").wireValue());
    }

    @Test
    void acceptsALeapDayRangeAndRejectsANonLeapOne() {
        assertEquals("2024-02-29to2024-02-29", Freshness.parse("2024-02-29to2024-02-29").wireValue());
        assertThrows(UsageValidationError.class, () -> Freshness.parse("2026-02-29to2026-03-01"));
    }

    @Test
    void rejectsImpossibleCalendarDates() {
        assertThrows(UsageValidationError.class, () -> Freshness.parse("2026-02-30to2026-03-01"));
        assertThrows(UsageValidationError.class, () -> Freshness.parse("2026-13-01to2026-03-01"));
    }

    @Test
    void rejectsUnorderedRanges() {
        assertThrows(UsageValidationError.class, () -> Freshness.parse("2026-03-01to2026-01-01"));
        assertThrows(
                UsageValidationError.class,
                () -> new Freshness.DateRange(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 1, 1)));
    }

    @Test
    void rejectsSignedAndExtendedYearsOutsideTheFourDigitGrammar() {
        for (String extended :
                new String[] {"+10000-01-01to+10000-01-01", "-0001-06-07to2026-01-01", "2026-01-01to+10000-12-31", "9999-12-31to-0001-01-01"}) {
            UsageValidationError rejected =
                    assertThrows(UsageValidationError.class, () -> Freshness.parse(extended), "token " + extended);
            assertEquals(FailureKind.USAGE, rejected.kind(), "token " + extended);
        }
        assertEquals(
                "0000-01-01to9999-12-31",
                Freshness.parse("0000-01-01to9999-12-31").wireValue(),
                "the four-digit year boundaries themselves stay valid");
    }

    @Test
    void dateRangeConstructionRequiresYearsInsideTheFourDigitGrammar() {
        assertThrows(
                UsageValidationError.class,
                () -> new Freshness.DateRange(LocalDate.of(10000, 1, 1), LocalDate.of(10000, 1, 2)));
        assertThrows(
                UsageValidationError.class, () -> new Freshness.DateRange(LocalDate.of(-1, 6, 7), LocalDate.of(2026, 1, 1)));
        assertEquals(
                "0000-01-01to9999-12-31",
                new Freshness.DateRange(LocalDate.of(0, 1, 1), LocalDate.of(9999, 12, 31)).wireValue());
    }

    @Test
    void rejectsMalformedTokens() {
        for (String malformed :
                new String[] {"", " ", "pd ", "PD", "pw2", "2026-01-01to", "to2026-01-01", "2026-1-1to2026-01-02", "2026-01-01xto2026-01-02", "2026-01-01toto2026-01-02"}) {
            UsageValidationError rejected = assertThrows(UsageValidationError.class, () -> Freshness.parse(malformed));
            assertEquals(FailureKind.USAGE, rejected.kind(), "token " + malformed);
        }
    }

    @Test
    void directDateRangeConstructionRequiresBothDates() {
        assertThrows(UsageValidationError.class, () -> new Freshness.DateRange(null, LocalDate.of(2026, 1, 2)));
        assertThrows(UsageValidationError.class, () -> new Freshness.DateRange(LocalDate.of(2026, 1, 1), null));
    }
}

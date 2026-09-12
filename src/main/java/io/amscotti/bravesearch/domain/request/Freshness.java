package io.amscotti.bravesearch.domain.request;

import io.amscotti.bravesearch.domain.error.UsageValidationError;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * The freshness window of a search: one of the four documented recent periods or an
 * explicit inclusive date range.
 *
 * <p>The date range renders exactly {@code YYYY-MM-DDtoYYYY-MM-DD}: strict ISO calendar
 * dates — a date that does not exist is a usage failure, not a normalized neighbor — each
 * carrying exactly the four year digits the grammar shows, so signed or extended-year ISO
 * spellings are usage failures rather than silently re-rendered tokens; the start never
 * follows the end, and a single-day range is the equal-date corner. Parsing
 * accepts exactly the five documented token spellings.
 */
public sealed interface Freshness
        permits Freshness.RecentPd, Freshness.RecentPw, Freshness.RecentPm, Freshness.RecentPy, Freshness.DateRange {

    /** The endpoint's documented freshness token. */
    String wireValue();

    /** The past day window. */
    record RecentPd() implements Freshness {

        @Override
        public String wireValue() {
            return "pd";
        }
    }

    /** The past week window. */
    record RecentPw() implements Freshness {

        @Override
        public String wireValue() {
            return "pw";
        }
    }

    /** The past month window. */
    record RecentPm() implements Freshness {

        @Override
        public String wireValue() {
            return "pm";
        }
    }

    /** The past year window. */
    record RecentPy() implements Freshness {

        @Override
        public String wireValue() {
            return "py";
        }
    }

    /** An inclusive explicit date range; both dates present, real, and ordered. */
    record DateRange(LocalDate start, LocalDate end) implements Freshness {

        public DateRange {
            if (start == null || end == null) {
                throw new UsageValidationError("freshness date range requires both a start and an end date");
            }
            if (start.isAfter(end)) {
                throw new UsageValidationError("freshness date range must not start after its end date");
            }
            requireFourDigitYear(start);
            requireFourDigitYear(end);
        }

        @Override
        public String wireValue() {
            return start + "to" + end;
        }
    }

    /**
     * Parses one documented freshness token: {@code pd}, {@code pw}, {@code pm}, {@code py},
     * or {@code YYYY-MM-DDtoYYYY-MM-DD}.
     *
     * @throws UsageValidationError when the token matches no documented spelling or carries
     *     an impossible or unordered date
     */
    static Freshness parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        return switch (raw) {
            case "pd" -> new RecentPd();
            case "pw" -> new RecentPw();
            case "pm" -> new RecentPm();
            case "py" -> new RecentPy();
            default -> parseDateRange(raw);
        };
    }

    private static Freshness parseDateRange(String raw) {
        // a strict ISO date carries only digits and hyphens, so the first "to" is the separator
        int separator = raw.indexOf("to");
        if (separator < 0) {
            throw malformedFreshness();
        }
        return new DateRange(strictDate(raw.substring(0, separator)), strictDate(raw.substring(separator + 2)));
    }

    private static LocalDate strictDate(String text) {
        if (!hasFourDigitYearShape(text)) {
            throw malformedFreshness();
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException impossible) {
            throw malformedFreshness();
        }
    }

    /**
     * The documented grammar is exactly {@code dddd-dd-dd}: four year digits, no more —
     * strict ISO parsing alone would accept extended-year spellings such as
     * {@code +10000-06-07} and negative years such as {@code -0001-06-07}, whose wire
     * rendering leaves the documented token shape.
     */
    private static boolean hasFourDigitYearShape(String text) {
        if (text.length() != 10 || text.charAt(4) != '-' || text.charAt(7) != '-') {
            return false;
        }
        for (int index = 0; index < 10; index++) {
            char letter = text.charAt(index);
            if (index != 4 && index != 7 && (letter < '0' || letter > '9')) {
                return false;
            }
        }
        return true;
    }

    /** Every rendered date keeps the four-digit year of the documented token, so no signed or extended year reaches the wire. */
    private static void requireFourDigitYear(LocalDate date) {
        if (date.getYear() < 0 || date.getYear() > 9999) {
            throw malformedFreshness();
        }
    }

    private static UsageValidationError malformedFreshness() {
        return new UsageValidationError("freshness must be pd, pw, pm, py, or YYYY-MM-DDtoYYYY-MM-DD");
    }
}

package io.amscotti.bravesearch.adapter.bravehttp.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.domain.metadata.Usage;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Answers usage header contract: the documented {@code X-Request-*} response headers of a
 * blocking Answers call parse into counters and exact-scale decimal costs; malformed values
 * degrade to notes with the surviving fields kept; unknown {@code X-Request-*} names are
 * preserved verbatim in first-seen order.
 */
final class AnswersUsageParserTest {

    @Test
    void knownCountersAndCostsParseWithExactScale() {
        Usage usage = AnswersUsageParser.parse(
                Map.of(
                        "X-Request-Requests", List.of(" 2 "),
                        "X-Request-Queries", List.of("1"),
                        "X-Request-Tokens-In", List.of("1200"),
                        "X-Request-Tokens-Out", List.of("800"),
                        "X-Request-Requests-Cost", List.of("0.00010"),
                        "X-Request-Queries-Cost", List.of("0.00310"),
                        "X-Request-Tokens-In-Cost", List.of("0.000024"),
                        "X-Request-Tokens-Out-Cost", List.of("0.000096"),
                        "X-Request-Total-Cost", List.of("0.0042")));

        assertEquals(2L, usage.requests());
        assertEquals(1L, usage.queries());
        assertEquals(1200L, usage.tokensIn());
        assertEquals(800L, usage.tokensOut());
        assertEquals(new BigDecimal("0.00010"), usage.requestsCost());
        assertEquals(5, usage.requestsCost().scale(), "the decimal's exact scale survives parsing");
        assertEquals(new BigDecimal("0.00310"), usage.queriesCost());
        assertEquals(new BigDecimal("0.000024"), usage.tokensInCost());
        assertEquals(new BigDecimal("0.000096"), usage.tokensOutCost());
        assertEquals(new BigDecimal("0.0042"), usage.totalCost());
        assertEquals(List.of(), usage.notes());
        assertTrue(usage.unknownFields().isEmpty());
    }

    @Test
    void malformedValuesAreNonfatalAndPartialFieldsAreKept() {
        Usage usage = AnswersUsageParser.parse(
                Map.of(
                        "X-Request-Requests", List.of("two"),
                        "X-Request-Queries", List.of("1"),
                        "X-Request-Tokens-In", List.of("12.5"),
                        "X-Request-Total-Cost", List.of("NaN")));

        assertNull(usage.requests());
        assertNull(usage.tokensIn());
        assertNull(usage.totalCost());
        assertEquals(1L, usage.queries());
        assertEquals(3, usage.notes().size());
        assertTrue(usage.notes().contains("x-request-requests is not a nonnegative integer"), usage.notes()::toString);
        assertTrue(usage.notes().contains("x-request-tokens-in is not a nonnegative integer"), usage.notes()::toString);
        assertTrue(usage.notes().contains("x-request-total-cost is not a nonnegative decimal"), usage.notes()::toString);
    }

    @Test
    void negativeCountersAndCostsAreMalformed() {
        Usage usage = AnswersUsageParser.parse(
                Map.of(
                        "X-Request-Requests", List.of("-1"),
                        "X-Request-Total-Cost", List.of("-0.50")));

        assertNull(usage.requests());
        assertNull(usage.totalCost());
        assertTrue(usage.notes().contains("x-request-requests is not a nonnegative integer"), usage.notes()::toString);
        assertTrue(usage.notes().contains("x-request-total-cost is not a nonnegative decimal"), usage.notes()::toString);
    }

    @Test
    void unknownRequestHeaderNamesArePreservedVerbatimInFirstSeenOrder() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("X-Request-Research-Queries", List.of("4"));
        headers.put("x-request-future-metric", List.of("17", "extra physical line"));

        Usage usage = AnswersUsageParser.parse(headers);

        assertNull(usage.requests());
        assertEquals(
                Map.of(
                        "X-Request-Research-Queries", "4",
                        "x-request-future-metric", "17, extra physical line"),
                usage.unknownFields());
        assertEquals(
                List.of("X-Request-Research-Queries", "x-request-future-metric"),
                List.copyOf(usage.unknownFields().keySet()),
                "first-seen order is the preserved order");
        assertEquals(List.of(), usage.notes());
    }

    @Test
    void headerNamesMatchCaseInsensitively() {
        Usage usage = AnswersUsageParser.parse(
                Map.of(
                        "x-request-requests", List.of("2"),
                        "X-REQUEST-TOTAL-COST", List.of("0.0042")));

        assertEquals(2L, usage.requests());
        assertEquals(new BigDecimal("0.0042"), usage.totalCost());
    }

    @Test
    void absentRequestHeadersYieldNoUsage() {
        assertNull(
                AnswersUsageParser.parse(
                        Map.of(
                                "X-RateLimit-Limit", List.of("1"),
                                "Content-Type", List.of("application/json"))));
    }

    @Test
    void unknownOnlyHeadersStillYieldUsageWithoutCounters() {
        Usage usage = AnswersUsageParser.parse(Map.of("X-Request-Research-Seconds", List.of("14")));

        assertNotNull(usage);
        assertNull(usage.requests());
        assertNull(usage.totalCost());
        assertEquals(Map.of("X-Request-Research-Seconds", "14"), usage.unknownFields());
    }

    @Test
    void notesNeverQuoteHeaderValueText() {
        String sentinel = "sentinel-91bd3e";
        Usage usage = AnswersUsageParser.parse(
                Map.of(
                        "X-Request-Requests", List.of(sentinel),
                        "X-Request-Total-Cost", List.of(sentinel),
                        "X-Request-Research-Note", List.of(sentinel)));

        for (String note : usage.notes()) {
            assertFalse(note.contains(sentinel), "a note names the field, never the observed text: " + note);
        }
    }

    @Test
    void countersBeyondLongRangeDegradeToANote() {
        Usage usage = AnswersUsageParser.parse(Map.of("X-Request-Requests", List.of("99999999999999999999")));

        assertNull(usage.requests());
        assertEquals(List.of("x-request-requests does not fit a counter"), usage.notes());
    }

    @Test
    void nonAsciiDigitCountersAreMalformedNeverSilentlyParsed() {
        Usage usage = AnswersUsageParser.parse(Map.of("X-Request-Requests", List.of("٤٢")));

        assertNull(usage.requests(), "the wire grammar is ASCII digits, so Arabic-Indic forms never parse");
        assertEquals(1, usage.notes().size());
        assertTrue(
                usage.notes().getFirst().contains("not a nonnegative integer"), usage.notes()::toString);
    }

    @Test
    void hostileCostTokensDegradeToNotes() {
        Usage usage = AnswersUsageParser.parse(
                Map.of(
                        "X-Request-Total-Cost", List.of("Infinity"),
                        "X-Request-Requests-Cost", List.of("$0.50")));

        assertNull(usage.totalCost());
        assertNull(usage.requestsCost());
        assertEquals(2, usage.notes().size());
        assertTrue(
                usage.notes().contains("x-request-total-cost is not a nonnegative decimal"),
                usage.notes()::toString);
        assertTrue(
                usage.notes().contains("x-request-requests-cost is not a nonnegative decimal"),
                usage.notes()::toString);
    }

    @Test
    void aKnownHeaderWithAnEmptyValueListIsMalformedWithANote() {
        Usage usage = AnswersUsageParser.parse(Map.of("X-Request-Requests", List.of()));

        assertNull(usage.requests(), "a header line with no value at all carries nothing to parse");
        assertEquals(List.of("x-request-requests is present with no value"), usage.notes());
    }

    @Test
    void repeatedKnownHeadersApplyTheFirstValueWithANote() {
        Usage usage = AnswersUsageParser.parse(
                Map.of("X-Request-Requests", List.of("2", "9")));

        assertEquals(2L, usage.requests());
        assertEquals(
                List.of("x-request-requests is repeated; the first value applies"), usage.notes());
    }
}

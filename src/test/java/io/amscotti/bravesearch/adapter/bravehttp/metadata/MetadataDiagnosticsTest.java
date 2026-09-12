package io.amscotti.bravesearch.adapter.bravehttp.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import io.amscotti.bravesearch.domain.metadata.RateLimitWindow;
import io.amscotti.bravesearch.domain.metadata.Usage;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Human-readable diagnostic lines of one exchange's metadata: the window count, the
 * nonfatal notes verbatim, and a usage summary that names only reported fields. Lines are
 * data for a future verbose flag, so they quote counts and field names, never preserved
 * unknown header text.
 */
final class MetadataDiagnosticsTest {

    @Test
    void windowCountsAndNotesRenderAsDiagnosticLines() {
        RateLimitSnapshot snapshot = new RateLimitSnapshot(
                List.of(
                        new RateLimitWindow("request", 1, 0, Duration.ofSeconds(7)),
                        new RateLimitWindow("minute", 15, 14, Duration.ofSeconds(42)),
                        new RateLimitWindow("month", 2000, 1997, Duration.ofSeconds(26000))),
                List.of("x-ratelimit header lists are misaligned: limit=3, policy=3, remaining=2, reset=3; "
                        + "unaligned trailing positions dropped"),
                Instant.parse("2026-08-31T10:15:30Z"));
        Usage usage = new Usage(
                2L, 1L, 1200L, 800L,
                null, null, null, null,
                new BigDecimal("0.0042"),
                Map.of(),
                List.of("x-request-requests is repeated; the first value applies"));

        assertEquals(
                List.of(
                        "rate-limit windows: 3",
                        "x-ratelimit header lists are misaligned: limit=3, policy=3, remaining=2, reset=3;"
                                + " unaligned trailing positions dropped",
                        "answers usage: requests=2, queries=1, tokens_in=1200, tokens_out=800, total_cost=0.0042",
                        "x-request-requests is repeated; the first value applies"),
                MetadataDiagnostics.renderNotes(snapshot, usage));
    }

    @Test
    void absentMetadataRendersTheWindowCountLineOnly() {
        assertEquals(
                List.of("rate-limit windows: 0"),
                MetadataDiagnostics.renderNotes(
                        new RateLimitSnapshot(List.of(), List.of(), Instant.EPOCH), null));
    }

    @Test
    void nullUsageFieldsAreOmittedFromTheUsageLine() {
        Usage usage = new Usage(
                null, 1L, null, null, null, null, null, null, new BigDecimal("0.50"), Map.of(), List.of());

        assertEquals(
                List.of(
                        "rate-limit windows: 0",
                        "answers usage: queries=1, total_cost=0.50"),
                MetadataDiagnostics.renderNotes(new RateLimitSnapshot(List.of(), List.of(), Instant.EPOCH), usage));
    }

    @Test
    void usageWithoutKnownCountersSaysSo() {
        Usage usage = new Usage(null, null, null, null, null, null, null, null, null, Map.of(), List.of());

        assertEquals(
                List.of(
                        "rate-limit windows: 0",
                        "answers usage: no known counters reported"),
                MetadataDiagnostics.renderNotes(new RateLimitSnapshot(List.of(), List.of(), Instant.EPOCH), usage));
    }

    @Test
    void diagnosticsNeverQuotePreservedUnknownHeaderValues() {
        String sentinel = "sentinel-c417fa";
        Usage usage = new Usage(
                null, null, null, null, null, null, null, null, null,
                Map.of("X-Request-Research-Note", sentinel),
                List.of());

        for (String line : MetadataDiagnostics.renderNotes(
                new RateLimitSnapshot(List.of(), List.of(), Instant.EPOCH), usage)) {
            assertFalse(line.contains(sentinel), "a diagnostic line carries preserved upstream text: " + line);
        }
    }
}

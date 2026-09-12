package io.amscotti.bravesearch.domain.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Immutability contracts of the per-request metadata value. */
final class RequestMetaTest {

    @Test
    void rateLimitsAreCopiedOutOfTheCallerList() {
        List<RateLimitWindow> windows = new ArrayList<>();
        windows.add(new RateLimitWindow("request", 1, 1, Duration.ofMillis(250)));
        RequestMeta meta = new RequestMeta(null, 200, null, windows, AnswersUsageSamples.requestsOnly());
        windows.add(new RateLimitWindow("token", 2, 2, Duration.ZERO));
        assertEquals(1, meta.rateLimits().size());
    }

    @Test
    void absentRateLimitsNormalizeToAnEmptyList() {
        RequestMeta meta = new RequestMeta(null, 0, null, null, null);
        assertEquals(List.of(), meta.rateLimits());
    }

    @Test
    void usageTravelsUnchanged() {
        Usage usage = new Usage(
                3L, 2L, 1L, 5L,
                new BigDecimal("0.0001"),
                new BigDecimal("0.0031"),
                new BigDecimal("0.00002"),
                new BigDecimal("0.00098"),
                new BigDecimal("0.0042"),
                java.util.Map.of("X-Request-Research-Queries", "4"),
                List.of());
        RequestMeta meta = new RequestMeta("req-1", 200, "2024-08-01", List.of(), usage);
        assertEquals(usage, meta.usage());
    }

    /** A minimal usage value, so callers stay readable where the usage shape is not the subject. */
    private static final class AnswersUsageSamples {

        private static Usage requestsOnly() {
            return new Usage(1L, null, null, null, null, null, null, null, BigDecimal.ZERO, java.util.Map.of(), List.of());
        }
    }
}

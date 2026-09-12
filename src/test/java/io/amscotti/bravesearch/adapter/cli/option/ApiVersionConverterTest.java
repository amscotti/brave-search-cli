package io.amscotti.bravesearch.adapter.cli.option;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import picocli.CommandLine.TypeConversionException;

/** The Api-Version pin accepts exactly real ISO calendar dates and nothing else. */
final class ApiVersionConverterTest {

    private final ApiVersionConverter converter = new ApiVersionConverter();

    @Test
    void parsesARealCalendarDate() {
        assertEquals(LocalDate.of(2024, 6, 1), converter.convert("2024-06-01"));
        assertEquals(LocalDate.of(2026, 2, 28), converter.convert("2026-02-28"));
    }

    @Test
    void impossibleAndMalformedDatesAreTypedUsageFailures() {
        for (String candidate : new String[] {
            "2026-13-99", "2026-02-30", "2026-2-1", "2024-06-01x", "", "not-a-date", "+10000-01-01", "+0100-01-01"
        }) {
            TypeConversionException failure = assertThrows(
                    TypeConversionException.class, () -> converter.convert(candidate), () -> candidate);
            assertEquals("api-version must be a real calendar date in YYYY-MM-DD form", failure.getMessage());
        }
    }
}

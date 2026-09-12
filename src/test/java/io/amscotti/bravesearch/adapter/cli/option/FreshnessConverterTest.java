package io.amscotti.bravesearch.adapter.cli.option;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.amscotti.bravesearch.domain.request.Freshness;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import picocli.CommandLine.TypeConversionException;

/** The freshness option parses the five documented spellings and keeps the domain rejections. */
final class FreshnessConverterTest {

    private final FreshnessConverter converter = new FreshnessConverter();

    @Test
    void parsesTheRecentWindowsAndDatedRanges() {
        assertEquals(new Freshness.RecentPd(), converter.convert("pd"));
        assertEquals(new Freshness.RecentPw(), converter.convert("pw"));
        assertEquals(new Freshness.RecentPm(), converter.convert("pm"));
        assertEquals(new Freshness.RecentPy(), converter.convert("py"));
        assertEquals(
                new Freshness.DateRange(LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 3)),
                converter.convert("2024-01-02to2024-01-03"));
    }

    @Test
    void malformedSpellingKeepsTheGenericDomainRejectionText() {
        for (String candidate : new String[] {"soon", "2024-01-02to", "to2024-01-02"}) {
            TypeConversionException failure = assertThrows(
                    TypeConversionException.class, () -> converter.convert(candidate), () -> candidate);
            assertEquals("freshness must be pd, pw, pm, py, or YYYY-MM-DDtoYYYY-MM-DD", failure.getMessage());
        }
    }

    @Test
    void impossibleAndUnorderedDatesKeepTheirTypedDomainRejections() {
        assertEquals(
                "freshness date range must not start after its end date",
                rejectionOf("2026-03-01to2026-02-01"));
        assertEquals(
                "freshness must be pd, pw, pm, py, or YYYY-MM-DDtoYYYY-MM-DD",
                rejectionOf("2026-02-30to2026-03-01"),
                "a date that does not exist is the generic malformed spelling");
    }

    private String rejectionOf(String candidate) {
        TypeConversionException failure =
                assertThrows(TypeConversionException.class, () -> converter.convert(candidate), candidate);
        return failure.getMessage();
    }
}

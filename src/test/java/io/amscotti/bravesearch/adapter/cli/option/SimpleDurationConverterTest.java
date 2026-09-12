package io.amscotti.bravesearch.adapter.cli.option;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine.TypeConversionException;

/**
 * Parsing contracts of the duration option syntax: an unsigned integer with a {@code ms},
 * {@code s}, or {@code m} unit is the whole grammar — every other form, including ISO-8601
 * and the {@code ns} and {@code h} units, is a usage-grade rejection, as are zero, negative,
 * and overflowing values.
 */
final class SimpleDurationConverterTest {

    private final SimpleDurationConverter converter = new SimpleDurationConverter();

    @Test
    void acceptsUnsignedIntegersWithMsSMAndMUnitsOnly() {
        assertEquals(Duration.ofMillis(250), converter.convert("250ms"));
        assertEquals(Duration.ofSeconds(1), converter.convert("1s"));
        assertEquals(Duration.ofMinutes(2), converter.convert("2m"));
        assertEquals(Duration.ofSeconds(1), converter.convert(" 1s "), "surrounding whitespace is trimmed");
    }

    @Test
    void rejectsNanosHoursAndEveryIsoForm() {
        List<String> outOfGrammar = List.of("5ns", "1h", "PT2S", "PT0.25S", "PT1M30S", "P2D", "PT-0.5S");

        for (String candidate : outOfGrammar) {
            TypeConversionException rejected =
                    assertThrows(
                            TypeConversionException.class,
                            () -> converter.convert(candidate),
                            () -> "<" + candidate + "> is outside the duration grammar");
            assertTrue(rejected.getMessage().contains("duration"), () -> candidate + " rejection must say why");
        }
    }

    @Test
    void rejectsZeroDurationsInEveryAcceptedUnit() {
        for (String candidate : List.of("0s", "0ms", "0m")) {
            TypeConversionException rejected =
                    assertThrows(TypeConversionException.class, () -> converter.convert(candidate));
            assertTrue(rejected.getMessage().contains("positive"), () -> candidate + " rejection must say why");
        }
    }

    @Test
    void rejectsNegativeDurationsAsOutOfGrammar() {
        for (String candidate : List.of("-1s", "-250ms", "PT-1S", "PT-2M")) {
            assertThrows(
                    TypeConversionException.class,
                    () -> converter.convert(candidate),
                    () -> candidate + " must be rejected as a usage error");
        }
    }

    @Test
    void rejectsUnknownSuffixesAndNonsense() {
        List<String> nonsense = List.of("soon", "1w", "1 s", "s1", "1", "PT", "", "  ");

        for (String candidate : nonsense) {
            assertThrows(
                    TypeConversionException.class,
                    () -> converter.convert(candidate),
                    () -> "<" + candidate + "> must be rejected");
        }
    }

    @Test
    void rejectsValuesThatOverflowTheDurationRange() {
        for (String candidate : List.of("99999999999999999999s", "9999999999999999999m")) {
            assertThrows(
                    TypeConversionException.class,
                    () -> converter.convert(candidate),
                    () -> candidate + " must be rejected instead of overflowing");
        }
    }
}

package io.amscotti.bravesearch.adapter.cli.option;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.amscotti.bravesearch.domain.request.Units;
import org.junit.jupiter.api.Test;
import picocli.CommandLine.TypeConversionException;

/** The units option accepts exactly the two lowercase wire tokens. */
final class UnitsConverterTest {

    private final UnitsConverter converter = new UnitsConverter();

    @Test
    void parsesTheWireTokens() {
        assertEquals(Units.METRIC, converter.convert("metric"));
        assertEquals(Units.IMPERIAL, converter.convert("imperial"));
    }

    @Test
    void everyOtherSpellingIsATypedUsageFailure() {
        for (String candidate : new String[] {"METRIC", "Metric", "furlongs", "", " metric"}) {
            TypeConversionException failure = assertThrows(
                    TypeConversionException.class, () -> converter.convert(candidate), () -> candidate);
            assertEquals("units must be metric or imperial", failure.getMessage());
        }
    }
}

package io.amscotti.bravesearch.adapter.cli.option;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import picocli.CommandLine.TypeConversionException;

/**
 * The double converter of the coordinate options: every finite decimal spelling parses to
 * its double, while the non-finite spellings a range check cannot judge — {@code NaN}, the
 * infinities, and decimals whose double overflows to infinity — and every non-decimal text
 * fail as conversion errors, so no location header can leave the process carrying them.
 */
final class FiniteDoubleConverterTest {

    private final FiniteDoubleConverter converter = new FiniteDoubleConverter();

    @Test
    void finiteDecimalsParseToTheirDoubles() {
        assertEquals(Double.valueOf(0.0), converter.convert("0"));
        assertEquals(Double.valueOf(47.6), converter.convert("47.6"));
        assertEquals(Double.valueOf(-74.25), converter.convert("-74.25"));
        assertEquals(Double.valueOf(90.0), converter.convert("90.0"));
    }

    @Test
    void nonFiniteAndNonDecimalSpellingsFailConversion() {
        for (String forbidden : new String[] {"NaN", "Infinity", "-Infinity", "1e999", "near", "", "1,5", "0x1p3", "1.5f"}) {
            TypeConversionException failure =
                    assertThrows(TypeConversionException.class, () -> converter.convert(forbidden), forbidden);
            assertEquals("must be a finite decimal number", failure.getMessage(), forbidden);
        }
    }
}

package io.amscotti.bravesearch.adapter.cli.option;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import picocli.CommandLine.TypeConversionException;

/**
 * The decimal converter of the places radius option: every finite decimal spelling
 * parses at its exact given scale, while the non-finite spellings the endpoint forbids
 * — {@code NaN} and the infinities — and every non-decimal text fail as conversion
 * errors naming the value's kind, never as a silently accepted zero.
 */
final class FiniteDecimalConverterTest {

    private final FiniteDecimalConverter converter = new FiniteDecimalConverter();

    @Test
    void finiteDecimalsParseAtTheirExactGivenScale() {
        assertEquals(new BigDecimal("0"), converter.convert("0"));
        assertEquals(new BigDecimal("1500.500"), converter.convert("1500.500"));
        assertEquals(new BigDecimal("-0.25"), converter.convert("-0.25"));
    }

    @Test
    void nonFiniteAndNonDecimalSpellingsFailConversion() {
        for (String forbidden : new String[] {"NaN", "Infinity", "-Infinity", "near", "", "1,5"}) {
            assertThrows(TypeConversionException.class, () -> converter.convert(forbidden), forbidden);
        }
    }
}

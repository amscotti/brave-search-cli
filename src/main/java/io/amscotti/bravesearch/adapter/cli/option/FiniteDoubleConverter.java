package io.amscotti.bravesearch.adapter.cli.option;

import java.math.BigDecimal;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/**
 * Converts a decimal option value into its finite {@link Double}: the value parses through
 * the decimal grammar — the spellings a decimal cannot carry, {@code NaN} and the
 * infinities, fail before any request exists — and the resulting double must itself stay
 * finite, so a coordinate option never holds a value whose comparisons against a range
 * are vacuously false and whose header serialization would carry a non-finite spelling.
 */
public final class FiniteDoubleConverter implements ITypeConverter<Double> {

    private static final String REJECTION = "must be a finite decimal number";

    @Override
    public Double convert(String value) {
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(value);
        } catch (NumberFormatException notDecimal) {
            throw new TypeConversionException(REJECTION);
        }
        double candidate = decimal.doubleValue();
        if (!Double.isFinite(candidate)) {
            throw new TypeConversionException(REJECTION);
        }
        return candidate;
    }
}

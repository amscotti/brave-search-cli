package io.amscotti.bravesearch.adapter.cli.option;

import java.math.BigDecimal;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/**
 * Converts a decimal option value into its {@link BigDecimal}: every finite decimal
 * spelling parses at its exact given scale, while the spellings a decimal cannot
 * carry — {@code NaN}, the infinities, and every non-decimal text — fail as
 * conversion errors, so an endpoint whose decimals must be finite never receives a
 * non-finite or invented value.
 */
public final class FiniteDecimalConverter implements ITypeConverter<BigDecimal> {

    @Override
    public BigDecimal convert(String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException notDecimal) {
            throw new TypeConversionException(
                    "must be a finite decimal number (" + notDecimal.getClass().getSimpleName() + ")");
        }
    }
}

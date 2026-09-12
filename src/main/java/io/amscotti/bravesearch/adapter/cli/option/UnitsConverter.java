package io.amscotti.bravesearch.adapter.cli.option;

import io.amscotti.bravesearch.domain.request.Units;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/**
 * Converts a {@code --units} value into its {@link Units}: exactly the two lowercase wire
 * tokens, so no enum-name spelling or case variant quietly aliases a measurement system.
 */
public final class UnitsConverter implements ITypeConverter<Units> {

    private static final String REJECTION = "units must be metric or imperial";

    @Override
    public Units convert(String value) {
        for (Units unit : Units.values()) {
            if (unit.wireName().equals(value)) {
                return unit;
            }
        }
        throw new TypeConversionException(REJECTION);
    }
}

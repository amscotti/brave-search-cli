package io.amscotti.bravesearch.adapter.cli.option;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/**
 * Converts an {@code --api-version} value into its {@link LocalDate}: exactly a real calendar
 * date spelled {@code YYYY-MM-DD} with four digits of year — the extended-year form the
 * platform's own parser accepts (a leading {@code +} with more digits) is not the documented
 * spelling and never travels. Anything else is a typed usage failure raised before any
 * request exists, because a pin the upstream would reject must never leave the process.
 */
public final class ApiVersionConverter implements ITypeConverter<LocalDate> {

    private static final String REJECTION = "api-version must be a real calendar date in YYYY-MM-DD form";

    /** The documented spelling exactly: four ASCII digits, dash, two, dash, two. */
    private static final Pattern STRICT_DAY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    @Override
    public LocalDate convert(String value) {
        if (!STRICT_DAY.matcher(value).matches()) {
            throw new TypeConversionException(REJECTION);
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException impossible) {
            throw new TypeConversionException(REJECTION);
        }
    }
}

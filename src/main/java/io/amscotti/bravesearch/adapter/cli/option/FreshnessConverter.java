package io.amscotti.bravesearch.adapter.cli.option;

import io.amscotti.bravesearch.domain.error.UsageValidationError;
import io.amscotti.bravesearch.domain.request.Freshness;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/**
 * Converts a {@code --freshness} value into its {@link Freshness} through the domain's own
 * parse — the five documented spellings with real, ordered calendar dates — keeping the
 * domain's rejection text for everything else.
 */
public final class FreshnessConverter implements ITypeConverter<Freshness> {

    @Override
    public Freshness convert(String value) {
        try {
            return Freshness.parse(value);
        } catch (UsageValidationError invalid) {
            throw new TypeConversionException(invalid.getMessage());
        }
    }
}

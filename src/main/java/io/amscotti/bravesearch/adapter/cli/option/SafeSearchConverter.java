package io.amscotti.bravesearch.adapter.cli.option;

import io.amscotti.bravesearch.domain.error.UsageValidationError;
import io.amscotti.bravesearch.domain.request.SafeSearch;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/**
 * Converts a {@code --safe-search} value into its {@link SafeSearch} through the domain's
 * own exact-token parse, keeping the domain's rejection text and rejecting everything else —
 * case differences and surrounding whitespace included — as a typed usage failure.
 */
public final class SafeSearchConverter implements ITypeConverter<SafeSearch> {

    @Override
    public SafeSearch convert(String value) {
        try {
            return SafeSearch.parse(value);
        } catch (UsageValidationError invalid) {
            throw new TypeConversionException(invalid.getMessage());
        }
    }
}

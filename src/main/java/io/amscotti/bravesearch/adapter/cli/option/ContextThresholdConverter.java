package io.amscotti.bravesearch.adapter.cli.option;

import io.amscotti.bravesearch.domain.error.UsageValidationError;
import io.amscotti.bravesearch.domain.request.ContextThreshold;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/**
 * Converts a {@code --threshold} value into its {@link ContextThreshold} through the
 * domain's own exact-token parse, keeping the domain's rejection text and rejecting
 * everything else — case differences and surrounding whitespace included — as a typed
 * usage failure.
 */
public final class ContextThresholdConverter implements ITypeConverter<ContextThreshold> {

    @Override
    public ContextThreshold convert(String value) {
        try {
            return ContextThreshold.parse(value);
        } catch (UsageValidationError invalid) {
            throw new TypeConversionException(invalid.getMessage());
        }
    }
}

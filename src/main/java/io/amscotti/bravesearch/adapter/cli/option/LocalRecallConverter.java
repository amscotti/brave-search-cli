package io.amscotti.bravesearch.adapter.cli.option;

import io.amscotti.bravesearch.domain.error.UsageValidationError;
import io.amscotti.bravesearch.domain.request.LocalRecall;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/**
 * Converts a {@code --local} value into its {@link LocalRecall} through the domain's own
 * exact-token parse, keeping the domain's rejection text and rejecting everything else —
 * case differences and surrounding whitespace included — as a typed usage failure.
 */
public final class LocalRecallConverter implements ITypeConverter<LocalRecall> {

    @Override
    public LocalRecall convert(String value) {
        try {
            return LocalRecall.parse(value);
        } catch (UsageValidationError invalid) {
            throw new TypeConversionException(invalid.getMessage());
        }
    }
}

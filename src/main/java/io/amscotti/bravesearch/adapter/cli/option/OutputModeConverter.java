package io.amscotti.bravesearch.adapter.cli.option;

import io.amscotti.bravesearch.domain.output.OutputMode;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/**
 * Converts an {@code --output} value into its {@link OutputMode}.
 *
 * <p>The grammar is exact: the four lowercase wire words and nothing else. Case differences
 * and surrounding whitespace are usage errors, so no spelling quietly aliases a channel.
 */
public final class OutputModeConverter implements ITypeConverter<OutputMode> {

    @Override
    public OutputMode convert(String value) {
        return OutputMode.fromWireName(value)
                .orElseThrow(() -> new TypeConversionException(
                        "invalid --output value '" + value + "': expected exactly one of human, json, jsonl, raw"));
    }
}

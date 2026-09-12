package io.amscotti.bravesearch.adapter.cli.option;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.amscotti.bravesearch.domain.output.OutputMode;
import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine.TypeConversionException;

/** The output-mode grammar: exactly four lowercase wire words, nothing else parses. */
final class OutputModeConverterTest {

    @Test
    void acceptsExactlyTheFourLowercaseWireNames() {
        OutputModeConverter converter = new OutputModeConverter();
        assertEquals(OutputMode.HUMAN, converter.convert("human"));
        assertEquals(OutputMode.JSON, converter.convert("json"));
        assertEquals(OutputMode.JSONL, converter.convert("jsonl"));
        assertEquals(OutputMode.RAW, converter.convert("raw"));
    }

    @Test
    void rejectsEveryOtherSpelling() {
        for (String rejected :
                List.of("JSON", "Json", "human ", " human", "json ", "jsonl\t", "", "text", "human,json", "Human")) {
            assertThrows(
                    TypeConversionException.class,
                    () -> new OutputModeConverter().convert(rejected),
                    () -> "the converter must reject '" + rejected + "' as a usage error");
        }
    }
}

package io.amscotti.bravesearch.adapter.cli.option;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.amscotti.bravesearch.domain.request.SafeSearch;
import org.junit.jupiter.api.Test;
import picocli.CommandLine.TypeConversionException;

/** The safe-search option accepts exactly the three documented tokens. */
final class SafeSearchConverterTest {

    private final SafeSearchConverter converter = new SafeSearchConverter();

    @Test
    void parsesTheDocumentedTokens() {
        assertEquals(SafeSearch.OFF, converter.convert("off"));
        assertEquals(SafeSearch.MODERATE, converter.convert("moderate"));
        assertEquals(SafeSearch.STRICT, converter.convert("strict"));
    }

    @Test
    void everyOtherSpellingKeepsTheDomainRejectionText() {
        for (String candidate : new String[] {"OFF", " strict", "strict ", "bogus", ""}) {
            TypeConversionException failure = assertThrows(
                    TypeConversionException.class, () -> converter.convert(candidate), () -> candidate);
            assertEquals("safe-search must be off, moderate, or strict", failure.getMessage());
        }
    }
}

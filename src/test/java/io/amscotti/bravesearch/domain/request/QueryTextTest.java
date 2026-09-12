package io.amscotti.bravesearch.domain.request;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.amscotti.bravesearch.domain.error.UsageValidationError;
import org.junit.jupiter.api.Test;

/**
 * The query rules every search endpoint shares: one to four hundred Unicode code points,
 * one to fifty whitespace-delimited words, never all whitespace, and the text itself is
 * never normalized or trimmed — valid input stays byte-for-byte as given.
 */
final class QueryTextTest {

    @Test
    void acceptsOneWordAndFourHundredCodePointsExactly() {
        assertDoesNotThrow(() -> new QueryText("q"));
        assertDoesNotThrow(() -> new QueryText("x".repeat(400)));
        assertDoesNotThrow(() -> new QueryText("café ☕ déjà vu"));
    }

    @Test
    void rejectsTheEmptyQueryAndTheAllWhitespaceQuery() {
        assertThrows(UsageValidationError.class, () -> new QueryText(""));
        assertThrows(UsageValidationError.class, () -> new QueryText("   "));
        assertThrows(UsageValidationError.class, () -> new QueryText("\t\n \u00A0"));
    }

    @Test
    void rejectsTheFourHundredAndFirstCodePointCountingUnicodeNotChars() {
        // one astral character is two chars but one code point: 400 of them stay inside the bound
        String fourHundredAstral = "\uD83E\uDDEA".repeat(400);
        assertDoesNotThrow(() -> new QueryText(fourHundredAstral));
        assertThrows(UsageValidationError.class, () -> new QueryText(fourHundredAstral + "\uD83E\uDD25"));
        assertThrows(UsageValidationError.class, () -> new QueryText("x".repeat(401)));
    }

    @Test
    void rejectsTheFiftyFirstWhitespaceDelimitedWord() {
        assertDoesNotThrow(() -> new QueryText(String.join(" ", java.util.Collections.nCopies(50, "word"))));
        assertThrows(
                UsageValidationError.class, () -> new QueryText(String.join(" ", java.util.Collections.nCopies(51, "word"))));
    }

    @Test
    void unicodeWhitespaceSeparatesWordsAndTheTextStaysUnchanged() {
        QueryText kept = new QueryText("café\u00A0☕  déjà");
        assertEquals("café\u00A0☕  déjà", kept.text(), "valid input is transmitted exactly as given");
        assertEquals(3, countWordsOf("one two three"));
        assertEquals(1, countWordsOf("surrogate\ud83e\uddeaPair"));
    }

    @Test
    void rejectionsCarryTheSharedRuleText() {
        assertEquals(
                "query must be between 1 and 400 code points",
                assertThrows(UsageValidationError.class, () -> new QueryText("")).getMessage());
        assertEquals(
                "query must carry at most 50 whitespace-delimited words",
                assertThrows(UsageValidationError.class, () -> new QueryText(String.join(" ", java.util.Collections.nCopies(51, "w"))))
                        .getMessage());
    }

    private static int countWordsOf(String text) {
        return new QueryText(text).wordCount();
    }
}

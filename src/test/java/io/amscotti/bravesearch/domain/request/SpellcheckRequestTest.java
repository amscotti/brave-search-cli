package io.amscotti.bravesearch.domain.request;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.UsageValidationError;
import org.junit.jupiter.api.Test;

/**
 * Validation contract of the spellcheck request: the spellcheck endpoint documents
 * exactly the query, the country, and the language hint — nothing else — so the request
 * carries exactly those members and the endpoint's own query rule (the shared 1–400
 * code-point, at-most-50-words, never-all-whitespace rule the live reference states for
 * spellcheck) plus the documented country spellings and the blank-present language
 * rejection. No count, no SafeSearch, no freshness, and no page member exists at all.
 */
final class SpellcheckRequestTest {

    @Test
    void acceptsAQueryOfExactlyFourHundredCodePoints() {
        assertEquals("a".repeat(400), SpellcheckRequest.builder("a".repeat(400)).build().query());
    }

    @Test
    void rejectsAQueryBeyondTheDocumentedLimits() {
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> SpellcheckRequest.builder("a".repeat(401)).build())
                        .kind());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> SpellcheckRequest.builder("").build()).kind());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(
                                UsageValidationError.class,
                                () -> SpellcheckRequest.builder(" \t\n\r\f\u000B").build())
                        .kind());
    }

    @Test
    void acceptsTheTwoLetterCountryCodeAndTheRegionWideSpelling() {
        assertDoesNotThrow(() -> SpellcheckRequest.builder("q").country("US").build());
        assertDoesNotThrow(() -> SpellcheckRequest.builder("q").country("ALL").build());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> SpellcheckRequest.builder("q").country("USA").build())
                        .kind());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> SpellcheckRequest.builder("q").country("deutsch").build())
                        .kind());
    }

    @Test
    void rejectsBlankPresentLanguage() {
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> SpellcheckRequest.builder("q").lang(" ").build())
                        .kind());
    }

    @Test
    void theBareQueryCarriesOnlyTheQuery() {
        SpellcheckRequest bare = SpellcheckRequest.builder("q").build();

        assertNull(bare.country());
        assertNull(bare.lang());
    }
}

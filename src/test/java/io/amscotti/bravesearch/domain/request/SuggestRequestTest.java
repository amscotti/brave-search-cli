package io.amscotti.bravesearch.domain.request;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.UsageValidationError;
import org.junit.jupiter.api.Test;

/**
 * Validation contract of the suggest request: the suggest endpoint's own query rule —
 * the same 1–400 code-point, at-most-50-words, never-all-whitespace rule the search
 * verticals document — the suggest-only count boundary (1..20, because Brave's default
 * of 5 must stay an omission, never a coerced wire value), the documented country
 * spellings, the blank-present language rejection, and the tri-state rich flag. The
 * endpoint documents no page, no interface language, no SafeSearch, and no freshness, so
 * the request carries none of those members at all.
 */
final class SuggestRequestTest {

    @Test
    void acceptsAQueryOfExactlyFourHundredCodePoints() {
        assertEquals("a".repeat(400), SuggestRequest.builder("a".repeat(400)).build().query());
    }

    @Test
    void rejectsAQueryBeyondTheDocumentedLimits() {
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> SuggestRequest.builder("a".repeat(401)).build())
                        .kind());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> SuggestRequest.builder("").build()).kind());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(
                                UsageValidationError.class,
                                () -> SuggestRequest.builder(" \t\n\r\f\u000B").build())
                        .kind());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(
                                UsageValidationError.class,
                                () -> SuggestRequest.builder(String.join(" ", java.util.Collections.nCopies(51, "w")))
                                        .build())
                        .kind());
    }

    @Test
    void acceptsTheDocumentedCountRangeAndRejectsBothEdgesBeyondIt() {
        assertDoesNotThrow(() -> SuggestRequest.builder("q").count(1).build());
        assertDoesNotThrow(() -> SuggestRequest.builder("q").count(20).build());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> SuggestRequest.builder("q").count(0).build())
                        .kind());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> SuggestRequest.builder("q").count(21).build())
                        .kind());
    }

    @Test
    void acceptsTheTwoLetterCountryCodeAndTheRegionWideSpelling() {
        assertDoesNotThrow(() -> SuggestRequest.builder("q").country("US").build());
        assertDoesNotThrow(() -> SuggestRequest.builder("q").country("ALL").build());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> SuggestRequest.builder("q").country("USA").build())
                        .kind());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> SuggestRequest.builder("q").country("D").build())
                        .kind());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> SuggestRequest.builder("q").country(" ").build())
                        .kind());
    }

    @Test
    void rejectsBlankPresentLanguage() {
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> SuggestRequest.builder("q").lang(" ").build())
                        .kind());
    }

    @Test
    void richStaysNullUntilSupplied() {
        assertNull(SuggestRequest.builder("q").build().rich());
        assertSame(Boolean.TRUE, SuggestRequest.builder("q").rich(true).build().rich());
        assertSame(Boolean.FALSE, SuggestRequest.builder("q").rich(false).build().rich());
    }

    @Test
    void theBareQueryCarriesOnlyTheQuery() {
        SuggestRequest bare = SuggestRequest.builder("q").build();

        assertNull(bare.country());
        assertNull(bare.lang());
        assertNull(bare.count());
        assertNull(bare.rich());
    }
}

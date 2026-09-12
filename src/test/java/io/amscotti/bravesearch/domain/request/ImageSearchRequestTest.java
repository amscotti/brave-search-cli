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
 * Validation contract of the images search request: the shared query rules, the
 * endpoint's own count boundary (1..200 — wider than every other vertical), the
 * documented country spellings (a two-letter code or the region-wide {@code ALL}), the
 * images-only SafeSearch restriction ({@code moderate} is not a documented images level),
 * the blank-present rejection, and the tri-state spellcheck — every rejection a typed
 * usage failure. The images endpoint documents no page, no interface language, and no
 * freshness, so the request carries none of those members at all.
 */
final class ImageSearchRequestTest {

    @Test
    void acceptsAQueryOfExactlyFourHundredCodePoints() {
        assertEquals("a".repeat(400), ImageSearchRequest.builder("a".repeat(400)).build().query());
    }

    @Test
    void rejectsAQueryBeyondTheSharedLimits() {
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> ImageSearchRequest.builder("a".repeat(401)).build())
                        .kind());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> ImageSearchRequest.builder("").build()).kind());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(
                                UsageValidationError.class,
                                () -> ImageSearchRequest.builder(" \t\n\r\f\u000B").build())
                        .kind());
    }

    @Test
    void acceptsTheDocumentedCountRangeAndRejectsBothEdgesBeyondIt() {
        assertDoesNotThrow(() -> ImageSearchRequest.builder("q").count(1).build());
        assertDoesNotThrow(() -> ImageSearchRequest.builder("q").count(200).build());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> ImageSearchRequest.builder("q").count(0).build())
                        .kind());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> ImageSearchRequest.builder("q").count(201).build())
                        .kind());
    }

    @Test
    void moderateSafeSearchIsAUsageRejectionOfTheImagesEndpoint() {
        UsageValidationError rejected =
                assertThrows(UsageValidationError.class, () -> ImageSearchRequest.builder("q")
                        .safeSearch(SafeSearch.MODERATE)
                        .build());

        assertEquals(FailureKind.USAGE, rejected.kind());
        assertEquals("safe-search must be off or strict for images", rejected.getMessage());
    }

    @Test
    void theDocumentedSafeSearchLevelsBindOntoTheRequest() {
        assertEquals(ImageSafeSearch.OFF, ImageSearchRequest.builder("q").safeSearch(SafeSearch.OFF).build().safeSearch());
        assertEquals(
                ImageSafeSearch.STRICT,
                ImageSearchRequest.builder("q").safeSearch(SafeSearch.STRICT).build().safeSearch());
    }

    @Test
    void acceptsTheTwoLetterCountryCodeAndTheRegionWideSpelling() {
        assertDoesNotThrow(() -> ImageSearchRequest.builder("q").country("US").build());
        assertDoesNotThrow(() -> ImageSearchRequest.builder("q")
                .country("all".toUpperCase(java.util.Locale.ROOT))
                .build());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> ImageSearchRequest.builder("q").country("USA").build())
                        .kind());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> ImageSearchRequest.builder("q").country("D").build())
                        .kind());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> ImageSearchRequest.builder("q").country(" ").build())
                        .kind());
    }

    @Test
    void rejectsBlankPresentSearchLanguage() {
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> ImageSearchRequest.builder("q").searchLang(" ").build())
                        .kind());
    }

    @Test
    void spellcheckStaysNullUntilSupplied() {
        assertNull(ImageSearchRequest.builder("q").build().spellcheck());
        assertSame(Boolean.FALSE, ImageSearchRequest.builder("q").spellcheck(false).build().spellcheck());
    }

    @Test
    void theBareQueryCarriesOnlyTheQuery() {
        ImageSearchRequest bare = ImageSearchRequest.builder("q").build();

        assertNull(bare.country());
        assertNull(bare.searchLang());
        assertNull(bare.safeSearch());
        assertNull(bare.count());
        assertNull(bare.spellcheck());
    }
}

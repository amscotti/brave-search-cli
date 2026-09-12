package io.amscotti.bravesearch.domain.request;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.UsageValidationError;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Validation contract of the videos search request: the shared query rules, the endpoint's
 * own count and page boundaries (count 1..50, page 1..10), the offset mapping, the
 * documented country spellings (a two-letter code or the region-wide {@code ALL}), the
 * blank-present rejections, and the tri-state booleans — every rejection a typed usage
 * failure. The videos endpoint documents no goggles, so the request carries no goggle
 * member at all.
 */
final class VideoSearchRequestTest {

    @Test
    void acceptsAQueryOfExactlyFourHundredCodePoints() {
        assertEquals("a".repeat(400), VideoSearchRequest.builder("a".repeat(400)).build().query());
    }

    @Test
    void rejectsAQueryBeyondTheSharedLimits() {
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> VideoSearchRequest.builder("a".repeat(401)).build())
                        .kind());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> VideoSearchRequest.builder("").build()).kind());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(
                                UsageValidationError.class,
                                () -> VideoSearchRequest.builder(" \t\n\r\f\u000B").build())
                        .kind());
    }

    @Test
    void acceptsTheDocumentedCountRangeAndRejectsBothEdgesBeyondIt() {
        assertDoesNotThrow(() -> VideoSearchRequest.builder("q").count(1).build());
        assertDoesNotThrow(() -> VideoSearchRequest.builder("q").count(50).build());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> VideoSearchRequest.builder("q").count(0).build())
                        .kind());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> VideoSearchRequest.builder("q").count(51).build())
                        .kind());
    }

    @Test
    void acceptsTheDocumentedPageRangeAndRejectsBothEdgesBeyondIt() {
        assertDoesNotThrow(() -> VideoSearchRequest.builder("q").page(1).build());
        assertDoesNotThrow(() -> VideoSearchRequest.builder("q").page(10).build());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> VideoSearchRequest.builder("q").page(0).build())
                        .kind());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> VideoSearchRequest.builder("q").page(11).build())
                        .kind());
    }

    @Test
    void theUserFacingPageTravelsAsItsZeroBasedUpstreamOffset() {
        assertNull(VideoSearchRequest.builder("q").build().upstreamOffset());
        assertEquals(0, VideoSearchRequest.builder("q").page(1).build().upstreamOffset());
        assertEquals(9, VideoSearchRequest.builder("q").page(10).build().upstreamOffset());
    }

    @Test
    void withPageReplacesOnlyThePageOfAnOtherwiseIdenticalRequest() {
        VideoSearchRequest original = VideoSearchRequest.builder("hello world")
                .country("DE")
                .searchLang("de")
                .uiLang("de-DE")
                .safeSearch(SafeSearch.OFF)
                .freshness(new Freshness.DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28)))
                .count(7)
                .page(3)
                .spellcheck(false)
                .includeFetchMetadata(true)
                .operators(false)
                .build();

        VideoSearchRequest replaced = original.withPage(5);

        assertEquals(5, replaced.page());
        assertEquals(original.query(), replaced.query());
        assertEquals(original.country(), replaced.country());
        assertEquals(original.searchLang(), replaced.searchLang());
        assertEquals(original.uiLang(), replaced.uiLang());
        assertEquals(original.safeSearch(), replaced.safeSearch());
        assertEquals(original.freshness(), replaced.freshness());
        assertEquals(original.count(), replaced.count());
        assertEquals(original.spellcheck(), replaced.spellcheck());
        assertEquals(original.includeFetchMetadata(), replaced.includeFetchMetadata());
        assertEquals(original.operators(), replaced.operators());
    }

    @Test
    void acceptsTheTwoLetterCountryCodeAndTheRegionWideSpelling() {
        assertDoesNotThrow(() -> VideoSearchRequest.builder("q").country("US").build());
        assertDoesNotThrow(() -> VideoSearchRequest.builder("q").country("all".toUpperCase(java.util.Locale.ROOT)).build());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> VideoSearchRequest.builder("q").country("USA").build())
                        .kind());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> VideoSearchRequest.builder("q").country("D").build())
                        .kind());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> VideoSearchRequest.builder("q").country(" ").build())
                        .kind());
    }

    @Test
    void rejectsBlankPresentLanguageValues() {
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> VideoSearchRequest.builder("q").searchLang(" ").build())
                        .kind());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> VideoSearchRequest.builder("q").uiLang("\t").build())
                        .kind());
    }

    @Test
    void theTriStateBooleansStayNullUntilSupplied() {
        VideoSearchRequest bare = VideoSearchRequest.builder("q").build();
        assertNull(bare.spellcheck());
        assertNull(bare.includeFetchMetadata());
        assertNull(bare.operators());
        assertSame(Boolean.FALSE, VideoSearchRequest.builder("q").spellcheck(false).build().spellcheck());
    }
}

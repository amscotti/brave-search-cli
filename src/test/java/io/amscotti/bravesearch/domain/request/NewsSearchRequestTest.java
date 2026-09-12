package io.amscotti.bravesearch.domain.request;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.UsageValidationError;
import io.amscotti.bravesearch.domain.goggles.Goggle;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Validation contract of the news search request: the shared query rules, the endpoint's own
 * count and page boundaries (count 1..50, page 1..10), the offset mapping, the documented
 * country spellings (a two-letter code or the region-wide {@code ALL}), the blank-present
 * rejections, and the goggle maximum — every rejection a typed usage failure.
 */
final class NewsSearchRequestTest {

    @Test
    void acceptsAQueryOfExactlyFourHundredCodePoints() {
        assertEquals("a".repeat(400), NewsSearchRequest.builder("a".repeat(400)).build().query());
    }

    @Test
    void rejectsAQueryBeyondTheSharedLimits() {
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> NewsSearchRequest.builder("a".repeat(401)).build())
                        .kind());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> NewsSearchRequest.builder("").build()).kind());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(
                                UsageValidationError.class,
                                () -> NewsSearchRequest.builder(" \t\n\r\f\u000B").build())
                        .kind());
    }

    @Test
    void acceptsTheDocumentedCountRangeAndRejectsBothEdgesBeyondIt() {
        assertDoesNotThrow(() -> NewsSearchRequest.builder("q").count(1).build());
        assertDoesNotThrow(() -> NewsSearchRequest.builder("q").count(50).build());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> NewsSearchRequest.builder("q").count(0).build())
                        .kind());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> NewsSearchRequest.builder("q").count(51).build())
                        .kind());
    }

    @Test
    void acceptsTheDocumentedPageRangeAndRejectsBothEdgesBeyondIt() {
        assertDoesNotThrow(() -> NewsSearchRequest.builder("q").page(1).build());
        assertDoesNotThrow(() -> NewsSearchRequest.builder("q").page(10).build());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> NewsSearchRequest.builder("q").page(0).build())
                        .kind());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> NewsSearchRequest.builder("q").page(11).build())
                        .kind());
    }

    @Test
    void theUserFacingPageTravelsAsItsZeroBasedUpstreamOffset() {
        assertNull(NewsSearchRequest.builder("q").build().upstreamOffset());
        assertEquals(0, NewsSearchRequest.builder("q").page(1).build().upstreamOffset());
        assertEquals(9, NewsSearchRequest.builder("q").page(10).build().upstreamOffset());
    }

    @Test
    void withPageReplacesOnlyThePageOfAnOtherwiseIdenticalRequest() {
        NewsSearchRequest original = NewsSearchRequest.builder("hello world")
                .country("DE")
                .searchLang("de")
                .uiLang("de-DE")
                .safeSearch(SafeSearch.OFF)
                .freshness(new Freshness.DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28)))
                .count(7)
                .page(3)
                .spellcheck(false)
                .extraSnippets(true)
                .includeFetchMetadata(true)
                .operators(false)
                .goggles(List.of(new Goggle.UrlReference("https://example.com/goggle")))
                .build();

        NewsSearchRequest replaced = original.withPage(5);

        assertEquals(5, replaced.page());
        assertEquals(original.query(), replaced.query());
        assertEquals(original.country(), replaced.country());
        assertEquals(original.searchLang(), replaced.searchLang());
        assertEquals(original.uiLang(), replaced.uiLang());
        assertEquals(original.safeSearch(), replaced.safeSearch());
        assertEquals(original.freshness(), replaced.freshness());
        assertEquals(original.count(), replaced.count());
        assertEquals(original.spellcheck(), replaced.spellcheck());
        assertEquals(original.extraSnippets(), replaced.extraSnippets());
        assertEquals(original.includeFetchMetadata(), replaced.includeFetchMetadata());
        assertEquals(original.operators(), replaced.operators());
        assertEquals(original.goggles(), replaced.goggles());
    }

    @Test
    void acceptsTheTwoLetterCountryCodeAndTheRegionWideSpelling() {
        assertDoesNotThrow(() -> NewsSearchRequest.builder("q").country("US").build());
        assertDoesNotThrow(() -> NewsSearchRequest.builder("q").country("all".toUpperCase(java.util.Locale.ROOT)).build());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> NewsSearchRequest.builder("q").country("USA").build())
                        .kind());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> NewsSearchRequest.builder("q").country("D").build())
                        .kind());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> NewsSearchRequest.builder("q").country(" ").build())
                        .kind());
    }

    @Test
    void rejectsBlankPresentLanguageValues() {
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> NewsSearchRequest.builder("q").searchLang(" ").build())
                        .kind());
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> NewsSearchRequest.builder("q").uiLang("\t").build())
                        .kind());
    }

    @Test
    void atMostThreeGogglesRideOneRequest() {
        List<Goggle> three = List.of(
                new Goggle.UrlReference("https://example.com/one"),
                new Goggle.UrlReference("https://example.com/two"),
                new Goggle.UrlReference("https://example.com/three"));
        assertDoesNotThrow(() -> NewsSearchRequest.builder("q").goggles(three).build());
        List<Goggle> four = List.of(
                new Goggle.UrlReference("https://example.com/one"),
                new Goggle.UrlReference("https://example.com/two"),
                new Goggle.UrlReference("https://example.com/three"),
                new Goggle.UrlReference("https://example.com/four"));
        assertEquals(
                FailureKind.USAGE,
                assertThrows(UsageValidationError.class, () -> NewsSearchRequest.builder("q").goggles(four).build())
                        .kind());
    }

    @Test
    void anAbsentGoggleListBecomesTheEmptyList() {
        assertEquals(List.of(), NewsSearchRequest.builder("q").build().goggles());
    }

    @Test
    void theTriStateBooleansStayNullUntilSupplied() {
        NewsSearchRequest bare = NewsSearchRequest.builder("q").build();
        assertNull(bare.spellcheck());
        assertNull(bare.extraSnippets());
        assertNull(bare.includeFetchMetadata());
        assertNull(bare.operators());
        assertSame(Boolean.FALSE, NewsSearchRequest.builder("q").spellcheck(false).build().spellcheck());
    }
}

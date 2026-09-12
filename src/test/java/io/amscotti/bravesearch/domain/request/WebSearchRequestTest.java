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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Validation contract of the web search request: the shared query rules (code points,
 * whitespace-delimited words, all-whitespace rejection, verbatim transmission), the numeric
 * boundaries of count and page, the offset mapping, blank-present rejections, and the
 * location pairing, range, code, and zone rules — every rejection a typed usage failure.
 */
final class WebSearchRequestTest {

    /** One supplementary code point (a musical symbol) carried as a surrogate pair. */
    private static final String SUPPLEMENTARY = "\uD834\uDD1E";

    @Test
    void acceptsAQueryOfExactlyFourHundredCodePoints() {
        assertEquals("a".repeat(400), WebSearchRequest.builder("a".repeat(400)).build().query());
    }

    @Test
    void rejectsAQueryBeyondFourHundredCodePoints() {
        UsageValidationError rejected =
                assertThrows(UsageValidationError.class, () -> WebSearchRequest.builder("a".repeat(401)).build());
        assertEquals(FailureKind.USAGE, rejected.kind());
    }

    @Test
    void countsSupplementaryCodePointsAsSingleUnits() {
        // 250 supplementary notes span 500 char units but only 250 code points: accepted
        assertDoesNotThrow(() -> WebSearchRequest.builder(SUPPLEMENTARY.repeat(250)).build());
        // 401 supplementary notes are 401 code points: rejected
        assertThrows(UsageValidationError.class, () -> WebSearchRequest.builder(SUPPLEMENTARY.repeat(401)).build());
    }

    @Test
    void combiningCharactersJoinTheirWordInsteadOfSplittingIt() {
        // 60 combining-decorated letters form one 120-code-point word, not 60 words
        assertDoesNotThrow(() -> WebSearchRequest.builder("e\u0301".repeat(60)).build());
    }

    @Test
    void rejectsTheEmptyQuery() {
        UsageValidationError rejected =
                assertThrows(UsageValidationError.class, () -> WebSearchRequest.builder("").build());
        assertEquals(FailureKind.USAGE, rejected.kind());
    }

    @Test
    void rejectsTheAllWhitespaceQuery() {
        UsageValidationError rejected = assertThrows(
                UsageValidationError.class, () -> WebSearchRequest.builder(" \t\n\r\f\u000B").build());
        assertEquals(FailureKind.USAGE, rejected.kind());
    }

    @Test
    void rejectsANullQuery() {
        assertThrows(NullPointerException.class, () -> WebSearchRequest.builder(null));
    }

    @Test
    void acceptsAQueryOfExactlyFiftyWords() {
        assertDoesNotThrow(() -> WebSearchRequest.builder("w" + " w".repeat(49)).build());
    }

    @Test
    void rejectsAQueryBeyondFiftyWords() {
        assertThrows(UsageValidationError.class, () -> WebSearchRequest.builder("w" + " w".repeat(50)).build());
    }

    @Test
    void everyJavaWhitespaceAndSpaceKindDelimitsWords() {
        // NBSP is a space character but not Java whitespace; both delimiter kinds count
        assertDoesNotThrow(() -> WebSearchRequest.builder("w\u00A0".repeat(49) + "w").build());
        assertThrows(UsageValidationError.class, () -> WebSearchRequest.builder("w\u00A0".repeat(50) + "w").build());
    }

    @Test
    void validQueriesTravelVerbatimWithoutTrimming() {
        WebSearchRequest request = WebSearchRequest.builder("  padded\tquery  ").build();
        assertEquals("  padded\tquery  ", request.query(), "no normalization or trimming of valid input");
    }

    @Test
    void acceptsCountAtBothBoundsAndRejectsBeyondThem() {
        assertDoesNotThrow(() -> WebSearchRequest.builder("q").count(1).build());
        assertDoesNotThrow(() -> WebSearchRequest.builder("q").count(20).build());
        assertThrows(UsageValidationError.class, () -> WebSearchRequest.builder("q").count(0).build());
        assertThrows(UsageValidationError.class, () -> WebSearchRequest.builder("q").count(21).build());
    }

    @Test
    void acceptsPageAtBothBoundsAndRejectsBeyondThem() {
        assertDoesNotThrow(() -> WebSearchRequest.builder("q").page(1).build());
        assertDoesNotThrow(() -> WebSearchRequest.builder("q").page(10).build());
        assertThrows(UsageValidationError.class, () -> WebSearchRequest.builder("q").page(0).build());
        assertThrows(UsageValidationError.class, () -> WebSearchRequest.builder("q").page(11).build());
    }

    @Test
    void exposesTheZeroBasedUpstreamOffsetOfTheUserFacingPage() {
        assertEquals(0, WebSearchRequest.builder("q").page(1).build().upstreamOffset());
        assertEquals(9, WebSearchRequest.builder("q").page(10).build().upstreamOffset());
        assertNull(WebSearchRequest.builder("q").build().upstreamOffset());
    }

    @Test
    void withPageReplacesThePageAndKeepsEveryOtherMember() {
        WebSearchRequest request = WebSearchRequest.builder("q")
                .country("US")
                .count(5)
                .page(2)
                .safeSearch(SafeSearch.STRICT)
                .location(new WebSearchRequest.Location(40.5, -73.5, null, null, null, null, null, null))
                .build();

        WebSearchRequest fourth = request.withPage(4);

        assertEquals(4, fourth.page());
        assertEquals(3, fourth.upstreamOffset());
        assertEquals("q", fourth.query());
        assertEquals("US", fourth.country());
        assertEquals(5, fourth.count());
        assertEquals(SafeSearch.STRICT, fourth.safeSearch());
        assertEquals(request.location(), fourth.location(), "every untouched member travels unchanged");
        assertThrows(UsageValidationError.class, () -> request.withPage(11), "the replacement page obeys the page rules");
    }

    @Test
    void rejectsBlankPresentCountryAndLanguageValues() {
        for (String blank : new String[] {"", " ", "\t"}) {
            assertThrows(UsageValidationError.class, () -> WebSearchRequest.builder("q").country(blank).build());
            assertThrows(UsageValidationError.class, () -> WebSearchRequest.builder("q").searchLang(blank).build());
            assertThrows(UsageValidationError.class, () -> WebSearchRequest.builder("q").uiLang(blank).build());
        }
        assertDoesNotThrow(() ->
                WebSearchRequest.builder("q").country("US").searchLang("en").uiLang("en-US").build());
    }

    @Test
    void countryAcceptsExactlyTwoLetterCodesSoAllIsRefused() {
        for (String accepted : new String[] {"US", "de"}) {
            assertDoesNotThrow(() -> WebSearchRequest.builder("q").country(accepted).build(), "country " + accepted);
        }
        // ALL is the region-wide spelling of the news endpoint only; web documents a two-letter code
        for (String refused : new String[] {"ALL", "DEU", "1A", "D-", " ", "Usa", ""}) {
            assertThrows(UsageValidationError.class, () -> WebSearchRequest.builder("q").country(refused).build(), "country " + refused);
        }
    }

    @Test
    void safeSearchParsingAcceptsExactlyTheDocumentedTokens() {
        assertEquals(SafeSearch.OFF, SafeSearch.parse("off"));
        assertEquals(SafeSearch.MODERATE, SafeSearch.parse("moderate"));
        assertEquals(SafeSearch.STRICT, SafeSearch.parse("strict"));
        for (String invalid : new String[] {"Off", "STRICT", "safe", ""}) {
            UsageValidationError rejected = assertThrows(UsageValidationError.class, () -> SafeSearch.parse(invalid));
            assertEquals(FailureKind.USAGE, rejected.kind(), "spelling " + invalid);
        }
    }

    @Test
    void safeSearchWireNamesRoundTripThroughParsing() {
        for (SafeSearch level : SafeSearch.values()) {
            assertSame(level, SafeSearch.parse(level.wireName()));
        }
    }

    @Test
    void resultFiltersAreCopiedValidatedAndDefaultToNone() {
        List<String> source = new ArrayList<>(List.of("web", "news"));
        WebSearchRequest request = WebSearchRequest.builder("q").resultFilters(source).build();
        source.add("videos");
        assertEquals(List.of("web", "news"), request.resultFilters(), "the request owns a defensive copy");
        assertEquals(List.of(), WebSearchRequest.builder("q").build().resultFilters());
        assertThrows(UsageValidationError.class, () ->
                WebSearchRequest.builder("q").resultFilters(List.of("web", "")).build());
        assertThrows(NullPointerException.class, () ->
                WebSearchRequest.builder("q").resultFilters(Arrays.asList("web", null)).build());
    }

    @Test
    void aLocationMayBeEntirelyAbsent() {
        assertDoesNotThrow(() -> new WebSearchRequest.Location(null, null, null, null, null, null, null, null));
        assertNull(WebSearchRequest.builder("q").build().location());
    }

    @Test
    void coordinatesMustBeSuppliedTogether() {
        assertThrows(UsageValidationError.class, () -> located(47.6, null));
        assertThrows(UsageValidationError.class, () -> located(null, -122.3));
        assertDoesNotThrow(() -> located(47.6, -122.3));
    }

    @Test
    void coordinatesAcceptTheirBoundaryValuesAndRejectBeyondThem() {
        assertDoesNotThrow(() -> located(-90.0, -180.0));
        assertDoesNotThrow(() -> located(90.0, 180.0));
        assertThrows(UsageValidationError.class, () -> located(90.0001, 0.0));
        assertThrows(UsageValidationError.class, () -> located(-90.5, 0.0));
        assertThrows(UsageValidationError.class, () -> located(0.0, 180.0001));
        assertThrows(UsageValidationError.class, () -> located(0.0, -181.0));
    }

    @Test
    void coordinatesRejectTheNonFiniteDoublesARangeComparisonCannotJudge() {
        assertEquals(
                "loc-lat must be between -90 and 90",
                assertThrows(UsageValidationError.class, () -> located(Double.NaN, 0.0)).getMessage());
        assertEquals(
                "loc-long must be between -180 and 180",
                assertThrows(UsageValidationError.class, () -> located(0.0, Double.NaN)).getMessage());
        assertThrows(UsageValidationError.class, () -> located(Double.POSITIVE_INFINITY, 0.0));
        assertThrows(UsageValidationError.class, () -> located(0.0, Double.NEGATIVE_INFINITY));
    }

    @Test
    void stateAcceptsOnlyTwoUppercaseLetters() {
        assertDoesNotThrow(() -> withState("WA"));
        for (String invalid : new String[] {"wa", "W", "WAS", "W1", "W A", ""}) {
            assertThrows(UsageValidationError.class, () -> withState(invalid), "state " + invalid);
        }
    }

    @Test
    void locationCountryAcceptsOnlyTwoUppercaseLetters() {
        assertDoesNotThrow(() -> withLocationCountry("US"));
        for (String invalid : new String[] {"usa", "U", "us", "U1", ""}) {
            assertThrows(UsageValidationError.class, () -> withLocationCountry(invalid), "country " + invalid);
        }
    }

    @Test
    void theTimezoneMustBeARealIanaZone() {
        assertDoesNotThrow(() -> withTimezone("America/Los_Angeles"));
        assertDoesNotThrow(() -> withTimezone("UTC"));
        UsageValidationError rejected = assertThrows(UsageValidationError.class, () -> withTimezone("Not/AZone"));
        assertEquals(FailureKind.USAGE, rejected.kind());
    }

    @Test
    void presentLocationStringsMustNotBeBlank() {
        assertThrows(UsageValidationError.class, () -> withCity(""));
        assertThrows(UsageValidationError.class, () -> withCity(" "));
        assertThrows(UsageValidationError.class, () -> withStateName("\t"));
        assertThrows(UsageValidationError.class, () -> withPostalCode(""));
    }

    @Test
    void gogglesDefaultToAnEmptyList() {
        assertEquals(List.of(), WebSearchRequest.builder("q").build().goggles());
    }

    @Test
    void atMostThreeGogglesRideOneRequest() {
        Goggle.UrlReference first = new Goggle.UrlReference("https://example.com/one");
        Goggle.UrlReference second = new Goggle.UrlReference("https://example.com/two");
        Goggle.UrlReference third = new Goggle.UrlReference("https://example.com/three");

        assertEquals(
                List.of(first, second, third),
                WebSearchRequest.builder("q").goggles(List.of(first, second, third)).build().goggles());
        assertThrows(
                UsageValidationError.class,
                () -> WebSearchRequest.builder("q")
                        .goggles(List.of(first, second, third, new Goggle.UrlReference("https://example.com/four")))
                        .build());
    }

    @Test
    void aNullGoggleEntryIsRejected() {
        assertThrows(NullPointerException.class, () -> WebSearchRequest.builder("q")
                .goggles(java.util.Arrays.asList((Goggle) null)).build());
    }

    @Test
    void aPageReplacementKeepsEveryGoggle() {
        Goggle.Inline inline = new Goggle.Inline("+site:example.com");
        WebSearchRequest request = WebSearchRequest.builder("q").page(1).goggles(List.of(inline)).build();

        assertEquals(List.of(inline), request.withPage(2).goggles());
    }

    @Test
    void theBuilderPopulatesEveryField() {
        WebSearchRequest.Location location =
                new WebSearchRequest.Location(1.5, -2.5, "c", "WA", "Washington", "US", "98101", "UTC");
        Freshness freshness = new Freshness.DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        WebSearchRequest request = WebSearchRequest.builder("q")
                .country("DE")
                .searchLang("de")
                .uiLang("de-DE")
                .safeSearch(SafeSearch.STRICT)
                .freshness(freshness)
                .count(7)
                .page(3)
                .spellcheck(false)
                .textDecorations(false)
                .resultFilters(List.of("web", "news"))
                .units(Units.IMPERIAL)
                .extraSnippets(true)
                .includeFetchMetadata(true)
                .operators(false)
                .enableRichCallback(true)
                .location(location)
                .build();

        assertEquals("q", request.query());
        assertEquals("DE", request.country());
        assertEquals("de", request.searchLang());
        assertEquals("de-DE", request.uiLang());
        assertEquals(SafeSearch.STRICT, request.safeSearch());
        assertEquals(freshness, request.freshness());
        assertEquals(7, request.count());
        assertEquals(3, request.page());
        assertEquals(2, request.upstreamOffset());
        assertEquals(Boolean.FALSE, request.spellcheck());
        assertEquals(Boolean.FALSE, request.textDecorations());
        assertEquals(List.of("web", "news"), request.resultFilters());
        assertEquals(Units.IMPERIAL, request.units());
        assertEquals(Boolean.TRUE, request.extraSnippets());
        assertEquals(Boolean.TRUE, request.includeFetchMetadata());
        assertEquals(Boolean.FALSE, request.operators());
        assertEquals(Boolean.TRUE, request.enableRichCallback());
        assertEquals(location, request.location());
    }

    private static WebSearchRequest.Location located(Double latitude, Double longitude) {
        return new WebSearchRequest.Location(latitude, longitude, null, null, null, null, null, null);
    }

    private static WebSearchRequest.Location withCity(String city) {
        return new WebSearchRequest.Location(null, null, city, null, null, null, null, null);
    }

    private static WebSearchRequest.Location withState(String state) {
        return new WebSearchRequest.Location(null, null, null, state, null, null, null, null);
    }

    private static WebSearchRequest.Location withStateName(String stateName) {
        return new WebSearchRequest.Location(null, null, null, null, stateName, null, null, null);
    }

    private static WebSearchRequest.Location withLocationCountry(String country) {
        return new WebSearchRequest.Location(null, null, null, null, null, country, null, null);
    }

    private static WebSearchRequest.Location withPostalCode(String postalCode) {
        return new WebSearchRequest.Location(null, null, null, null, null, null, postalCode, null);
    }

    private static WebSearchRequest.Location withTimezone(String timezone) {
        return new WebSearchRequest.Location(null, null, null, null, null, null, null, timezone);
    }
}

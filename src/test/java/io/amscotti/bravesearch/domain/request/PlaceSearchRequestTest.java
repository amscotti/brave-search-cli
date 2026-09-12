package io.amscotti.bravesearch.domain.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.amscotti.bravesearch.domain.error.UsageValidationError;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The places request contract: the query is always optional — omitting it with an
 * anchor is Explore mode and omitting both query and anchor is a valid broad global
 * search the CLI never rejects locally — coordinates arrive paired inside their ranges,
 * the coordinate pair refuses the place-name anchor, the geoloc value validates both
 * components and serializes exactly {@code latitudexlongitude}, the geoloc and radius
 * decimals stay plain — no exponent spelling, bounded fraction scale and digit count —
 * so the radius accepts every finite plain decimal at zero or above, and the count
 * keeps its documented total-budget
 * range across every response bucket, and the place-name anchor rejects the control
 * characters and edge whitespace that must never reach a location string.
 */
final class PlaceSearchRequestTest {

    @Test
    void querylessAnchorlessRequestIsValidAndNeverRejectedLocally() {
        PlaceSearchRequest request = PlaceSearchRequest.builder().build();

        assertNull(request.query());
        assertNull(request.anchor());
        assertNull(request.radius());
        assertNull(request.count());
        assertNull(request.geoloc());
    }

    @Test
    void exploreRequestCarriesTheAnchorWithoutAnyQuery() {
        PlaceSearchRequest request =
                PlaceSearchRequest.builder().anchor(new PlaceAnchor.LocationName("Philadelphia PA US")).build();

        assertNull(request.query());
        assertEquals(new PlaceAnchor.LocationName("Philadelphia PA US"), request.anchor());
    }

    @Test
    void latitudeRequiresLongitudeAndViceVersa() {
        UsageValidationError missingLongitude = assertThrows(
                UsageValidationError.class,
                () -> PlaceSearchRequest.builder().anchor(PlaceAnchor.of(40.69, null, null)));
        assertEquals("latitude and longitude must be supplied together", missingLongitude.getMessage());

        UsageValidationError missingLatitude = assertThrows(
                UsageValidationError.class,
                () -> PlaceSearchRequest.builder().anchor(PlaceAnchor.of(null, -74.25, null)));
        assertEquals("latitude and longitude must be supplied together", missingLatitude.getMessage());
    }

    @Test
    void theCoordinatePairRefusesTheLocationName() {
        UsageValidationError conflict = assertThrows(
                UsageValidationError.class,
                () -> PlaceSearchRequest.builder().anchor(PlaceAnchor.of(40.69, -74.25, "Philadelphia PA US")));

        assertEquals("latitude and longitude cannot be combined with location", conflict.getMessage());
    }

    @Test
    void bothAnchorCoordinatesStayInsideTheirDocumentedRanges() {
        UsageValidationError latitude = assertThrows(
                UsageValidationError.class,
                () -> PlaceSearchRequest.builder().anchor(new PlaceAnchor.Coordinates(90.01, -74.25)));
        assertEquals("latitude must be between -90 and 90", latitude.getMessage());

        UsageValidationError longitude = assertThrows(
                UsageValidationError.class,
                () -> PlaceSearchRequest.builder().anchor(new PlaceAnchor.Coordinates(40.69, -180.5)));
        assertEquals("longitude must be between -180 and 180", longitude.getMessage());

        UsageValidationError nonFiniteLatitude = assertThrows(
                UsageValidationError.class,
                () -> PlaceSearchRequest.builder().anchor(new PlaceAnchor.Coordinates(Double.NaN, -74.25)));
        assertEquals("latitude must be between -90 and 90", nonFiniteLatitude.getMessage());

        UsageValidationError nonFiniteLongitude = assertThrows(
                UsageValidationError.class,
                () -> PlaceSearchRequest.builder().anchor(new PlaceAnchor.Coordinates(40.69, Double.NaN)));
        assertEquals("longitude must be between -180 and 180", nonFiniteLongitude.getMessage());

        PlaceSearchRequest edges =
                PlaceSearchRequest.builder().anchor(new PlaceAnchor.Coordinates(-90.0, 180.0)).build();
        assertEquals(new PlaceAnchor.Coordinates(-90.0, 180.0), edges.anchor());
    }

    @Test
    void geolocRangeChecksBothComponentsAndSerializesExactlyLatitudexLongitude() {
        assertEquals("40.69x-74.25", GeoLoc.parse("40.69x-74.25").wireForm());
        assertEquals(
                "40.690x-74.250", GeoLoc.parse("40.690x-74.250").wireForm(), "the given scale survives verbatim");

        UsageValidationError latitude = assertThrows(UsageValidationError.class, () -> GeoLoc.parse("91x0"));
        assertEquals("geoloc latitude must be between -90 and 90", latitude.getMessage());

        UsageValidationError longitude = assertThrows(UsageValidationError.class, () -> GeoLoc.parse("0x181"));
        assertEquals("geoloc longitude must be between -180 and 180", longitude.getMessage());
    }

    @Test
    void geolocRejectsEverySpellingThatIsNotTwoDecimalCoordinates() {
        for (String malformed : new String[] {"", "40.69", "x", "40.69x", "x-74.25", "40.69xx-74.25", "NaNx0",
            "0xInfinity", "40.69x-74.25x1"}) {
            UsageValidationError rejected = assertThrows(
                    UsageValidationError.class, () -> GeoLoc.parse(malformed), "spelling <" + malformed + ">");
            assertEquals("geoloc must be two decimal coordinates spelled latitudexlongitude", rejected.getMessage());
        }
    }

    @Test
    void radiusAcceptsZeroAndEveryFiniteDecimalButNothingNegative() {
        assertEquals(new BigDecimal("0"), PlaceSearchRequest.builder().radius(new BigDecimal("0")).build().radius());
        assertEquals(
                new BigDecimal("1500.500"),
                PlaceSearchRequest.builder().radius(new BigDecimal("1500.500")).build().radius());

        UsageValidationError negative =
                assertThrows(UsageValidationError.class, () -> PlaceSearchRequest.builder().radius(new BigDecimal("-1")).build());
        assertEquals("radius must be zero or greater", negative.getMessage());
    }

    @Test
    void geolocRefusesExponentSpellingsBeforeTheyCanReRenderTheWireForm() {
        for (String exponent : new String[] {"1e2x5", "4e-2x5", "4E-2x5", "40.69x1e2", "1e2147483647x0",
            "0x1e-2147483647"}) {
            UsageValidationError rejected = assertThrows(
                    UsageValidationError.class, () -> GeoLoc.parse(exponent), "spelling <" + exponent + ">");
            assertEquals("geoloc must be two decimal coordinates spelled latitudexlongitude", rejected.getMessage());
        }
    }

    @Test
    void geolocRefusesComponentsBeyondTheSharedDecimalBounds() {
        String beyondFractionScale = "0." + "0".repeat(100) + "1";
        UsageValidationError latitude = assertThrows(
                UsageValidationError.class,
                () -> GeoLoc.parse(beyondFractionScale + "x0"),
                "a 101-fraction-digit component must never reach a wire form");
        assertEquals("geoloc latitude must carry at most 100 fraction digits", latitude.getMessage());

        UsageValidationError longitude = assertThrows(
                UsageValidationError.class, () -> GeoLoc.parse("0x" + beyondFractionScale));
        assertEquals("geoloc longitude must carry at most 100 fraction digits", longitude.getMessage());

        UsageValidationError exponent = assertThrows(
                UsageValidationError.class, () -> new GeoLoc(new BigDecimal("1e2"), new BigDecimal("0")));
        assertEquals("geoloc latitude must be a plain decimal, not an exponent spelling", exponent.getMessage());

        String largestScale = "0." + "0".repeat(99) + "1";
        assertEquals(largestScale + "x0", GeoLoc.parse(largestScale + "x0").wireForm());
    }

    @Test
    void radiusRefusesExponentSpellingsAndUnboundedDigitCounts() {
        for (BigDecimal exponent : new BigDecimal[] {new BigDecimal("1e2"), new BigDecimal("1e2147483647")}) {
            UsageValidationError rejected = assertThrows(
                    UsageValidationError.class,
                    () -> PlaceSearchRequest.builder().radius(exponent).build(),
                    "spelling <" + exponent + ">");
            assertEquals("radius must be a plain decimal, not an exponent spelling", rejected.getMessage());
        }

        UsageValidationError fractionScale = assertThrows(
                UsageValidationError.class,
                () -> PlaceSearchRequest.builder().radius(new BigDecimal("1e-2147483647")).build());
        assertEquals("radius must carry at most 100 fraction digits", fractionScale.getMessage());

        UsageValidationError significantDigits = assertThrows(
                UsageValidationError.class,
                () -> PlaceSearchRequest.builder().radius(new BigDecimal("1".repeat(1001))).build());
        assertEquals("radius must carry at most 1000 significant digits", significantDigits.getMessage());

        PlaceSearchRequest largestPlainScales = PlaceSearchRequest.builder()
                .radius(new BigDecimal("0." + "0".repeat(99) + "1"))
                .build();
        assertEquals(new BigDecimal("0." + "0".repeat(99) + "1"), largestPlainScales.radius());
        assertEquals(new BigDecimal("1".repeat(1000)), PlaceSearchRequest.builder()
                .radius(new BigDecimal("1".repeat(1000)))
                .build()
                .radius());
    }

    @Test
    void acceptsTheDocumentedCountRangeAndRejectsBothEdgesBeyondIt() {
        assertEquals(1, PlaceSearchRequest.builder().count(1).build().count());
        assertEquals(100, PlaceSearchRequest.builder().count(100).build().count());

        for (int beyond : new int[] {0, 101}) {
            UsageValidationError rejected =
                    assertThrows(UsageValidationError.class, () -> PlaceSearchRequest.builder().count(beyond).build());
            assertEquals("count must be between 1 and 100", rejected.getMessage());
        }
    }

    @Test
    void theQueryKeepsTheSharedTextRulesExactlyWhenPresent() {
        assertNull(PlaceSearchRequest.builder().build().query());
        assertEquals("three word query", PlaceSearchRequest.builder().query("three word query").build().query());

        UsageValidationError blank = assertThrows(UsageValidationError.class, () -> PlaceSearchRequest.builder().query("   ").build());
        assertEquals("query must carry at least one non-whitespace word", blank.getMessage());

        UsageValidationError tooManyWords = assertThrows(
                UsageValidationError.class,
                () -> PlaceSearchRequest.builder().query(String.join(" ", java.util.Collections.nCopies(51, "w"))).build());
        assertEquals(
                "query must carry at most 50 whitespace-delimited words", tooManyWords.getMessage());
    }

    @Test
    void locationNameRejectsControlCharactersAndEdgeWhitespace() {
        UsageValidationError leading =
                assertThrows(UsageValidationError.class, () -> new PlaceAnchor.LocationName(" Philadelphia"));
        assertEquals("location must not begin or end with whitespace", leading.getMessage());

        UsageValidationError trailing =
                assertThrows(UsageValidationError.class, () -> new PlaceAnchor.LocationName("Philadelphia "));
        assertEquals("location must not begin or end with whitespace", trailing.getMessage());

        UsageValidationError blank = assertThrows(UsageValidationError.class, () -> new PlaceAnchor.LocationName("  "));
        assertEquals("location must not be blank when supplied", blank.getMessage());

        for (String adversarial : new String[] {"Phil\radelphia", "Phil\nadelphia", "Phil\0adelphia", "Phil\badelphia",
            "Phil\u0007adelphia"}) {
            UsageValidationError control = assertThrows(
                    UsageValidationError.class,
                    () -> new PlaceAnchor.LocationName(adversarial),
                    "spelling must be refused before any header map");
            assertEquals("location must not carry control characters", control.getMessage());
        }
    }

    @Test
    void countryAcceptsExactlyTwoLettersAndNothingRegionWide() {
        assertEquals("US", PlaceSearchRequest.builder().country("US").build().country());

        for (String undocumented : new String[] {"ALL", "USA", "u"}) {
            UsageValidationError rejected = assertThrows(
                    UsageValidationError.class, () -> PlaceSearchRequest.builder().country(undocumented).build());
            assertEquals("country must be a two-letter code", rejected.getMessage());
        }
    }

    @Test
    void languageTagsStayNonblankWhenPresent() {
        UsageValidationError search = assertThrows(
                UsageValidationError.class, () -> PlaceSearchRequest.builder().searchLang(" ").build());
        assertEquals("search-lang must not be blank when supplied", search.getMessage());

        UsageValidationError ui = assertThrows(UsageValidationError.class, () -> PlaceSearchRequest.builder().uiLang(" ").build());
        assertEquals("ui-lang must not be blank when supplied", ui.getMessage());
    }

    @Test
    void fullRequestCarriesEveryOptionalMemberWithoutCoercingOne() {
        PlaceSearchRequest request = PlaceSearchRequest.builder()
                .query("coffee")
                .anchor(new PlaceAnchor.Coordinates(40.69, -74.25))
                .radius(new BigDecimal("1500"))
                .count(50)
                .geoloc(GeoLoc.parse("40.69x-74.25"))
                .units(Units.IMPERIAL)
                .country("US")
                .searchLang("en")
                .uiLang("en-US")
                .safeSearch(SafeSearch.MODERATE)
                .spellcheck(false)
                .build();

        assertEquals("coffee", request.query());
        assertEquals(new PlaceAnchor.Coordinates(40.69, -74.25), request.anchor());
        assertEquals(new BigDecimal("1500"), request.radius());
        assertEquals(50, request.count());
        assertEquals("40.69x-74.25", request.geoloc().wireForm());
        assertEquals(Units.IMPERIAL, request.units());
        assertEquals("US", request.country());
        assertEquals("en", request.searchLang());
        assertEquals("en-US", request.uiLang());
        assertEquals(SafeSearch.MODERATE, request.safeSearch());
        assertEquals(Boolean.FALSE, request.spellcheck());
    }
}

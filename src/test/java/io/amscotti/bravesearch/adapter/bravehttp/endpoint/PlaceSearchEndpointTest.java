package io.amscotti.bravesearch.adapter.bravehttp.endpoint;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.BraveApiRequest;
import io.amscotti.bravesearch.application.exchange.ExpectedUserAgent;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.request.GeoLoc;
import io.amscotti.bravesearch.domain.request.PlaceAnchor;
import io.amscotti.bravesearch.domain.request.PlaceSearchRequest;
import io.amscotti.bravesearch.domain.request.SafeSearch;
import io.amscotti.bravesearch.domain.request.Units;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Assembly of the places search wire form, pinned to the checked-in upstream contract:
 * a full-parameter request renders one percent-encoded GET URI over exactly the
 * documented field inventory of {@code /local/place_search}, the geoloc value rides
 * the wire exactly as {@code latitudexlongitude}, every unsupplied value is omitted
 * rather than coerced to an upstream default, and the query stays optional — an
 * explore request carries its anchor without any {@code q}, and a query-less
 * anchor-less request carries neither.
 */
final class PlaceSearchEndpointTest {

    private static final BraveApiOrigin PRODUCTION = BraveApiOrigin.production();

    /** The pinned full-parameter rendering: every documented places wire field supplied. */
    private static final String FULL_QUERY = "count=50&country=US&geoloc=40.690x-74.250&latitude=40.69"
            + "&longitude=-74.25&q=hello%20world&radius=1500.500&safesearch=moderate&search_lang=en"
            + "&spellcheck=true&ui_lang=en-US&units=imperial";

    @Test
    void fullParameterRequestRendersTheExactEncodedUri() throws Exception {
        BraveApiRequest assembled = PlaceSearchEndpoint.assemble(fullRequest(), PRODUCTION, token("opaque-test-token"));

        assertEquals("https://api.search.brave.com/res/v1/local/place_search?" + FULL_QUERY, assembled.uri().toString());
        assertEquals(BraveApiRequest.Method.GET, assembled.method());
        assertNull(assembled.bodyBytes());
        assertFalse(assembled.rawResponseMode());
        assertEquals(
                List.of(
                        new BraveApiRequest.HeaderLine("X-Subscription-Token", "opaque-test-token"),
                        new BraveApiRequest.HeaderLine("Accept", "application/json"),
                        new BraveApiRequest.HeaderLine("Accept-Encoding", "gzip"),
                        new BraveApiRequest.HeaderLine("User-Agent", ExpectedUserAgent.fromVersionResource())),
                assembled.headers(),
                "the places endpoint documents no location header group, so the fixed assembly is the whole header set");
    }

    @Test
    void exploreRequestCarriesTheAnchorWithoutAnyQueryParameter() {
        PlaceSearchRequest explore = PlaceSearchRequest.builder()
                .anchor(new PlaceAnchor.LocationName("Philadelphia PA US"))
                .build();

        String query = queryOf(explore);

        assertEquals("location=Philadelphia%20PA%20US", query);
        assertFalse(query.contains("q="), "an explore request omits the query entirely");
    }

    @Test
    void querylessAnchorlessRequestCarriesNoAnchorAndNoQuery() {
        BraveApiRequest assembled = assemble(PlaceSearchRequest.builder().build());

        assertNull(assembled.uri().getRawQuery(), "the broad global search travels as the bare endpoint path");
        assertEquals("/res/v1/local/place_search", assembled.uri().getPath());
    }

    @Test
    void everyUnsuppliedValueIsOmittedFromTheWire() {
        BraveApiRequest assembled = assemble(PlaceSearchRequest.builder().query("café ☕").build());

        assertEquals("q=caf%C3%A9%20%E2%98%95", assembled.uri().getRawQuery());
        assertEquals("/res/v1/local/place_search", assembled.uri().getPath());
        assertEquals(4, assembled.headers().size(), "only the fixed assembly travels: " + assembled.headers());
    }

    @Test
    void geolocRidesTheWireExactlyAsLatitudexLongitude() {
        String query = queryOf(PlaceSearchRequest.builder().geoloc(GeoLoc.parse("40.690x-74.250")).build());

        assertTrue(query.contains("geoloc=40.690x-74.250"), "the serialized geoloc keeps the given scale: " + query);
    }

    @Test
    void radiusKeepsItsGivenScaleAndBothSpellingsOfTheUnitsTravel() {
        assertEquals("radius=1500.500&units=imperial", queryOf(PlaceSearchRequest.builder()
                .radius(new BigDecimal("1500.500"))
                .units(Units.IMPERIAL)
                .build()));
        assertEquals("units=metric", queryOf(PlaceSearchRequest.builder().units(Units.METRIC).build()));
    }

    @Test
    void anApiVersionPinRidesTheFixedAssemblyExactlyWhenSupplied() {
        BraveApiRequest pinned =
                PlaceSearchEndpoint.assemble(PlaceSearchRequest.builder().build(), PRODUCTION, token("t"), "2024-06-01");

        assertEquals(
                new BraveApiRequest.HeaderLine("Api-Version", "2024-06-01"),
                pinned.headers().get(4),
                "the pin follows the User-Agent line of the fixed assembly");

        BraveApiRequest unpinned =
                PlaceSearchEndpoint.assemble(PlaceSearchRequest.builder().build(), PRODUCTION, token("t"), null);

        assertEquals(4, unpinned.headers().size(), "no pin line travels without a pin: " + unpinned.headers());
    }

    @Test
    void theMethodStaysGetEvenBeyondTheSharedPostTriggerLength() {
        PlaceSearchRequest beyond = PlaceSearchRequest.builder()
                .anchor(new PlaceAnchor.LocationName("a".repeat(7970)))
                .build();

        BraveApiRequest assembled = assemble(beyond);

        assertEquals(
                BraveApiRequest.Method.GET,
                assembled.method(),
                "the places endpoint documents no POST form, so the length rule never switches methods here");
        assertTrue(assembled.uri().getRawQuery().contains("location="), "the long value rides the GET query");
        assertNull(assembled.bodyBytes());
    }

    private static PlaceSearchRequest fullRequest() {
        return PlaceSearchRequest.builder()
                .query("hello world")
                .anchor(new PlaceAnchor.Coordinates(40.69, -74.25))
                .radius(new BigDecimal("1500.500"))
                .count(50)
                .geoloc(GeoLoc.parse("40.690x-74.250"))
                .units(Units.IMPERIAL)
                .country("US")
                .searchLang("en")
                .uiLang("en-US")
                .safeSearch(SafeSearch.MODERATE)
                .spellcheck(true)
                .build();
    }

    private static String queryOf(PlaceSearchRequest request) {
        return assemble(request).uri().getRawQuery();
    }

    private static BraveApiRequest assemble(PlaceSearchRequest request) {
        return PlaceSearchEndpoint.assemble(request, PRODUCTION, token("t"));
    }

    private static Credential token(String text) {
        return Credential.of(text.getBytes(UTF_8));
    }
}

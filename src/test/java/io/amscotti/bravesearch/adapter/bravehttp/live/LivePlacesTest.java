package io.amscotti.bravesearch.adapter.bravehttp.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.bravehttp.endpoint.PlaceSearchEndpoint;
import io.amscotti.bravesearch.api.BraveSearchClient;
import io.amscotti.bravesearch.api.PlaceSearchResponse;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.BraveApiRequest;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.request.PlaceAnchor;
import io.amscotti.bravesearch.domain.request.PlaceSearchRequest;
import io.amscotti.bravesearch.domain.result.PlaceResults;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;

/**
 * One bounded live place search anchored on a fixed well-known landmark (Philadelphia City
 * Hall) holding only protocol invariants: the exact {@code /res/v1/local/place_search} path,
 * HTTP 200, a JSON-object body, and every present documented response bucket parsing as an
 * array.
 * Bucket entries are counted, never asserted against live content, and nothing from the body
 * is stored or printed. Invariants hold according to the plan entitlements the key carries:
 * the documented entitlement-absence shape — HTTP 400 with upstream code
 * {@code OPTION_NOT_IN_PLAN} — is a loud, recorded assumption skip; every other failure
 * shape still fails loudly.
 */
@Tag("live")
final class LivePlacesTest {

    private static final double PHILADELPHIA_CITY_HALL_LATITUDE = 39.9526;

    private static final double PHILADELPHIA_CITY_HALL_LONGITUDE = -75.1652;

    @Test
    @Timeout(value = 240, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void anchoredPlaceSearchHoldsTheProtocolInvariants(TestReporter reporter) throws Exception {
        LiveGuard.requireCredential();
        PlaceSearchRequest request = PlaceSearchRequest.builder()
                .anchor(new PlaceAnchor.Coordinates(PHILADELPHIA_CITY_HALL_LATITUDE, PHILADELPHIA_CITY_HALL_LONGITUDE))
                .count(1)
                .build();

        BraveApiRequest assembled =
                PlaceSearchEndpoint.assemble(request, BraveApiOrigin.production(), LiveGuard.credential());
        assertEquals(
                LiveGuard.productionPath(PlaceSearchEndpoint.ENDPOINT_PATH),
                assembled.uri().getPath(),
                "the live places exchange must target the exact /res/v1/local/place_search path");

        Outcome<PlaceSearchResponse> outcome = LiveGuard.pacedExchange(() -> {
            try (BraveSearchClient client = LiveGuard.client()) {
                return client.placeSearch(request);
            }
        });

        PlaceSearchResponse response;
        switch (outcome) {
            case Outcome.Success<PlaceSearchResponse> success -> response = success.value();
            case Outcome.Failure<PlaceSearchResponse> failure -> {
                if (LiveGuard.planOptionAbsent(failure)) {
                    reporter.publishEntry(
                            "live-places", LiveGuard.redacted("entitlement=place-search-option-absent-from-plan"));
                    Assumptions.abort(LiveGuard.PLACE_SEARCH_NOT_IN_PLAN_MESSAGE);
                }
                throw new AssertionError(
                        "the live places exchange failed: kind=" + failure.kind() + ", status=" + failure.httpStatus());
            }
        }
        assertEquals(
                200,
                response.httpStatus(),
                "the anchored live place search must answer 200; a parsed-mode 2xx with a nonempty body"
                        + " also proves the served content type was exactly application/json");
        JsonNode upstream = response.upstream();
        assertTrue(upstream.isObject(), "the live places body must parse as one JSON object");
        int entries = 0;
        for (String bucket : PlaceResults.BUCKETS) {
            JsonNode member = upstream.path(bucket);
            if (member.isMissingNode()) {
                continue;
            }
            assertTrue(
                    member.isArray(),
                    "every present documented bucket must parse as an array; observed bucket name: " + bucket);
            entries += member.size();
        }
        assertTrue(entries >= 0, "bucket totals are recorded, never asserted against live content");
        reporter.publishEntry(
                "live-places", LiveGuard.redacted("documented-buckets=" + PlaceResults.BUCKETS.size() + ",entries=" + entries));
    }
}

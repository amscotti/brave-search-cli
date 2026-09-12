package io.amscotti.bravesearch.adapter.bravehttp.endpoint;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.BraveApiRequest;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.request.PlaceEnrichmentRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Assembly of the place enrichment wire forms, pinned to the checked-in upstream
 * contract: the detail and description endpoints are GET-only, each id rides the wire
 * as its own repeated {@code ids} query parameter in chunk input order — duplicates
 * included, each value percent-encoded independently — and the endpoint path follows
 * the invocation's kind.
 */
final class PlaceEnrichmentEndpointTest {

    private static final BraveApiOrigin PRODUCTION = BraveApiOrigin.production();

    @Test
    void detailIdsRideTheWireAsRepeatedParametersInInputOrder() throws Exception {
        PlaceEnrichmentRequest request = new PlaceEnrichmentRequest(
                PlaceEnrichmentRequest.Kind.DETAILS, List.of("poi-a", "poi b", "poi-a", "poi+c"));

        BraveApiRequest assembled = PlaceEnrichmentEndpoint.assemble(request, PRODUCTION, token("opaque-test-token"));

        assertEquals(
                "https://api.search.brave.com/res/v1/local/pois"
                        + "?ids=poi-a&ids=poi%20b&ids=poi-a&ids=poi%2Bc",
                assembled.uri().toString(),
                "every id is one repeated ids parameter, encoded independently, order and duplicates preserved");
        assertEquals(BraveApiRequest.Method.GET, assembled.method());
        assertNull(assembled.bodyBytes());
        assertTrue(
                assembled.headers().stream()
                        .anyMatch(header ->
                                header.name().equals("X-Subscription-Token") && header.value().equals("opaque-test-token")),
                "the resolved credential rides the subscription header");
    }

    @Test
    void descriptionsTravelTheirOwnEndpointPath() throws Exception {
        PlaceEnrichmentRequest request =
                new PlaceEnrichmentRequest(PlaceEnrichmentRequest.Kind.DESCRIPTIONS, List.of("poi-a"));

        BraveApiRequest assembled = PlaceEnrichmentEndpoint.assemble(request, PRODUCTION, token("opaque-test-token"));

        assertEquals(
                "https://api.search.brave.com/res/v1/local/descriptions?ids=poi-a", assembled.uri().toString());
    }

    @Test
    void anOpaqueIdSpellingSurvivesTheWireEncoded() throws Exception {
        PlaceEnrichmentRequest request = new PlaceEnrichmentRequest(
                PlaceEnrichmentRequest.Kind.DETAILS, List.of("loc4FNMQJNOOCVHEB7UBOLN354ZYIDIYJ3RPRETERRY="));

        BraveApiRequest assembled = PlaceEnrichmentEndpoint.assemble(request, PRODUCTION, token("opaque-test-token"));

        assertTrue(
                assembled.uri().toString().endsWith("ids=loc4FNMQJNOOCVHEB7UBOLN354ZYIDIYJ3RPRETERRY%3D"),
                "the reserved padding byte encodes and nothing else changes: " + assembled.uri());
    }

    @Test
    void theVersionPinTravelsAsTheExactHeaderSpelling() throws Exception {
        PlaceEnrichmentRequest request =
                new PlaceEnrichmentRequest(PlaceEnrichmentRequest.Kind.DETAILS, List.of("poi-a"));

        BraveApiRequest assembled =
                PlaceEnrichmentEndpoint.assemble(request, PRODUCTION, token("opaque-test-token"), "2026-08-30");

        assertTrue(
                assembled.headers().stream()
                        .anyMatch(header -> header.name().equals("Api-Version") && header.value().equals("2026-08-30")),
                "the pin rides the wire exactly as spelled");
    }

    private static Credential token(String spelling) {
        return Credential.of(spelling.getBytes(UTF_8));
    }
}

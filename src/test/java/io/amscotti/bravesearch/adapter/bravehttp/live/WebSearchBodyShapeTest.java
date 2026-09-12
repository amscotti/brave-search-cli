package io.amscotti.bravesearch.adapter.bravehttp.live;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.bravehttp.json.UpstreamJsonCodec;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Hermetic pin of the structural reading of the live web-search body the smoke suite holds:
 * the result list rides inside the {@code web} object's {@code results} array — the array
 * may be empty — beside a required {@code query} object. The fixture mirrors the top-level
 * shape the production endpoint was observed answering on 2026-09-01 (one object with
 * {@code mixed}, {@code query}, {@code type}, {@code videos}, and {@code web}, where
 * {@code web} is an object carrying {@code type}, {@code family_friendly}, and
 * {@code results}), and the wrong readings — {@code web} as an array, a {@code web} object
 * without a {@code results} array, a body without a {@code query} object — are rejected.
 * No network exchange happens here.
 */
final class WebSearchBodyShapeTest {

    @Test
    void observedLiveShapeCarriesWebResultsInsideTheWebObject() {
        JsonNode upstream = json(
                """
                {"mixed":{"type":"mixed"},
                 "query":{"original":"brave search api","more_results_available":false},
                 "type":"web",
                 "videos":{"type":"videos","results":[]},
                 "web":{"type":"web","family_friendly":true,
                        "results":[{"type":"web_result","title":"t","url":"https://example.com"}]}}
                """);
        assertEquals(
                1,
                WebSearchBodyShape.webResultsCount(upstream),
                "the count is read from the results array inside the web object");
    }

    @Test
    void anEmptyResultsArrayStillHoldsTheShape() {
        JsonNode upstream = json(
                """
                {"query":{"original":"brave search api"},
                 "type":"web",
                 "web":{"type":"web","family_friendly":true,"results":[]}}
                """);
        assertEquals(
                0,
                WebSearchBodyShape.webResultsCount(upstream),
                "structural presence holds with an empty results array; live content is never graded");
    }

    @Test
    void webAsAnArrayIsTheRejectedReading() {
        AssertionError rejected = assertThrows(
                AssertionError.class,
                () -> WebSearchBodyShape.webResultsCount(json("{\"query\":{},\"web\":[]}")));
        assertTrue(
                rejected.getMessage().contains("web object"),
                "the rejection names the web object the body must carry");
    }

    @Test
    void aWebObjectWithoutAResultsArrayIsRejected() {
        AssertionError rejected = assertThrows(
                AssertionError.class,
                () -> WebSearchBodyShape.webResultsCount(json("{\"query\":{},\"web\":{\"type\":\"web\"}}")));
        assertTrue(
                rejected.getMessage().contains("results"),
                "the rejection names the results array the web object must carry");
    }

    @Test
    void aBodyWithoutAQueryObjectIsRejected() {
        assertThrows(
                AssertionError.class,
                () -> WebSearchBodyShape.webResultsCount(
                        json("{\"type\":\"web\",\"web\":{\"results\":[]}}")));
    }

    private static JsonNode json(String body) {
        return UpstreamJsonCodec.readTree(new UpstreamPayload(body.getBytes(UTF_8)));
    }
}

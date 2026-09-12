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
 * Hermetic pin of the structural reading of the live news-search body the smoke suite
 * holds: the result list rides either inside the {@code news} object's {@code results}
 * array or — the top-level shape the production endpoint was observed answering on
 * 2026-09-04 — in a shared top-level {@code results} array of tagged elements where only
 * {@code news_result} members count, beside a required {@code query} object. The fixture
 * mirrors the observed top-level shape with its profiler extras ({@code profile}, {@code
 * meta_url}, {@code thumbnail}) to pin tolerance of them, and the wrong readings — a
 * {@code news} member that is no object, neither shape present, a body without a {@code
 * query} object — are rejected. No network exchange happens here.
 */
final class NewsSearchBodyShapeTest {

    @Test
    void observedLiveShapeCarriesNewsResultsInTheTopLevelArray() {
        JsonNode upstream = json(
                """
                {"type":"news",
                 "query":{"original":"Muse Spark 1.3"},
                 "results":[
                   {"type":"news_result","title":"t","url":"https://example.com/first","age":"2 days ago",
                    "profile":{"name":"Example"},"meta_url":{"hostname":"example.com"},
                    "thumbnail":{"src":"https://example.com/thumb"}},
                   {"type":"news_result","title":"u","url":"https://example.com/second"},
                   {"type":"web_result","title":"w","url":"https://example.com/web"}]}
                """);
        assertEquals(
                2,
                NewsSearchBodyShape.newsResultsCount(upstream),
                "only the tagged news elements of the top-level array count");
    }

    @Test
    void documentedBucketShapeStillCounts() {
        JsonNode upstream = json(
                """
                {"query":{"original":"Muse Spark 1.3"},
                 "news":{"results":[{"title":"t","url":"https://example.com/first"}]}}
                """);
        assertEquals(
                1,
                NewsSearchBodyShape.newsResultsCount(upstream),
                "the documented bucket keeps its structural reading");
    }

    @Test
    void anEmptyTopLevelArrayStillHoldsTheShape() {
        JsonNode upstream = json("{\"type\":\"news\",\"query\":{\"original\":\"Muse Spark 1.3\"},\"results\":[]}");
        assertEquals(
                0,
                NewsSearchBodyShape.newsResultsCount(upstream),
                "structural presence holds with an empty results array; live content is never graded");
    }

    @Test
    void aBodyWithNeitherShapeIsTheRejectedReading() {
        AssertionError rejected = assertThrows(
                AssertionError.class,
                () -> NewsSearchBodyShape.newsResultsCount(json("{\"type\":\"news\",\"query\":{}}")));
        assertTrue(
                rejected.getMessage().contains("top-level results array"),
                "the rejection names the array the body must carry");
    }

    @Test
    void aBodyWithoutAQueryObjectIsRejected() {
        assertThrows(
                AssertionError.class,
                () -> NewsSearchBodyShape.newsResultsCount(json("{\"type\":\"news\",\"results\":[]}")));
    }

    private static JsonNode json(String body) {
        return UpstreamJsonCodec.readTree(new UpstreamPayload(body.getBytes(UTF_8)));
    }
}

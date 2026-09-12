package io.amscotti.bravesearch.adapter.bravehttp.live;

import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;

/**
 * The structural reading of the live news-search body the smoke suite holds: either the
 * documented {@code news} object carrying the result list in its {@code results} array, or
 * — the shape the production endpoint was observed answering on 2026-09-04 — a shared
 * top-level {@code results} array of tagged elements beside a {@code query} object, where
 * only {@code news_result} elements count. Only structural presence is asserted; live
 * result content is never inspected, stored, or printed.
 */
final class NewsSearchBodyShape {

    /** The element tag of a news result inside a shared top-level results array. */
    private static final String NEWS_RESULT_TYPE = "news_result";

    private NewsSearchBodyShape() {}

    /**
     * The live news result count of the given upstream body, returned only after one of
     * the two structural shapes holds; the count is recorded by callers, never asserted
     * against live content.
     */
    static int newsResultsCount(JsonNode upstream) {
        assertTrue(upstream.path("query").isObject(), "the live news body carries a query object");
        JsonNode bucket = upstream.path("news").path("results");
        if (bucket.isArray()) {
            return countNews(bucket);
        }
        JsonNode topLevel = upstream.path("results");
        assertTrue(
                topLevel.isArray(),
                "the live news body carries its results in the news bucket or the top-level results array");
        return countNews(topLevel);
    }

    private static int countNews(JsonNode results) {
        int count = 0;
        for (JsonNode element : results) {
            if (element.isObject() && isNews(element)) {
                count++;
            }
        }
        return count;
    }

    private static boolean isNews(JsonNode element) {
        JsonNode type = element.path("type");
        return !type.isString() || NEWS_RESULT_TYPE.equals(type.stringValue());
    }
}

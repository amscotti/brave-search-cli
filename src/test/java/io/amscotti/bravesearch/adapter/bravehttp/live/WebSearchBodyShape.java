package io.amscotti.bravesearch.adapter.bravehttp.live;

import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;

/**
 * The structural reading of the live web-search body the smoke suite holds: one JSON body
 * whose {@code web} member is an object carrying the result list in its {@code results}
 * array — the array may be empty — beside a {@code query} object. Only structural presence
 * is asserted; live result content is never inspected, stored, or printed.
 */
final class WebSearchBodyShape {

    private WebSearchBodyShape() {}

    /**
     * The live web result count of the given upstream body, returned only after the
     * structural shape holds; the count is recorded by callers, never asserted against
     * live content.
     */
    static int webResultsCount(JsonNode upstream) {
        JsonNode web = upstream.path("web");
        assertTrue(web.isObject(), "the live web body carries a web object; only structural presence is asserted");
        JsonNode results = web.path("results");
        assertTrue(
                results.isArray(),
                "the web object carries its results in the results array; results may be empty");
        assertTrue(upstream.path("query").isObject(), "the live web body carries a query object");
        return results.size();
    }
}

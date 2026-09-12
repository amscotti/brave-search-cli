package io.amscotti.bravesearch.adapter.bravehttp.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.bravehttp.endpoint.WebSearchEndpoint;
import io.amscotti.bravesearch.api.BraveSearchClient;
import io.amscotti.bravesearch.api.WebSearchResponse;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.BraveApiRequest;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.request.WebSearchRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;

/**
 * One bounded live web search (count pinned to one) holding only protocol invariants: the
 * exact {@code /res/v1/web/search} path on the production origin, HTTP 200, a JSON-object
 * body whose {@code web} member is an object carrying the result list in its {@code
 * results} array (live-verified 2026-09-01; the array may be empty) beside a {@code query}
 * object, and the live {@code X-RateLimit-*} headers parsing into at least one window
 * through the transport's {@code RateLimitHeaderParser}. No live result content is ever
 * asserted, stored, or printed — the only emitted line is a bounded, redacted structural
 * count.
 */
@Tag("live")
final class LiveWebSearchTest {

    @Test
    @Timeout(value = 240, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void boundedWebSearchHoldsTheProtocolInvariants(TestReporter reporter) throws Exception {
        LiveGuard.requireCredential();
        WebSearchRequest request = WebSearchRequest.builder("brave search api").count(1).build();

        BraveApiRequest assembled =
                WebSearchEndpoint.assemble(request, BraveApiOrigin.production(), LiveGuard.credential());
        assertEquals(
                LiveGuard.productionPath(WebSearchEndpoint.ENDPOINT_PATH),
                assembled.uri().getPath(),
                "the live web exchange must target the exact /res/v1/web/search path");
        assertEquals(
                "api.search.brave.com",
                assembled.uri().getHost(),
                "the live web exchange must target the production origin host");

        Outcome<WebSearchResponse> outcome = LiveGuard.pacedExchange(() -> {
            try (BraveSearchClient client = LiveGuard.client()) {
                return client.webSearch(request);
            }
        });

        WebSearchResponse response = switch (outcome) {
            case Outcome.Success<WebSearchResponse> success -> success.value();
            case Outcome.Failure<WebSearchResponse> failure ->
                throw new AssertionError(
                        "the live web exchange failed: kind=" + failure.kind() + ", status=" + failure.httpStatus());
        };
        assertEquals(
                200,
                response.httpStatus(),
                "the bounded live web search must answer 200; a parsed-mode 2xx with a nonempty body"
                        + " also proves the served content type was exactly application/json");
        assertFalse(
                response.rateLimits().windows().isEmpty(),
                "the live X-RateLimit headers must parse into at least one window via RateLimitHeaderParser");
        JsonNode upstream = response.upstream();
        assertTrue(upstream.isObject(), "the live web body must parse as one JSON object");
        int results = WebSearchBodyShape.webResultsCount(upstream);
        assertTrue(results >= 0, "the result count is recorded, never asserted against live content");
        reporter.publishEntry(
                "live-web",
                LiveGuard.redacted(
                        "rate-limit-windows=" + response.rateLimits().windows().size() + ",web-results=" + results));
    }
}

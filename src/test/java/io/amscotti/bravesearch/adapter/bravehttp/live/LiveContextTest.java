package io.amscotti.bravesearch.adapter.bravehttp.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.bravehttp.endpoint.ContextEndpoint;
import io.amscotti.bravesearch.api.BraveSearchClient;
import io.amscotti.bravesearch.api.ContextResponse;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.BraveApiRequest;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.request.ContextRequest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;

/**
 * One bounded live LLM-context retrieval (count pinned to one) holding only protocol
 * invariants: the exact {@code /res/v1/llm/context} path, HTTP 200, a JSON-object body, and —
 * because the request carries no location — not a single {@code X-Loc} header on the wire
 * form. Invariants hold according to the plan entitlements the key carries: the documented
 * entitlement-absence shape — HTTP 400 with upstream code {@code OPTION_NOT_IN_PLAN}
 * (live-observed 2026-09-01 on the development key) — is a loud, recorded assumption skip;
 * every other failure shape still fails loudly. The same content and redaction discipline
 * as the web probe: no live result content is asserted, stored, or printed.
 */
@Tag("live")
final class LiveContextTest {

    @Test
    @Timeout(value = 240, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void boundedLlmContextHoldsTheProtocolInvariants(TestReporter reporter) throws Exception {
        LiveGuard.requireCredential();
        ContextRequest request = ContextRequest.builder("brave search api").count(1).build();

        BraveApiRequest assembled =
                ContextEndpoint.assemble(request, BraveApiOrigin.production(), LiveGuard.credential());
        assertEquals(
                LiveGuard.productionPath(ContextEndpoint.ENDPOINT_PATH),
                assembled.uri().getPath(),
                "the live context exchange must target the exact /res/v1/llm/context path");
        assertTrue(
                assembled.headers().stream().noneMatch(header -> header.name().startsWith("X-Loc")),
                "the context request carries no location, so no X-Loc header may ride its wire form");

        Outcome<ContextResponse> outcome = LiveGuard.pacedExchange(() -> {
            try (BraveSearchClient client = LiveGuard.client()) {
                return client.llmContext(request);
            }
        });

        ContextResponse response;
        switch (outcome) {
            case Outcome.Success<ContextResponse> success -> response = success.value();
            case Outcome.Failure<ContextResponse> failure -> {
                if (LiveGuard.planOptionAbsent(failure)) {
                    reporter.publishEntry(
                            "live-context", LiveGuard.redacted("entitlement=llm-context-option-absent-from-plan"));
                    Assumptions.abort(LiveGuard.LLM_CONTEXT_NOT_IN_PLAN_MESSAGE);
                }
                throw new AssertionError(
                        "the live context exchange failed: kind=" + failure.kind() + ", status=" + failure.httpStatus());
            }
        }
        assertEquals(
                200,
                response.httpStatus(),
                "the bounded live context retrieval must answer 200; a parsed-mode 2xx with a nonempty"
                        + " body also proves the served content type was exactly application/json");
        JsonNode upstream = response.upstream();
        assertTrue(upstream.isObject(), "the live context body must parse as one JSON object");
        reporter.publishEntry(
                "live-context",
                LiveGuard.redacted("rate-limit-windows=" + response.rateLimits().windows().size()));
    }
}

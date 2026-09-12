package io.amscotti.bravesearch.adapter.bravehttp.live;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.bravehttp.BraveHttpTransport;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.AnswersEndpoint;
import io.amscotti.bravesearch.adapter.bravehttp.json.UpstreamJsonCodec;
import io.amscotti.bravesearch.api.AnswersResponse;
import io.amscotti.bravesearch.api.BraveSearchClient;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.BraveApiRequest;
import io.amscotti.bravesearch.application.exchange.BraveHttpResponse;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.error.UpstreamError;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.request.AnswersRequest;
import io.amscotti.bravesearch.domain.request.SafeSearch;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;

/**
 * The live wire-form probe of the Answers chat-completions body, settling the documented
 * nested-versus-flat discrepancy of the search-control fields: the implemented nested
 * {@code web_search_options} form is sent exactly as shipped and must be accepted (a
 * rejection fails loudly), while one flat-variant probe — the same cheap blocking question
 * with a search control flat at the top level — is sent purely to record the observed
 * behavior; its acceptance is never asserted either way. The wire-form question cannot
 * settle on an unentitled key (live-observed 2026-09-01 on the development key): when
 * either probe answers the documented entitlement-absence shape — HTTP 400 with upstream
 * code {@code OPTION_NOT_IN_PLAN} — both probes skip loudly with the recorded fixed
 * message, and the nested form stays the committed shape. Both requests are one cheap
 * blocking question with a tiny completion budget, and no result content is ever stored,
 * printed, or asserted: the flat probe's upstream text is only reduced to a fixed
 * classification vocabulary.
 */
@Tag("live")
final class LiveAnswersTest {

    private static final String PROBE_QUESTION = "Reply with exactly: pong";

    private static final int PROBE_TOKEN_CAP = 32;

    private static final Duration PROBE_BUDGET = Duration.ofSeconds(120);

    @Test
    @Timeout(value = 240, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void blockingAnswerWithNestedSearchControlsIsAccepted(TestReporter reporter) throws Exception {
        LiveGuard.requireCredential();
        AnswersRequest request = AnswersRequest.builder(PROBE_QUESTION)
                .maxCompletionTokens(PROBE_TOKEN_CAP)
                .country("US")
                .safeSearch(SafeSearch.MODERATE)
                .build();

        BraveApiRequest assembled =
                AnswersEndpoint.assemble(request, BraveApiOrigin.production(), LiveGuard.credential());
        assertEquals(
                LiveGuard.productionPath(AnswersEndpoint.ENDPOINT_PATH),
                assembled.uri().getPath(),
                "the live answers exchange must target the exact /res/v1/chat/completions path");
        JsonNode body = UpstreamJsonCodec.readTree(new UpstreamPayload(assembled.bodyBytes()));
        assertTrue(
                body.path("web_search_options").isObject(),
                "the implemented body must nest the search controls inside web_search_options");
        assertTrue(
                body.path("web_search_options").has("safesearch"),
                "the supplied search control must ride the nested container");
        assertFalse(body.has("safesearch"), "no search control may ride the top level of the body");

        Outcome<AnswersResponse> outcome = LiveGuard.pacedExchange(() -> {
            try (BraveSearchClient client = LiveGuard.client()) {
                return client.answers(request);
            }
        });

        AnswersResponse response = switch (outcome) {
            case Outcome.Success<AnswersResponse> success -> success.value();
            case Outcome.Failure<AnswersResponse> failure -> {
                if (LiveGuard.planOptionAbsent(failure)) {
                    reporter.publishEntry(
                            "live-answers-nested",
                            LiveGuard.redacted("entitlement=answers-option-absent-from-plan"));
                    Assumptions.abort(LiveGuard.ANSWERS_NOT_IN_PLAN_MESSAGE);
                }
                throw new AssertionError(
                        "the nested web_search_options form was rejected by the live endpoint: kind="
                                + failure.kind() + ", status=" + failure.httpStatus());
            }
        };
        assertEquals(200, response.httpStatus(), "the blocking live answer with nested search controls must be accepted");
        JsonNode upstream = response.upstream();
        assertTrue(upstream.isObject(), "the live blocking answer body must parse as one JSON object");
        assertTrue(
                upstream.path("choices").isArray() && !upstream.path("choices").isEmpty(),
                "a blocking answer carries a nonempty choices array");
        JsonNode error = upstream.path("error");
        if (!error.isMissingNode()) {
            String observed = error.toString().toLowerCase(Locale.ROOT);
            assertFalse(
                    observed.contains("unknown") || observed.contains("invalid"),
                    "a 200 answer body must not complain about unknown or invalid parameters;"
                            + " the observed text stays out of this message by discipline");
        }
        String usageObserved = response.usage() == null ? "none" : "answers-usage-response-headers";
        reporter.publishEntry(
                "live-answers-nested",
                LiveGuard.redacted("nested-web-search-options=accepted,usage-observed=" + usageObserved));
    }

    @Test
    @Timeout(value = 240, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void flatSearchControlProbeRecordsTheUpstreamBehavior(TestReporter reporter) throws Exception {
        LiveGuard.requireCredential();
        URI target = URI.create(BraveApiOrigin.PRODUCTION_BASE_URL + "/" + AnswersEndpoint.ENDPOINT_PATH);
        BraveApiRequest probe =
                BraveApiRequest.post(target, flatProbeBody()).token(LiveGuard.credential()).build();

        Outcome<BraveHttpResponse> outcome = LiveGuard.pacedExchange(() -> {
            try (BraveHttpTransport transport = LiveGuard.transport()) {
                return transport.send(probe, PROBE_BUDGET);
            }
        });

        if (outcome instanceof Outcome.Failure<BraveHttpResponse> failure && LiveGuard.planOptionAbsent(failure)) {
            reporter.publishEntry(
                    "live-answers-flat-probe", LiveGuard.redacted("entitlement=answers-option-absent-from-plan"));
            Assumptions.abort(LiveGuard.ANSWERS_NOT_IN_PLAN_MESSAGE);
        }
        String observation = "flat-search-controls=" + classify(outcome) + ",status=" + observedStatus(outcome);
        reporter.publishEntry("live-answers-flat-probe", LiveGuard.redacted(observation));
    }

    /**
     * The fixed probe body of the flat variant: the same one cheap blocking question and
     * token cap, with a search control flat at the top level where the nested form nests it.
     */
    private static byte[] flatProbeBody() {
        return ("{\"country\":\"US\",\"max_completion_tokens\":32,"
                        + "\"messages\":[{\"content\":\"Reply with exactly: pong\",\"role\":\"user\"}],"
                        + "\"safesearch\":\"moderate\",\"stream\":false}")
                .getBytes(UTF_8);
    }

    /** Reduces the observed outcome to the fixed record vocabulary; never renders upstream text. */
    static String classify(Outcome<BraveHttpResponse> outcome) {
        return switch (outcome) {
            case Outcome.Success<BraveHttpResponse> ignored -> "accepted";
            case Outcome.Failure<BraveHttpResponse> failure -> {
                if (failure.httpStatus() == 0) {
                    throw new AssertionError(
                            "the flat probe exchange did not complete observably: kind=" + failure.kind());
                }
                yield mentionsUnknownOrInvalidParameter(failure.upstream())
                        ? "rejected-unknown-or-invalid-parameter"
                        : "rejected-other";
            }
        };
    }

    static int observedStatus(Outcome<BraveHttpResponse> outcome) {
        return switch (outcome) {
            case Outcome.Success<BraveHttpResponse> success -> success.value().statusCode();
            case Outcome.Failure<BraveHttpResponse> failure -> failure.httpStatus();
        };
    }

    /**
     * Whether the bounded upstream error evidence mentions an unknown or invalid parameter;
     * the evidence text is only ever reduced to this boolean, never surfaced.
     */
    static boolean mentionsUnknownOrInvalidParameter(UpstreamError error) {
        if (error == null) {
            return false;
        }
        StringBuilder evidence = new StringBuilder(error.code() == null ? "" : error.code());
        evidence.append(' ');
        evidence.append(new String(error.body().toByteArray(), UTF_8));
        String lowercased = evidence.toString().toLowerCase(Locale.ROOT);
        return lowercased.contains("unknown")
                || (lowercased.contains("invalid") && lowercased.contains("parameter"));
    }
}

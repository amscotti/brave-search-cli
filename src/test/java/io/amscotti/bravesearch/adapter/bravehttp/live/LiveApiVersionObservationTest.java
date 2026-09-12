package io.amscotti.bravesearch.adapter.bravehttp.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.bravehttp.BraveHttpTransport;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.WebSearchEndpoint;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.BraveApiRequest;
import io.amscotti.bravesearch.application.exchange.BraveHttpResponse;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.request.WebSearchRequest;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.Timeout;

/**
 * One bounded live observation of the {@code Api-Version} protocol: the web search travels
 * with the version pinned to the upstream-contract baseline date, and the exchange is
 * observed, never graded on serving. A 2xx answer records the pin as served (with any
 * offered {@code Api-Version} response header logged); the typed upstream rejection — a 4xx
 * answering upstream code {@code API_VERSION_NOT_FOUND} (live-observed 2026-09-01: the
 * baseline-dated pin {@code 2026-08-30} answered 404) — records that the pin demonstrably
 * reached the server and was evaluated, because the baseline date is a doc-retrieval date,
 * not a verified served version. Every other failure shape still fails loudly, and no
 * server default is ever hardcoded or asserted — the offered value is recorded exactly as
 * observed, or its absence is.
 */
@Tag("live")
final class LiveApiVersionObservationTest {

    /**
     * The upstream-contract baseline date this repository pins when a version is explicitly
     * requested; it is the documented retrieval baseline, not a verified served version.
     */
    private static final String DOC_BASELINE_VERSION = "2026-08-30";

    private static final Duration PROBE_BUDGET = Duration.ofSeconds(120);

    @Test
    @Timeout(value = 240, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void pinnedBaselineExchangeIsEvaluatedByTheUpstream(TestReporter reporter) throws Exception {
        LiveGuard.requireCredential();
        WebSearchRequest request = WebSearchRequest.builder("brave search api").count(1).build();

        BraveApiRequest assembled = WebSearchEndpoint.assemble(
                request, BraveApiOrigin.production(), LiveGuard.credential(), DOC_BASELINE_VERSION);
        assertEquals(
                LiveGuard.productionPath(WebSearchEndpoint.ENDPOINT_PATH),
                assembled.uri().getPath(),
                "the pinned-version probe must target the exact /res/v1/web/search path");
        assertTrue(
                assembled.headers().stream()
                        .anyMatch(
                                header ->
                                        BraveApiRequest.API_VERSION_HEADER.equals(header.name())
                                                && DOC_BASELINE_VERSION.equals(header.value())),
                "the baseline version pin must ride the exact assembled request");

        Outcome<BraveHttpResponse> outcome = LiveGuard.pacedExchange(() -> {
            try (BraveHttpTransport transport = LiveGuard.transport()) {
                return transport.send(assembled, PROBE_BUDGET);
            }
        });

        String observation = switch (outcome) {
            case Outcome.Success<BraveHttpResponse> success -> {
                int status = success.value().statusCode();
                assertTrue(
                        status >= 200 && status <= 299,
                        "a successfully completed pinned exchange is a served 2xx; observed status " + status);
                String offered = success.value().apiVersion();
                yield "pin=served,status=" + status + ",api-version-response-header="
                        + (offered == null ? "absent" : "observed:" + offered);
            }
            case Outcome.Failure<BraveHttpResponse> failure -> {
                if (!LiveGuard.apiVersionNotFound(failure)) {
                    throw new AssertionError(
                            "the pinned-baseline exchange failed in a shape that is not a version verdict:"
                                    + " kind=" + failure.kind() + ", status=" + failure.httpStatus());
                }
                yield "pin=typed-not-found,status=" + failure.httpStatus()
                        + ",upstream-code=" + LiveGuard.API_VERSION_NOT_FOUND_CODE;
            }
        };
        reporter.publishEntry("live-api-version", LiveGuard.redacted(observation));
    }
}

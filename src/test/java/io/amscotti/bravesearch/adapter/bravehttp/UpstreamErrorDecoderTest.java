package io.amscotti.bravesearch.adapter.bravehttp;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.application.exchange.BraveHttpResponse;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.error.UpstreamError;
import org.junit.jupiter.api.Test;

/**
 * Error decoding of a bounded non-2xx body: the status alone decides most classifications, a
 * structured auth or entitlement code upgrades only a 422, and whatever the body said rides
 * along losslessly as the bounded upstream payload while the human diagnostic stays redacted —
 * no body text, no upstream detail, ever. Tolerance rules: a missing or malformed error body
 * yields no structured code and a generic message.
 */
final class UpstreamErrorDecoderTest {

    @Test
    void statusesWithoutABodyClassifyByStatusAlone() {
        assertClassification(302, FailureKind.UPSTREAM);
        assertClassification(400, FailureKind.UPSTREAM);
        assertClassification(401, FailureKind.AUTHENTICATION);
        assertClassification(403, FailureKind.AUTHENTICATION);
        assertClassification(404, FailureKind.UPSTREAM);
        assertClassification(429, FailureKind.RATE_LIMITED);
        assertClassification(500, FailureKind.UPSTREAM);
        assertClassification(503, FailureKind.UPSTREAM);
    }

    @Test
    void theFailureCarriesItsHttpStatusForTheMachineSurfaces() {
        assertEquals(401, UpstreamErrorDecoder.decode(401, new byte[0]).httpStatus());
        assertEquals(429, UpstreamErrorDecoder.decode(429, structuredBody("TooManyRequests")).httpStatus());
        assertEquals(503, UpstreamErrorDecoder.decode(503, new byte[0]).httpStatus());
    }

    @Test
    void theDiagnosticNamesTheStatusAndNothingFromBodyOrUri() {
        Outcome<BraveHttpResponse> outcome =
                UpstreamErrorDecoder.decode(500, "{\"error\":{\"detail\":\"SECRET-DETAIL\"}}".getBytes(UTF_8));

        assertEquals(FailureKind.UPSTREAM, ((Outcome.Failure<BraveHttpResponse>) outcome).kind());
        String diagnostic = ((Outcome.Failure<BraveHttpResponse>) outcome).diagnostic();
        assertTrue(diagnostic.contains("500"), diagnostic);
        assertFalse(diagnostic.contains("SECRET-DETAIL"), "the human message carries no body text");
        assertFalse(diagnostic.contains("/"), "the human message carries no URI text");
    }

    @Test
    void theStructuredCodeTimestampAndMetaSurviveLosslessly() {
        byte[] body = ("{\"error\":{\"code\":\"TooManyRequests\",\"detail\":\"quota exhausted\","
                        + "\"timestamp\":\"2026-08-31T12:34:56Z\",\"meta\":{\"request_id\":\"abc-123\","
                        + "\"unknown_future_field\":{\"nested\":[1,2,3]},\"plan\":\"free\"}}}")
                .getBytes(UTF_8);

        Outcome<BraveHttpResponse> outcome = UpstreamErrorDecoder.decode(429, body);

        Outcome.Failure<BraveHttpResponse> failure = (Outcome.Failure<BraveHttpResponse>) outcome;
        assertEquals(FailureKind.RATE_LIMITED, failure.kind());
        UpstreamError upstream = failure.upstream();
        assertEquals("TooManyRequests", upstream.code());
        assertEquals("2026-08-31T12:34:56Z", upstream.timestamp());
        assertArrayEquals(body, upstream.body().toByteArray(), "the payload is the raw bounded body");
    }

    @Test
    void anAuthBearingCodeUpgradesOnlyStatusFourTwentyTwo() {
        for (String authCode : new String[] {
            "entitlement_required", "unauthorized", "InvalidAPIKey", "FORBIDDEN", "payment_required",
            "subscription_required", "authentication_failed"
        }) {
            assertEquals(
                    FailureKind.AUTHENTICATION,
                    classificationOf(422, structuredBody(authCode)),
                    "422 with auth-bearing code " + authCode + " is an authentication failure");
        }
        assertEquals(
                FailureKind.UPSTREAM,
                classificationOf(422, structuredBody("ValidationFailed")),
                "a non-auth 422 stays upstream");
        assertEquals(
                FailureKind.UPSTREAM,
                classificationOf(422, new byte[0]),
                "a bodyless 422 stays upstream");
    }

    @Test
    void aStructuredAuthCodeNeverOverridesAnyOtherStatus() {
        for (int status : new int[] {400, 404, 500, 503}) {
            assertEquals(
                    FailureKind.UPSTREAM,
                    classificationOf(status, structuredBody("unauthorized")),
                    "status " + status + " is the observed fact and stays upstream");
        }
        assertEquals(
                FailureKind.RATE_LIMITED,
                classificationOf(429, structuredBody("unauthorized")),
                "429 stays rate limited");
    }

    @Test
    void malformedErrorBodiesYieldNoStructuredCodeButKeepThePayload() {
        byte[] notJson = "gateway exploded <html>".getBytes(UTF_8);

        Outcome<BraveHttpResponse> outcome = UpstreamErrorDecoder.decode(502, notJson);

        Outcome.Failure<BraveHttpResponse> failure = (Outcome.Failure<BraveHttpResponse>) outcome;
        assertEquals(FailureKind.UPSTREAM, failure.kind());
        assertNull(failure.upstream().code(), "no structured code can be extracted");
        assertNull(failure.upstream().timestamp());
        assertArrayEquals(notJson, failure.upstream().body().toByteArray());
    }

    @Test
    void jsonWithoutAnErrorObjectYieldsNoStructuredCode() {
        byte[] body = "{\"message\":\"plain failure wrapper\"}".getBytes(UTF_8);

        UpstreamError upstream = upstreamOf(500, body);

        assertNull(upstream.code());
        assertArrayEquals(body, upstream.body().toByteArray());
    }

    @Test
    void aMissingBodyCarriesNoUpstreamPayload() {
        Outcome<BraveHttpResponse> outcome = UpstreamErrorDecoder.decode(500, new byte[0]);

        Outcome.Failure<BraveHttpResponse> failure = (Outcome.Failure<BraveHttpResponse>) outcome;
        assertEquals(FailureKind.UPSTREAM, failure.kind());
        assertNull(failure.upstream(), "an empty error body retains nothing");
        assertTrue(failure.diagnostic().contains("500"));
    }

    @Test
    void nonTextualCodeAndTimestampNodesStillYieldTheirText() {
        byte[] body = "{\"error\":{\"code\":42,\"timestamp\":1756628160}}".getBytes(UTF_8);

        UpstreamError upstream = upstreamOf(500, body);

        assertEquals("42", upstream.code());
        assertEquals("1756628160", upstream.timestamp());
    }

    private static void assertClassification(int status, FailureKind expected) {
        Outcome<BraveHttpResponse> outcome = UpstreamErrorDecoder.decode(status, new byte[0]);
        assertEquals(expected, ((Outcome.Failure<BraveHttpResponse>) outcome).kind(), "status " + status);
        assertNull(((Outcome.Failure<BraveHttpResponse>) outcome).upstream());
    }

    private static FailureKind classificationOf(int status, byte[] body) {
        return UpstreamErrorDecoder.decode(status, body).kind();
    }

    private static UpstreamError upstreamOf(int status, byte[] body) {
        return UpstreamErrorDecoder.decode(status, body).upstream();
    }

    private static byte[] structuredBody(String code) {
        return ("{\"error\":{\"code\":\"" + code + "\"}}").getBytes(UTF_8);
    }
}

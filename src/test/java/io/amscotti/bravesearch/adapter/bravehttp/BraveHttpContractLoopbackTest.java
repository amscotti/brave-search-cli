package io.amscotti.bravesearch.adapter.bravehttp;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.application.exchange.BraveApiRequest;
import io.amscotti.bravesearch.application.exchange.BraveHttpResponse;
import io.amscotti.bravesearch.application.exchange.ResponseLimits;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.error.UpstreamError;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.testsupport.ScriptedSseServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Loopback contract of the response side of one Brave exchange, proven against a scripted
 * server on an ephemeral port. Every bound is enforced at kibibyte scale through injected
 * limits — the production defaults are 16 MiB compressed, 16 MiB decoded, 1 MiB structured
 * error — and every failing exchange authenticates with its own single-use sentinel token
 * whose text, together with its query value, is asserted absent from every diagnostic,
 * preview, and structured field the exchange can surface.
 */
final class BraveHttpContractLoopbackTest {

    private static final Duration GENEROUS_HEADERS_TIMEOUT = Duration.ofSeconds(10);
    private static final ResponseLimits SMALL_LIMITS = new ResponseLimits(64 * ResponseLimits.KIB, 8 * ResponseLimits.KIB, 2 * ResponseLimits.KIB);

    /** Fixes the metadata observation instant, so derived reset instants are assertable. */
    private static final Clock OBSERVED_AT_CLOCK = Clock.fixed(Instant.parse("2026-08-31T10:15:30Z"), ZoneOffset.UTC);

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void servesAJsonSuccessBodyByteExact() throws Exception {
        byte[] body = "{\"query\":\"café ☕\",\"web\":{\"results\":[{\"title\":\"a\"},{\"title\":\"b\"}]}}"
                .getBytes(UTF_8);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .writeBytes(body)
                .start()) {

            Outcome<BraveHttpResponse> outcome = send(SMALL_LIMITS, server, "sentinel-" + UUID.randomUUID(), false);

            BraveHttpResponse response = valueOf(outcome);
            assertEquals(200, response.statusCode());
            assertEquals(new UpstreamPayload(body), response.body(), "the payload is the decoded body, byte-exact");
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void succeedsOnNoContentWithAnEmptyBody() throws Exception {
        try (ScriptedSseServer server = ScriptedSseServer.builder().statusCode(204).start()) {

            Outcome<BraveHttpResponse> outcome = send(SMALL_LIMITS, server, "sentinel-" + UUID.randomUUID(), false);

            BraveHttpResponse response = valueOf(outcome);
            assertEquals(204, response.statusCode());
            assertEquals(0, response.body().length());
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void rejectsAWrongContentTypeWithABoundedRedactedPreview() throws Exception {
        String token = "sentinel-" + UUID.randomUUID();
        String marker = "marker-" + UUID.randomUUID();
        byte[] body = new byte[600];
        Arrays.fill(body, (byte) 'b');
        body[0] = 0x01;
        body[1] = '\n';
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "text/plain")
                .writeBytes(body)
                .start()) {

            Outcome<BraveHttpResponse> outcome = sendQuery(SMALL_LIMITS, server, token, marker, false);

            assertEquals(FailureKind.MALFORMED, kindOf(outcome));
            String diagnostic = diagnosticOf(outcome);
            assertTrue(diagnostic.contains("response content type is not application/json"), diagnostic);
            assertTrue(diagnostic.contains("\\u0001"), "control bytes arrive escaped: " + diagnostic);
            assertFalse(diagnostic.contains("b".repeat(300)), "the preview is bounded to 256 body bytes");
            assertNoSecrets(outcome, token, marker);
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void rawModePassesATruncatedJsonBodyThroughUnchanged() throws Exception {
        byte[] truncated = "{\"web\":{\"results\":[{\"title\":\"trunc".getBytes(UTF_8);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .writeBytes(truncated)
                .start()) {

            Outcome<BraveHttpResponse> outcome = send(SMALL_LIMITS, server, "sentinel-" + UUID.randomUUID(), true);

            BraveHttpResponse response = valueOf(outcome);
            assertEquals(200, response.statusCode());
            assertArrayEquals(truncated, response.body().toByteArray(), "raw mode never repairs or parses the body");
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void cancelsAnOversizedIdentityBodyWithNoPartialOutput() throws Exception {
        String token = "sentinel-" + UUID.randomUUID();
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .writeBytesUntilClosed(new byte[1024])
                .start()) {

            Outcome<BraveHttpResponse> outcome = send(SMALL_LIMITS, server, token, false);

            assertEquals(FailureKind.MALFORMED, kindOf(outcome));
            assertEquals("response exceeds decoded limit", diagnosticOf(outcome));
            assertTrue(
                    server.awaitConnectionClosed(Duration.ofSeconds(5)),
                    "the cancelled subscription must sever the server's connection");
            assertNoSecrets(outcome, token);
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void decodesAGzipSuccessBodyByteExact() throws Exception {
        byte[] body = "{\"query\":\"compressed ☕\",\"web\":{\"results\":[{\"title\":\"x\"}]}}".getBytes(UTF_8);
        byte[] compressed = gzip(body);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .header("Content-Encoding", "gzip")
                .writeBytes(compressed)
                .start()) {

            Outcome<BraveHttpResponse> outcome = send(SMALL_LIMITS, server, "sentinel-" + UUID.randomUUID(), false);

            BraveHttpResponse response = valueOf(outcome);
            assertEquals(200, response.statusCode());
            assertArrayEquals(body, response.body().toByteArray(), "gzip decodes to the original bytes");
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void rejectsADecompressionBombAtTheDecodedLimit() throws Exception {
        String token = "sentinel-" + UUID.randomUUID();
        byte[] bomb = gzip(repeat((byte) 'x', 1024 * 1024));
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .header("Content-Encoding", "gzip")
                .writeBytes(bomb)
                .start()) {

            Outcome<BraveHttpResponse> outcome = send(SMALL_LIMITS, server, token, false);

            assertEquals(FailureKind.MALFORMED, kindOf(outcome));
            assertEquals("response exceeds decoded limit", diagnosticOf(outcome));
            assertNoSecrets(outcome, token);
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void cancelsAnOversizedCompressedTransferImmediately() throws Exception {
        String token = "sentinel-" + UUID.randomUUID();
        // an endless gzip member of incompressible noise: the server is provably still
        // writing when the wire ceiling cancels the transfer, so the severance witness
        // observes the cancel mid-member rather than after a finite body drained
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .header("Content-Encoding", "gzip")
                .writeGzippedUntilClosed()
                .start()) {

            Outcome<BraveHttpResponse> outcome = send(SMALL_LIMITS, server, token, false);

            assertEquals(FailureKind.MALFORMED, kindOf(outcome));
            assertEquals("response exceeds compressed limit", diagnosticOf(outcome));
            assertTrue(
                    server.awaitConnectionClosed(Duration.ofSeconds(5)),
                    "the cancelled stream must sever the server's connection before the member ends");
            assertNoSecrets(outcome, token);
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void rejectsATruncatedGzipBody() throws Exception {
        byte[] compressed = gzip("{\"web\":{\"results\":[]}}".getBytes(UTF_8));
        byte[] truncated = Arrays.copyOfRange(compressed, 0, compressed.length - 6);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .header("Content-Encoding", "gzip")
                .writeBytes(truncated)
                .start()) {

            Outcome<BraveHttpResponse> outcome = send(SMALL_LIMITS, server, "sentinel-" + UUID.randomUUID(), false);

            assertEquals(FailureKind.MALFORMED, kindOf(outcome));
            assertEquals("truncated gzip response body", diagnosticOf(outcome));
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void rejectsTrailingGarbageAfterTheGzipStream() throws Exception {
        byte[] trailed = concat(
                gzip("{\"web\":{\"results\":[]}}".getBytes(UTF_8)), "TAIL-GARBAGE".getBytes(UTF_8));
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .header("Content-Encoding", "gzip")
                .writeBytes(trailed)
                .start()) {

            Outcome<BraveHttpResponse> outcome = send(SMALL_LIMITS, server, "sentinel-" + UUID.randomUUID(), false);

            assertEquals(FailureKind.MALFORMED, kindOf(outcome));
            assertEquals("trailing bytes after gzip stream", diagnosticOf(outcome));
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void rejectsStackedAndUnknownContentEncodings() throws Exception {
        for (String encoding : new String[] {"gzip, gzip", "br"}) {
            try (ScriptedSseServer server = ScriptedSseServer.builder()
                    .statusCode(200)
                    .header("Content-Type", "application/json")
                    .header("Content-Encoding", encoding)
                    .writeBytes(gzip("{}".getBytes(UTF_8)))
                    .start()) {

                Outcome<BraveHttpResponse> outcome =
                        send(SMALL_LIMITS, server, "sentinel-" + UUID.randomUUID(), false);

                assertEquals(FailureKind.MALFORMED, kindOf(outcome), "encoding " + encoding);
                assertEquals("unsupported content encoding", diagnosticOf(outcome));
            }
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void repeatedContentEncodingFieldLinesAreRejectedAsUnsupported() throws Exception {
        byte[] compressed = gzip("{\"web\":{\"results\":[]}}".getBytes(UTF_8));
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .header("Content-Encoding", "gzip")
                .header("Content-Encoding", "gzip")
                .writeBytes(compressed)
                .start()) {

            Outcome<BraveHttpResponse> outcome = send(SMALL_LIMITS, server, "sentinel-" + UUID.randomUUID(), false);

            assertEquals(FailureKind.MALFORMED, kindOf(outcome), "two gzip field lines are a stacked coding, never one");
            assertEquals("unsupported content encoding", diagnosticOf(outcome));
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aNonTwoXxBodyWithBrokenGzipClassifiesMalformedOverItsStatus() throws Exception {
        byte[] broken = gzip("{\"error\":{\"code\":\"InternalError\"}}".getBytes(UTF_8));
        broken[broken.length - 5] ^= 0x55;
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(500)
                .header("Content-Type", "application/json")
                .header("Content-Encoding", "gzip")
                .writeBytes(broken)
                .start()) {

            Outcome<BraveHttpResponse> outcome = send(SMALL_LIMITS, server, "sentinel-" + UUID.randomUUID(), false);

            assertEquals(
                    FailureKind.MALFORMED,
                    kindOf(outcome),
                    "decode integrity outranks status classification when the body is unintelligible");
            assertEquals("malformed gzip response body", diagnosticOf(outcome));
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void redirectsRemainUpstreamFailuresAndAreNeverFollowed() throws Exception {
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(302)
                .header("Location", "/landing")
                .writeBytes(new byte[0])
                .start()) {

            Outcome<BraveHttpResponse> outcome = send(SMALL_LIMITS, server, "sentinel-" + UUID.randomUUID(), false);

            assertEquals(FailureKind.UPSTREAM, kindOf(outcome), "a 3xx is an upstream failure, not a hop");
            assertEquals(1, server.requests().size(), "the redirect target is never requested");
        }
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void classifiesUpstreamStatusesAndPreservesStructuredErrors() throws Exception {
        String token = "sentinel-" + UUID.randomUUID();
        record Expectation(int status, String code, FailureKind kind) {}
        for (Expectation expected :
                new Expectation[] {
                    new Expectation(400, "BadRequest", FailureKind.UPSTREAM),
                    new Expectation(401, "Unauthorized", FailureKind.AUTHENTICATION),
                    new Expectation(403, "Forbidden", FailureKind.AUTHENTICATION),
                    new Expectation(422, "entitlement_required", FailureKind.AUTHENTICATION),
                    new Expectation(422, "ValidationFailed", FailureKind.UPSTREAM),
                    new Expectation(429, "TooManyRequests", FailureKind.RATE_LIMITED),
                    new Expectation(500, "InternalError", FailureKind.UPSTREAM),
                    new Expectation(503, "Unavailable", FailureKind.UPSTREAM)
                }) {
            byte[] errorBody = ("{\"error\":{\"code\":\"" + expected.code()
                            + "\",\"detail\":\"server-side explanation\",\"timestamp\":\"2026-08-31T10:15:30Z\","
                            + "\"meta\":{\"request_id\":\"opaque\",\"future_field\":{\"deep\":[1,2]}}}}")
                    .getBytes(UTF_8);
            try (ScriptedSseServer server = ScriptedSseServer.builder()
                    .statusCode(expected.status())
                    .header("Content-Type", "application/json")
                    .writeBytes(errorBody)
                    .start()) {

                Outcome<BraveHttpResponse> outcome = send(SMALL_LIMITS, server, token, false);

                assertEquals(expected.kind(), kindOf(outcome), "status " + expected.status());
                assertTrue(diagnosticOf(outcome).contains(Integer.toString(expected.status())));
                UpstreamError upstream = failureOf(outcome).upstream();
                assertEquals(expected.code(), upstream.code(), "status " + expected.status());
                assertEquals("2026-08-31T10:15:30Z", upstream.timestamp());
                assertArrayEquals(
                        errorBody,
                        upstream.body().toByteArray(),
                        "the error payload is the raw body, meta lossless: status " + expected.status());
                assertFalse(
                        diagnosticOf(outcome).contains("server-side explanation"),
                        "the human diagnostic quotes no body text");
                assertNoSecrets(outcome, token);
            }
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void boundsErrorBodiesAtTheStructuredErrorLimit() throws Exception {
        String token = "sentinel-" + UUID.randomUUID();
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(500)
                .header("Content-Type", "text/plain")
                .writeBytesUntilClosed(new byte[512])
                .start()) {

            Outcome<BraveHttpResponse> outcome = send(SMALL_LIMITS, server, token, false);

            assertEquals(FailureKind.MALFORMED, kindOf(outcome));
            assertEquals("response exceeds structured error limit", diagnosticOf(outcome));
            assertNull(failureOf(outcome).upstream(), "an oversized error body retains nothing");
            assertTrue(
                    server.awaitConnectionClosed(Duration.ofSeconds(5)),
                    "the cancelled subscription must sever the server's connection");
            assertNoSecrets(outcome, token);
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void countsDeliveredBytesWhenFramingIsAbsentOrLying() throws Exception {
        String closeDelimitedHead = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                + "Connection: close\r\n\r\n";
        try (RawResponseServer server = new RawResponseServer(closeDelimitedHead, repeat((byte) 'd', 32 * 1024))) {
            Outcome<BraveHttpResponse> outcome = sendUri(SMALL_LIMITS, server.baseUri(), "sentinel-" + UUID.randomUUID());

            assertEquals(FailureKind.MALFORMED, kindOf(outcome), "unframed bytes count as delivered");
            assertEquals("response exceeds decoded limit", diagnosticOf(outcome));
        }

        String lyingHead = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                + "Content-Length: 4\r\nConnection: close\r\n\r\n";
        try (RawResponseServer server = new RawResponseServer(lyingHead, repeat((byte) 'e', 16 * 1024))) {
            Outcome<BraveHttpResponse> outcome = sendUri(SMALL_LIMITS, server.baseUri(), "sentinel-" + UUID.randomUUID());

            BraveHttpResponse response = valueOf(outcome);
            assertEquals(4, response.body().length(), "framing, not the peer's volume, bounds a fixed-length body");
        }
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void failureDiagnosticsNeverCarryTheSentinelOrItsQueryText() throws Exception {
        String token = "sentinel-" + UUID.randomUUID();
        String marker = "marker-" + UUID.randomUUID();
        byte[] wrongType = "not json at all, just plain text".getBytes(UTF_8);
        byte[] errorBody = "{\"error\":{\"code\":\"Unauthorized\",\"detail\":\"recovery hint\"}}".getBytes(UTF_8);
        try (ScriptedSseServer wrongTypeServer = ScriptedSseServer.builder()
                        .statusCode(200)
                        .header("Content-Type", "text/html")
                        .writeBytes(wrongType)
                        .start();
                ScriptedSseServer unauthorizedServer = ScriptedSseServer.builder()
                        .statusCode(401)
                        .header("Content-Type", "application/json")
                        .writeBytes(errorBody)
                        .start();
                ScriptedSseServer bombServer = ScriptedSseServer.builder()
                        .statusCode(200)
                        .header("Content-Type", "application/json")
                        .header("Content-Encoding", "gzip")
                        .writeBytes(gzip(repeat((byte) 'z', 1024 * 1024)))
                        .start()) {

            for (Outcome<BraveHttpResponse> outcome :
                    new Outcome[] {
                        sendQuery(SMALL_LIMITS, wrongTypeServer, token, marker, false),
                        sendQuery(SMALL_LIMITS, unauthorizedServer, token, marker, false),
                        sendQuery(SMALL_LIMITS, bombServer, token, marker, false)
                    }) {
                assertTrue(outcome instanceof Outcome.Failure<BraveHttpResponse>, "every scenario fails");
                assertNoSecrets(outcome, token, marker);
            }
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void alignedMultiWindowRateLimitHeadersAttachToTheResponseSnapshot() throws Exception {
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .header("X-RateLimit-Limit", "1,15,2000")
                .header("X-RateLimit-Policy", "request,minute,month")
                .header("X-RateLimit-Remaining", "0,14,1997")
                .header("X-RateLimit-Reset", "7,42,26000")
                .writeBytes("{\"web\":{\"results\":[]}}".getBytes(UTF_8))
                .start()) {

            Outcome<BraveHttpResponse> outcome = send(SMALL_LIMITS, server, "sentinel-" + UUID.randomUUID(), false);

            BraveHttpResponse response = valueOf(outcome);
            assertEquals(3, response.rateLimits().windows().size(), "every aligned window parses");
            assertEquals(OBSERVED_AT_CLOCK.instant(), response.rateLimits().observedAt());
            assertEquals(
                    OBSERVED_AT_CLOCK.instant().plus(java.time.Duration.ofSeconds(26000)),
                    response.rateLimits().resetAtOf(response.rateLimits().windows().getLast()),
                    "the wire reset is a duration from the observation, never an epoch");
            assertEquals("request", response.rateLimits().windows().getFirst().policy());
            assertNull(response.usage(), "no usage header travelled");
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void malformedRateLimitHeadersNeverDowngradeASuccess() throws Exception {
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .header("X-RateLimit-Limit", "1,fifteen")
                .header("X-RateLimit-Policy", "request,minute")
                .header("X-RateLimit-Remaining", "0,notacounter")
                .header("X-RateLimit-Reset", "7,42")
                .writeBytes("{\"web\":{\"results\":[]}}".getBytes(UTF_8))
                .start()) {

            Outcome<BraveHttpResponse> outcome = send(SMALL_LIMITS, server, "sentinel-" + UUID.randomUUID(), false);

            BraveHttpResponse response = valueOf(outcome);
            assertEquals(200, response.statusCode(), "metadata garbage never fails a 2xx exchange");
            assertEquals(1, response.rateLimits().windows().size(), "only the fully valid position survives");
            assertEquals("request", response.rateLimits().windows().getFirst().policy());
            assertFalse(response.rateLimits().notes().isEmpty(), "the malformed position is preserved as notes");
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void answersUsageHeadersPopulateTheResponseUsage() throws Exception {
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .header("X-Request-Requests", "2")
                .header("X-Request-Queries", "1")
                .header("X-Request-Tokens-In", "1200")
                .header("X-Request-Tokens-Out", "800")
                .header("X-Request-Total-Cost", "0.0042")
                .header("X-Request-Research-Queries", "4")
                .writeBytes("{\"choices\":[{\"message\":{\"content\":\"an answer\"}}]}".getBytes(UTF_8))
                .start()) {

            Outcome<BraveHttpResponse> outcome = send(SMALL_LIMITS, server, "sentinel-" + UUID.randomUUID(), false);

            BraveHttpResponse response = valueOf(outcome);
            assertEquals(2L, response.usage().requests());
            assertEquals(1200L, response.usage().tokensIn());
            assertEquals(new java.math.BigDecimal("0.0042"), response.usage().totalCost());
            assertEquals(
                    Map.of("x-request-research-queries", "4"),
                    response.usage().unknownFields(),
                    "the JDK client lowercases response header names, and preservation is verbatim from there");
            assertEquals(List.of(), response.rateLimits().windows());
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void responsesWithoutMetadataHeadersCarryAnEmptySnapshot() throws Exception {
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .writeBytes("{}".getBytes(UTF_8))
                .start()) {

            Outcome<BraveHttpResponse> outcome = send(SMALL_LIMITS, server, "sentinel-" + UUID.randomUUID(), false);

            BraveHttpResponse response = valueOf(outcome);
            assertNotNull(response.rateLimits(), "the snapshot is empty, never absent");
            assertEquals(List.of(), response.rateLimits().windows());
            assertEquals(List.of(), response.rateLimits().notes());
            assertNull(response.usage());
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aRateLimitedFailureCarriesTheWindowsItsHeadersExplainedItWith() throws Exception {
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(429)
                .header("Content-Type", "application/json")
                .header("X-RateLimit-Limit", "1,15")
                .header("X-RateLimit-Policy", "request,minute")
                .header("X-RateLimit-Remaining", "0,14")
                .header("X-RateLimit-Reset", "7,42")
                .writeBytes("{\"error\":{\"code\":\"TooManyRequests\"}}".getBytes(UTF_8))
                .start()) {

            Outcome<BraveHttpResponse> outcome = send(SMALL_LIMITS, server, "sentinel-" + UUID.randomUUID(), false);

            Outcome.Failure<BraveHttpResponse> failure = failureOf(outcome);
            assertEquals(FailureKind.RATE_LIMITED, failure.kind());
            assertEquals(
                    2,
                    failure.rateLimits().windows().size(),
                    "a 429 is exactly the failure whose windows must ride the carrier");
            assertEquals("request", failure.rateLimits().windows().getFirst().policy());
            assertEquals(
                    OBSERVED_AT_CLOCK.instant().plus(java.time.Duration.ofSeconds(42)),
                    failure.rateLimits().resetAtOf(failure.rateLimits().windows().getLast()));
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aRateLimitedFailureWithoutWindowHeadersCarriesAnEmptySnapshot() throws Exception {
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(429)
                .header("Content-Type", "application/json")
                .writeBytes("{\"error\":{\"code\":\"TooManyRequests\"}}".getBytes(UTF_8))
                .start()) {

            Outcome<BraveHttpResponse> outcome = send(SMALL_LIMITS, server, "sentinel-" + UUID.randomUUID(), false);

            Outcome.Failure<BraveHttpResponse> failure = failureOf(outcome);
            assertEquals(FailureKind.RATE_LIMITED, failure.kind());
            assertEquals(
                    List.of(),
                    failure.rateLimits().windows(),
                    "no rate-limit headers means an empty snapshot, never a null one from the transport");
        }
    }

    private static Outcome<BraveHttpResponse> send(
            ResponseLimits limits, ScriptedSseServer server, String token, boolean rawMode) throws Exception {
        return sendQuery(limits, server, token, "marker-" + UUID.randomUUID(), rawMode);
    }

    private static Outcome<BraveHttpResponse> sendQuery(
            ResponseLimits limits, ScriptedSseServer server, String token, String marker, boolean rawMode)
            throws Exception {
        return sendUri(
                limits,
                URI.create(server.baseUrl() + "web/search?q=" + marker),
                token,
                rawMode);
    }

    private static Outcome<BraveHttpResponse> sendUri(ResponseLimits limits, URI uri, String token) throws Exception {
        return sendUri(limits, uri, token, false);
    }

    private static Outcome<BraveHttpResponse> sendUri(
            ResponseLimits limits, URI uri, String token, boolean rawMode) throws Exception {
        BraveHttpTransport transport = new BraveHttpTransport(
                new BraveHttpClientFactory(Duration.ofSeconds(5)).newClient(),
                GENEROUS_HEADERS_TIMEOUT,
                limits,
                OBSERVED_AT_CLOCK);
        return transport.send(BraveApiRequest.get(uri)
                .token(Credential.of(token.getBytes(UTF_8)))
                .rawMode(rawMode)
                .build());
    }

    /** A one-shot raw socket server speaking hand-written HTTP, for framing the JDK server cannot produce. */
    private static final class RawResponseServer implements AutoCloseable {

        private final ServerSocket socket;

        RawResponseServer(String head, byte[] body) throws IOException {
            this.socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            Thread.ofVirtual()
                    .start(() -> {
                        try (Socket connection = socket.accept()) {
                            OutputStream out = connection.getOutputStream();
                            out.write(head.getBytes(UTF_8));
                            out.write(body);
                            out.flush();
                            connection.close();
                        } catch (IOException serving) {
                            // the client may have gone first; the scripted bytes were already sent
                        }
                    });
        }

        URI baseUri() {
            return URI.create(
                    "http://" + socket.getInetAddress().getHostAddress() + ":" + socket.getLocalPort() + "/web/search");
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException alreadyClosed) {
                // teardown only
            }
        }
    }

    private static BraveHttpResponse valueOf(Outcome<BraveHttpResponse> outcome) {
        return switch (outcome) {
            case Outcome.Success<BraveHttpResponse> success -> success.value();
            case Outcome.Failure<BraveHttpResponse> failure ->
                    throw new AssertionError("expected a success, saw " + failure.kind() + ": " + failure.diagnostic());
        };
    }

    private static Outcome.Failure<BraveHttpResponse> failureOf(Outcome<BraveHttpResponse> outcome) {
        return switch (outcome) {
            case Outcome.Success<BraveHttpResponse> ignored ->
                    throw new AssertionError("expected a failure, saw a success");
            case Outcome.Failure<BraveHttpResponse> failure -> failure;
        };
    }

    private static FailureKind kindOf(Outcome<BraveHttpResponse> outcome) {
        return failureOf(outcome).kind();
    }

    private static String diagnosticOf(Outcome<BraveHttpResponse> outcome) {
        return failureOf(outcome).diagnostic();
    }

    /** Recursively asserts the secrets appear in no string a failure can surface. */
    private static void assertNoSecrets(Outcome<BraveHttpResponse> outcome, String... secrets) {
        if (outcome instanceof Outcome.Success<BraveHttpResponse>) {
            return;
        }
        Outcome.Failure<BraveHttpResponse> failure = failureOf(outcome);
        for (String secret : secrets) {
            assertFalse(failure.diagnostic().contains(secret), "a diagnostic carries secret material");
            UpstreamError upstream = failure.upstream();
            if (upstream != null) {
                if (upstream.code() != null) {
                    assertFalse(upstream.code().contains(secret), "a structured code carries secret material");
                }
                if (upstream.timestamp() != null) {
                    assertFalse(upstream.timestamp().contains(secret), "a timestamp carries secret material");
                }
                assertFalse(
                        upstream.body().toString().contains(secret),
                        "a payload rendering carries secret material");
            }
        }
    }

    private static byte[] gzip(byte[] payload) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream compressed = new GZIPOutputStream(out)) {
            compressed.write(payload);
        }
        return out.toByteArray();
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = new byte[first.length + second.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    private static byte[] repeat(byte value, int count) {
        byte[] filled = new byte[count];
        Arrays.fill(filled, value);
        return filled;
    }
}

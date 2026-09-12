package io.amscotti.bravesearch.adapter.cli.presentation.json;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.bravehttp.metadata.RateLimitHeaderParser;
import io.amscotti.bravesearch.domain.error.UnreadableBodyException;
import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import io.amscotti.bravesearch.domain.metadata.RateLimitWindow;
import io.amscotti.bravesearch.domain.metadata.RequestMeta;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.metadata.Usage;
import io.amscotti.bravesearch.domain.result.Projection;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Byte-exact contract of the machine-readable success and failure envelopes. */
final class EnvelopeCodecGoldenTest {

    private static final String FULL_META_GOLDEN =
            """
            {"schema_version":"1","ok":true,"command":"web","data":{"projection":{"result_count":42,"top_title":"Cats & Dogs","safe":true,"related":["cats","dogs"],"page":{"index":2}},"upstream":{"query":{"original":"cats"},"web":{"results":[{"title":"Cats"}]},"unknown_future_field":{"nested":[1,2,3]}}},"meta":{"request_id":"req-7f3a2b","http_status":200,"api_version":"2024-08-01","rate_limits":[{"policy":"request","limit":1,"remaining":0,"reset_ms":1200},{"policy":"token","limit":0,"remaining":0,"reset_ms":0}],"usage":{"requests":2,"queries":1,"tokens_in":17,"tokens_out":240,"requests_cost":0.0001,"queries_cost":0.0031,"tokens_in_cost":0.00002,"tokens_out_cost":0.00098,"total_cost":0.0042,"unknown":{"X-Request-Research-Queries":"4"}},"warnings":["results truncated by upstream page limit"]}}
            """;

    private static final String ZERO_RESULT_GOLDEN =
            """
            {"schema_version":"1","ok":true,"command":"web","data":{"projection":{},"upstream":{}},"meta":{"request_id":null,"http_status":200,"api_version":null,"rate_limits":[],"usage":null,"warnings":[]}}
            """;

    private static final String AUTHENTICATION_FAILURE_GOLDEN =
            """
            {"schema_version":"1","ok":false,"command":"web","error":{"code":"AUTHENTICATION_FAILED","message":"Brave Search rejected the configured credential.","retryable":false,"upstream_code":null,"details":null},"meta":{"http_status":422,"rate_limits":[{"policy":"request","limit":1,"remaining":0,"reset_ms":1000}]}}
            """;

    private static final String RATE_LIMITED_FAILURE_GOLDEN =
            """
            {"schema_version":"1","ok":false,"command":"web","error":{"code":"RATE_LIMITED","message":"Upstream rate limit exceeded.","retryable":true,"upstream_code":"429","details":null},"meta":{"http_status":429,"rate_limits":[]}}
            """;

    private static final String RATE_LIMITED_FAILURE_WITH_WINDOWS_GOLDEN =
            """
            {"schema_version":"1","ok":false,"command":"web","error":{"code":"RATE_LIMITED","message":"Upstream rate limit exceeded.","retryable":true,"upstream_code":"429","details":null},"meta":{"http_status":429,"rate_limits":[{"policy":"request","limit":1,"remaining":0,"reset_ms":1000},{"policy":"minute","limit":15,"remaining":14,"reset_ms":42000}]}}
            """;

    private static final String DECIMAL_LOSSLESS_GOLDEN =
            """
            {"schema_version":"1","ok":true,"command":"web","data":{"projection":{},"upstream":{"trailing":1.10,"long_decimal":0.1000000000000000000001}},"meta":{"request_id":null,"http_status":200,"api_version":null,"rate_limits":[],"usage":null,"warnings":[]}}
            """;

    private static final String DECIMAL_NORMALIZATION_GOLDEN =
            """
            {"schema_version":"1","ok":true,"command":"web","data":{"projection":{},"upstream":{"exponent":1E+2,"negative_zero":0.0}},"meta":{"request_id":null,"http_status":200,"api_version":null,"rate_limits":[],"usage":null,"warnings":[]}}
            """;

    private static final String SUB_MILLISECOND_RESET_GOLDEN =
            """
            {"schema_version":"1","ok":true,"command":"web","data":{"projection":{},"upstream":{}},"meta":{"request_id":null,"http_status":200,"api_version":null,"rate_limits":[{"policy":"request","limit":1,"remaining":0,"reset_ms":0},{"policy":"token","limit":0,"remaining":0,"reset_ms":1}],"usage":null,"warnings":[]}}
            """;

    private static final String EXPONENT_COST_GOLDEN =
            """
            {"schema_version":"1","ok":true,"command":"web","data":{"projection":{},"upstream":{}},"meta":{"request_id":null,"http_status":200,"api_version":null,"rate_limits":[],"usage":{"requests":null,"queries":null,"tokens_in":null,"tokens_out":null,"requests_cost":null,"queries_cost":null,"tokens_in_cost":null,"tokens_out_cost":null,"total_cost":1E+3,"unknown":null},"warnings":[]}}
            """;

    @Test
    void successEnvelopeWithFullMetaMatchesGoldenBytes() {
        assertGolden(FULL_META_GOLDEN, CodecSamples.fullMetaSuccess());
    }

    @Test
    void prettySuccessEnvelopeIndentsTwoSpacesWithLfOnlyAndOneFinalLf() {
        byte[] encoded = CodecSamples
                .envelopeCodec()
                .encodeSuccess(
                        "web",
                        Projection.empty(),
                        new UpstreamPayload("{\"web\":{\"results\":[{\"title\":\"Only\"}]}}".getBytes(StandardCharsets.UTF_8)),
                        new RequestMeta(null, 200, null, List.of(), null),
                        List.of(),
                        true);
        assertGolden(PRETTY_SUCCESS_GOLDEN, encoded);
    }

    @Test
    void prettyFailureEnvelopeIndentsTheSameWay() {
        byte[] encoded = CodecSamples
                .envelopeCodec()
                .encodeFailure(
                        "web",
                        io.amscotti.bravesearch.domain.error.FailureKind.AUTHENTICATION,
                        "Brave Search rejected the configured credential.",
                        false,
                        null,
                        new RequestMeta(null, 422, null, List.of(), null),
                        true);
        assertGolden(PRETTY_FAILURE_GOLDEN, encoded);
    }

    private static final String PRETTY_SUCCESS_GOLDEN =
            """
            {
              "schema_version": "1",
              "ok": true,
              "command": "web",
              "data": {
                "projection": {},
                "upstream": {
                  "web": {
                    "results": [
                      {
                        "title": "Only"
                      }
                    ]
                  }
                }
              },
              "meta": {
                "request_id": null,
                "http_status": 200,
                "api_version": null,
                "rate_limits": [],
                "usage": null,
                "warnings": []
              }
            }
            """;

    private static final String PRETTY_FAILURE_GOLDEN =
            """
            {
              "schema_version": "1",
              "ok": false,
              "command": "web",
              "error": {
                "code": "AUTHENTICATION_FAILED",
                "message": "Brave Search rejected the configured credential.",
                "retryable": false,
                "upstream_code": null,
                "details": null
              },
              "meta": {
                "http_status": 422,
                "rate_limits": []
              }
            }
            """;

    @Test
    void zeroResultSuccessEnvelopeHasEmptyProjectionAndAbsentMetadata() {
        assertGolden(ZERO_RESULT_GOLDEN, CodecSamples.zeroResultSuccess());
    }

    @Test
    void failureEnvelopeWithRateLimitsMatchesGoldenBytes() {
        assertGolden(AUTHENTICATION_FAILURE_GOLDEN, CodecSamples.authenticationFailure());
    }

    @Test
    void failureEnvelopeCarriesUpstreamCodeAndRetryable() {
        assertGolden(RATE_LIMITED_FAILURE_GOLDEN, CodecSamples.rateLimitedFailure());
    }

    @Test
    void failureEnvelopeRendersTheWindowsARateLimitedExchangeObserved() {
        assertGolden(RATE_LIMITED_FAILURE_WITH_WINDOWS_GOLDEN, CodecSamples.rateLimitedFailureWithWindows());
    }

    @Test
    void upstreamDecimalsKeepTrailingZerosAndExactLongDigits() {
        assertGolden(
                DECIMAL_LOSSLESS_GOLDEN,
                encodeSuccessWithUpstream("{\"trailing\":1.10,\"long_decimal\":0.1000000000000000000001}"));
    }

    @Test
    void upstreamExponentsNormalizeAndNegativeZeroLosesItsSign() {
        assertGolden(DECIMAL_NORMALIZATION_GOLDEN, encodeSuccessWithUpstream("{\"exponent\":1e2,\"negative_zero\":-0.0}"));
    }

    @Test
    void subMillisecondResetDurationsTruncateTowardZeroOnTheWire() {
        byte[] encoded = CodecSamples
                .envelopeCodec()
                .encodeSuccess(
                        "web",
                        Projection.empty(),
                        new UpstreamPayload("{}".getBytes(StandardCharsets.UTF_8)),
                        new RequestMeta(
                                null,
                                200,
                                null,
                                List.of(
                                        new RateLimitWindow("request", 1, 0, Duration.ofNanos(999_999)),
                                        new RateLimitWindow("token", 0, 0, Duration.ofNanos(1_900_000))),
                                null),
                        List.of());
        assertGolden(SUB_MILLISECOND_RESET_GOLDEN, encoded);
    }

    @Test
    void hostileResetHeadersNeverCrashSuccessEncoding() {
        RateLimitSnapshot observed = RateLimitHeaderParser.parse(
                Map.of(
                        "X-RateLimit-Limit", List.of("15"),
                        "X-RateLimit-Policy", List.of("per-minute"),
                        "X-RateLimit-Remaining", List.of("14"),
                        "X-RateLimit-Reset", List.of("9999999999999999")),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        byte[] encoded = CodecSamples
                .envelopeCodec()
                .encodeSuccess(
                        "web",
                        Projection.empty(),
                        new UpstreamPayload("{}".getBytes(StandardCharsets.UTF_8)),
                        new RequestMeta(null, 200, null, observed.windows(), null),
                        List.of());
        assertGolden(
                ZERO_RESULT_GOLDEN,
                encoded,
                "a hostile reset degrades to a dropped window at parse, never a crash at encode");
    }

    @Test
    void exponentNotationCostsRenderValueExact() {
        byte[] encoded = CodecSamples
                .envelopeCodec()
                .encodeSuccess(
                        "web",
                        Projection.empty(),
                        new UpstreamPayload("{}".getBytes(StandardCharsets.UTF_8)),
                        new RequestMeta(
                                null,
                                200,
                                null,
                                List.of(),
                                new Usage(
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        new java.math.BigDecimal("1e3"),
                                        java.util.Map.of(),
                                        List.of())),
                        List.of());
        assertGolden(EXPONENT_COST_GOLDEN, encoded);
    }

    @Test
    void nonJsonUpstreamBodyIsRejectedWithAClearFailure() {
        UnreadableBodyException failure = assertThrows(UnreadableBodyException.class, () -> encodeSuccessWithUpstream("not json"));
        assertTrue(failure.getMessage().contains("not exactly one JSON document"), failure::getMessage);
    }

    @Test
    void upstreamBodyWithTrailingTokensIsRejected() {
        UnreadableBodyException failure = assertThrows(UnreadableBodyException.class, () -> encodeSuccessWithUpstream("{} {}"));
        assertTrue(failure.getMessage().contains("not exactly one JSON document"), failure::getMessage);
    }

    @Test
    void emptyUpstreamBodyIsRejected() {
        UnreadableBodyException failure = assertThrows(UnreadableBodyException.class, () -> encodeSuccessWithUpstream(""));
        assertTrue(failure.getMessage().contains("not a JSON document"), failure::getMessage);
    }

    private static byte[] encodeSuccessWithUpstream(String upstream) {
        return CodecSamples
                .envelopeCodec()
                .encodeSuccess(
                        "web",
                        Projection.empty(),
                        new UpstreamPayload(upstream.getBytes(StandardCharsets.UTF_8)),
                        new RequestMeta(null, 200, null, List.of(), null),
                        List.of());
    }

    private static void assertGolden(String golden, byte[] encoded) {
        assertGolden(golden, encoded, "");
    }

    private static void assertGolden(String golden, byte[] encoded, String message) {
        assertArrayEquals(golden.getBytes(StandardCharsets.UTF_8), encoded, message);
    }
}

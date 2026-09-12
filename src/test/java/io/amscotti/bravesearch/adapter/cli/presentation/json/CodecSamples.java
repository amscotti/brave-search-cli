package io.amscotti.bravesearch.adapter.cli.presentation.json;

import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.metadata.RateLimitWindow;
import io.amscotti.bravesearch.domain.metadata.RequestMeta;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.metadata.Usage;
import io.amscotti.bravesearch.domain.result.Projection;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Shared encoded documents and the inputs that produce them, pinned by the golden and schema tests. */
final class CodecSamples {

    static final String UPSTREAM_JSON =
            "{\"query\":{\"original\":\"cats\"},\"web\":{\"results\":[{\"title\":\"Cats\"}]},"
                    + "\"unknown_future_field\":{\"nested\":[1,2,3]}}";

    private static final JsonMappers MAPPERS = new JsonMappers();

    private CodecSamples() {}

    static EnvelopeCodec envelopeCodec() {
        return new EnvelopeCodec(MAPPERS);
    }

    static JsonlCodec jsonlCodec() {
        return new JsonlCodec(MAPPERS);
    }

    static Projection fullMetaProjection() {
        return new Projection(
                List.of(
                        new Projection.Field("result_count", new Projection.Decimal(BigDecimal.valueOf(42))),
                        new Projection.Field("top_title", new Projection.Text("Cats & Dogs")),
                        new Projection.Field("safe", new Projection.Flag(true)),
                        new Projection.Field(
                                "related",
                                new Projection.Sequence(List.of(new Projection.Text("cats"), new Projection.Text("dogs")))),
                        new Projection.Field(
                                "page", new Projection.Nested(new Projection(List.of(new Projection.Field("index", new Projection.Decimal(BigDecimal.valueOf(2)))))))));
    }

    static RequestMeta fullMeta() {
        return new RequestMeta(
                "req-7f3a2b",
                200,
                "2024-08-01",
                List.of(
                        new RateLimitWindow("request", 1, 0, Duration.ofMillis(1200)),
                        new RateLimitWindow("token", 0, 0, Duration.ZERO)),
                new Usage(
                        2L,
                        1L,
                        17L,
                        240L,
                        new BigDecimal("0.0001"),
                        new BigDecimal("0.0031"),
                        new BigDecimal("0.00002"),
                        new BigDecimal("0.00098"),
                        new BigDecimal("0.0042"),
                        Map.of("X-Request-Research-Queries", "4"),
                        List.of()));
    }

    static byte[] fullMetaSuccess() {
        return envelopeCodec()
                .encodeSuccess(
                        "web",
                        fullMetaProjection(),
                        new UpstreamPayload(UPSTREAM_JSON.getBytes(StandardCharsets.UTF_8)),
                        fullMeta(),
                        List.of("results truncated by upstream page limit"));
    }

    static byte[] zeroResultSuccess() {
        return envelopeCodec()
                .encodeSuccess(
                        "web",
                        Projection.empty(),
                        new UpstreamPayload("{}".getBytes(StandardCharsets.UTF_8)),
                        new RequestMeta(null, 200, null, List.of(), null),
                        List.of());
    }

    static byte[] authenticationFailure() {
        return envelopeCodec()
                .encodeFailure(
                        "web",
                        FailureKind.AUTHENTICATION,
                        "Brave Search rejected the configured credential.",
                        false,
                        null,
                        new RequestMeta(
                                null,
                                422,
                                null,
                                List.of(new RateLimitWindow("request", 1, 0, Duration.ofSeconds(1))),
                                null));
    }

    static byte[] rateLimitedFailure() {
        return envelopeCodec()
                .encodeFailure(
                        "web",
                        FailureKind.RATE_LIMITED,
                        "Upstream rate limit exceeded.",
                        true,
                        "429",
                        new RequestMeta(null, 429, null, List.of(), null));
    }

    static byte[] rateLimitedFailureWithWindows() {
        return envelopeCodec()
                .encodeFailure(
                        "web",
                        FailureKind.RATE_LIMITED,
                        "Upstream rate limit exceeded.",
                        true,
                        "429",
                        new RequestMeta(
                                null,
                                429,
                                null,
                                List.of(
                                        new RateLimitWindow("request", 1, 0, Duration.ofSeconds(1)),
                                        new RateLimitWindow("minute", 15, 14, Duration.ofSeconds(42))),
                                null));
    }

    static byte[] jsonlResultLine() {
        return jsonlCodec()
                .encodeResult(
                        "web",
                        List.of(
                                new Projection.Field("position", new Projection.Decimal(BigDecimal.ZERO)),
                                new Projection.Field("bucket", new Projection.Text("web")),
                                new Projection.Field("title", new Projection.Text("Cats")),
                                new Projection.Field("url", new Projection.Text("https://example.com/cats"))));
    }

    static byte[] jsonlSummaryLine() {
        return jsonlCodec()
                .encodeSummary(
                        "web",
                        List.of(
                                new Projection.Field("result_count", new Projection.Decimal(BigDecimal.ONE)),
                                new Projection.Field("page", new Projection.Decimal(BigDecimal.ONE)),
                                new Projection.Field("upstream_offset", new Projection.Decimal(BigDecimal.ZERO)),
                                new Projection.Field("http_status", new Projection.Decimal(BigDecimal.valueOf(200))),
                                new Projection.Field("request_id", new Projection.Text("req-7f3a2b"))));
    }

    static byte[] jsonlWarningLine() {
        return jsonlCodec().encodeWarning("web", "rate limit window reset delayed this request");
    }

    static byte[] jsonlErrorLine() {
        return jsonlCodec().encodeError("answers", FailureKind.RATE_LIMITED, "Upstream rate limit exceeded.", true);
    }

    /** The error record of an exchange that observed quota windows: they ride after the counts. */
    static byte[] jsonlErrorLineWithWindows() {
        return jsonlCodec()
                .encodeError(
                        "web",
                        FailureKind.RATE_LIMITED,
                        "Upstream rate limit exceeded.",
                        true,
                        null,
                        List.of(
                                new RateLimitWindow("request", 1, 0, Duration.ofSeconds(1)),
                                new RateLimitWindow("minute", 15, 14, Duration.ofSeconds(42))));
    }
}

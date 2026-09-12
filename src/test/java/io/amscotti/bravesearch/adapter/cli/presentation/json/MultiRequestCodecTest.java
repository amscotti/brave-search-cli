package io.amscotti.bravesearch.adapter.cli.presentation.json;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.UnreadableBodyException;
import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import io.amscotti.bravesearch.domain.metadata.RateLimitWindow;
import io.amscotti.bravesearch.domain.metadata.RequestMeta;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.result.PagedSearch;
import io.amscotti.bravesearch.domain.result.Projection;
import io.amscotti.bravesearch.domain.result.WebSearchResult;
import io.amscotti.bravesearch.testsupport.SchemaCatalog;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The multi-request machine framings: the success envelope whose {@code data.upstream} is
 * the ordered array of per-request entries — {@code request_index}, per-request {@code
 * meta}, lossless {@code body} — and the failure documents that carry the pagination counts
 * (the JSON envelope's {@code error.details} object and the JSONL error record's count
 * fields).
 */
final class MultiRequestCodecTest {

    private static final RateLimitWindow EXHAUSTED_REQUEST =
            new RateLimitWindow("request", 1, 0, Duration.ofMillis(1200));

    private static final PagedSearch.Page<WebSearchResult> FIRST_PAGE = new PagedSearch.Page<>(
            0,
            1,
            null,
            new WebSearchResult(
                    200,
                    new UpstreamPayload("{\"query\":{\"original\":\"cats\"}}".getBytes(UTF_8)),
                    RateLimitSnapshot.empty(),
                    null,
                    "req-1",
                    null));

    private static final PagedSearch.Page<WebSearchResult> SECOND_PAGE = new PagedSearch.Page<>(
            1,
            2,
            Duration.ofMillis(2000),
            new WebSearchResult(
                    200,
                    new UpstreamPayload("{\"query\":{\"original\":\"cats\"},\"web\":{\"results\":[]}}".getBytes(UTF_8)),
                    new RateLimitSnapshot(List.of(EXHAUSTED_REQUEST), List.of(), java.time.Instant.EPOCH),
                    null,
                    "req-2",
                    null));

    private static final String MULTI_SUCCESS_GOLDEN =
            """
            {"schema_version":"1","ok":true,"command":"web","data":{"projection":{"result_count":2,"requested_pages":3,"received_pages":2,"duplicates_removed":1},"upstream":[{"request_index":0,"meta":{"request_id":"req-1","http_status":200,"api_version":null,"rate_limits":[],"usage":null},"body":{"query":{"original":"cats"}}},{"request_index":1,"meta":{"request_id":"req-2","http_status":200,"api_version":null,"rate_limits":[{"policy":"request","limit":1,"remaining":0,"reset_ms":1200}],"usage":null,"waited_ms":2000},"body":{"query":{"original":"cats"},"web":{"results":[]}}}]},"meta":{"request_id":"req-2","http_status":200,"api_version":null,"rate_limits":[{"policy":"request","limit":1,"remaining":0,"reset_ms":1200}],"usage":null,"warnings":[]}}
            """;

    private static final String COUNTED_FAILURE_GOLDEN =
            """
            {"schema_version":"1","ok":false,"command":"web","error":{"code":"RATE_LIMITED","message":"Upstream rate limit exceeded.","retryable":true,"upstream_code":null,"details":{"requested_pages":3,"received_pages":1}},"meta":{"http_status":429,"rate_limits":[]}}
            """;

    private static final String COUNTED_JSONL_ERROR_GOLDEN =
            """
            {"schema_version":"1","type":"error","command":"web","code":"RATE_LIMITED","message":"Upstream rate limit exceeded.","retryable":true,"requested_pages":3,"received_pages":1}
            """;

    private static final String COUNTED_JSONL_ERROR_WITH_WINDOWS_GOLDEN =
            """
            {"schema_version":"1","type":"error","command":"web","code":"RATE_LIMITED","message":"Upstream rate limit exceeded.","retryable":true,"requested_pages":3,"received_pages":1,"rate_limits":[{"policy":"request","limit":1,"remaining":0,"reset_ms":1200}]}
            """;

    private final SchemaCatalog schemas = new SchemaCatalog();

    @Test
    void multiRequestSuccessEncodesTheOrderedUpstreamEntryArray() {
        byte[] encoded = CodecSamples
                .envelopeCodec()
                .encodeSuccess(
                        "web",
                        countedProjection(),
                        List.of(FIRST_PAGE, SECOND_PAGE),
                        new RequestMeta("req-2", 200, null, List.of(EXHAUSTED_REQUEST), null),
                        List.of(),
                        false);

        assertArrayEquals(MULTI_SUCCESS_GOLDEN.getBytes(UTF_8), encoded);
        assertTrue(
                schemas.validate("envelope-success.schema.json", encoded).isEmpty(),
                "the multi-request envelope satisfies the published success schema");
    }

    @Test
    void anEntryBodyThatIsNotOneJsonDocumentFailsLoudly() {
        assertThrows(
                UnreadableBodyException.class,
                () -> CodecSamples
                        .envelopeCodec()
                        .encodeSuccess(
                                "web",
                                Projection.empty(),
                                List.of(new PagedSearch.Page<>(
                                        0,
                                        1,
                                        null,
                                        new WebSearchResult(
                                                200,
                                                new UpstreamPayload("gateway exploded <html>".getBytes(UTF_8)),
                                                RateLimitSnapshot.empty(),
                                                null,
                                                null,
                                                null))),
                                new RequestMeta(null, 200, null, List.of(), null),
                                List.of(),
                                false));
    }

    @Test
    void aFailureEnvelopeMayCarryThePaginationCountDetails() {
        byte[] encoded = CodecSamples
                .envelopeCodec()
                .encodeFailure(
                        "web",
                        FailureKind.RATE_LIMITED,
                        "Upstream rate limit exceeded.",
                        true,
                        null,
                        new RequestMeta(null, 429, null, List.of(), null),
                        countFields(),
                        false);

        assertArrayEquals(COUNTED_FAILURE_GOLDEN.getBytes(UTF_8), encoded);
        assertTrue(
                schemas.validate("envelope-error.schema.json", encoded).isEmpty(),
                "the counted failure envelope satisfies the published error schema");
    }

    @Test
    void theFailureDetailsStayNullWhenACountlessOverloadIsUsed() {
        byte[] encoded = CodecSamples
                .envelopeCodec()
                .encodeFailure(
                        "web",
                        FailureKind.RATE_LIMITED,
                        "Upstream rate limit exceeded.",
                        true,
                        null,
                        new RequestMeta(null, 429, null, List.of(), null));

        assertTrue(
                new String(encoded, UTF_8).contains("\"details\":null"),
                "the single-request failure keeps its null details");
    }

    @Test
    void aJsonlErrorRecordMayCarryThePaginationCounts() {
        byte[] encoded = CodecSamples
                .jsonlCodec()
                .encodeError(
                        "web",
                        FailureKind.RATE_LIMITED,
                        "Upstream rate limit exceeded.",
                        true,
                        countFields());

        assertArrayEquals(COUNTED_JSONL_ERROR_GOLDEN.getBytes(UTF_8), encoded);
        assertTrue(
                schemas.validate("jsonl-record.schema.json", encoded).isEmpty(),
                "the counted error record satisfies the published JSONL schema");
    }

    @Test
    void aCountedJsonlErrorRecordCarriesTheFailingExchangeWindowsWhenASnapshotExists() {
        byte[] encoded = CodecSamples
                .jsonlCodec()
                .encodeError(
                        "web",
                        FailureKind.RATE_LIMITED,
                        "Upstream rate limit exceeded.",
                        true,
                        countFields(),
                        List.of(EXHAUSTED_REQUEST));

        assertArrayEquals(COUNTED_JSONL_ERROR_WITH_WINDOWS_GOLDEN.getBytes(UTF_8), encoded);
        assertTrue(
                schemas.validate("jsonl-record.schema.json", encoded).isEmpty(),
                "the windows-carrying error record satisfies the published JSONL schema");
    }

    private static Projection countedProjection() {
        return new Projection(
                List.of(
                        new Projection.Field("result_count", new Projection.Decimal(BigDecimal.valueOf(2))),
                        new Projection.Field("requested_pages", new Projection.Decimal(BigDecimal.valueOf(3))),
                        new Projection.Field("received_pages", new Projection.Decimal(BigDecimal.valueOf(2))),
                        new Projection.Field("duplicates_removed", new Projection.Decimal(BigDecimal.valueOf(1)))));
    }

    private static List<Projection.Field> countFields() {
        return List.of(
                new Projection.Field("requested_pages", new Projection.Decimal(BigDecimal.valueOf(3))),
                new Projection.Field("received_pages", new Projection.Decimal(BigDecimal.valueOf(1))));
    }
}

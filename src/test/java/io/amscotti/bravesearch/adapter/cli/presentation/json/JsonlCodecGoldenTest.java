package io.amscotti.bravesearch.adapter.cli.presentation.json;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.domain.result.Projection;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Byte-exact contract of JSON Lines records and their shared leading fields. */
final class JsonlCodecGoldenTest {

    private static final String RESULT_LINE_GOLDEN =
            """
            {"schema_version":"1","type":"result","command":"web","position":0,"bucket":"web","title":"Cats","url":"https://example.com/cats"}
            """;

    private static final String SUMMARY_LINE_GOLDEN =
            """
            {"schema_version":"1","type":"summary","command":"web","result_count":1,"page":1,"upstream_offset":0,"http_status":200,"request_id":"req-7f3a2b"}
            """;

    private static final String WARNING_LINE_GOLDEN =
            """
            {"schema_version":"1","type":"warning","command":"web","message":"rate limit window reset delayed this request"}
            """;

    private static final String ERROR_LINE_GOLDEN =
            """
            {"schema_version":"1","type":"error","command":"answers","code":"RATE_LIMITED","message":"Upstream rate limit exceeded.","retryable":true}
            """;

    private static final String ERROR_LINE_WITH_WINDOWS_GOLDEN =
            """
            {"schema_version":"1","type":"error","command":"web","code":"RATE_LIMITED","message":"Upstream rate limit exceeded.","retryable":true,"rate_limits":[{"policy":"request","limit":1,"remaining":0,"reset_ms":1000},{"policy":"minute","limit":15,"remaining":14,"reset_ms":42000}]}
            """;

    @Test
    void resultAndSummaryLinesMatchGoldenBytes() {
        assertArrayEquals(RESULT_LINE_GOLDEN.getBytes(StandardCharsets.UTF_8), CodecSamples.jsonlResultLine());
        assertArrayEquals(SUMMARY_LINE_GOLDEN.getBytes(StandardCharsets.UTF_8), CodecSamples.jsonlSummaryLine());
    }

    @Test
    void warningLineMatchesGoldenBytes() {
        assertArrayEquals(WARNING_LINE_GOLDEN.getBytes(StandardCharsets.UTF_8), CodecSamples.jsonlWarningLine());
    }

    @Test
    void errorLineMatchesGoldenBytes() {
        assertArrayEquals(ERROR_LINE_GOLDEN.getBytes(StandardCharsets.UTF_8), CodecSamples.jsonlErrorLine());
    }

    @Test
    void errorLineWithObservedWindowsMatchesGoldenBytes() {
        assertArrayEquals(
                ERROR_LINE_WITH_WINDOWS_GOLDEN.getBytes(StandardCharsets.UTF_8),
                CodecSamples.jsonlErrorLineWithWindows(),
                "a snapshot exists, so the observed windows ride in the error record");
    }

    @Test
    void recordTypesCoverEveryPlannedWireName() {
        assertEquals(
                Set.of(
                        "result",
                        "answer_delta",
                        "citation",
                        "entity",
                        "research_progress",
                        "upstream_event",
                        "warning",
                        "summary",
                        "error"),
                Set.copyOf(JsonlRecordTypes.all()));
    }

    @Test
    void encodedLinesNeverContainAnsiEscapes() {
        for (byte[] line :
                List.of(
                        CodecSamples.jsonlResultLine(),
                        CodecSamples.jsonlSummaryLine(),
                        CodecSamples.jsonlWarningLine(),
                        CodecSamples.jsonlErrorLine())) {
            String text = new String(line, StandardCharsets.UTF_8);
            assertFalse(text.chars().anyMatch(c -> c == 0x1b), () -> "ANSI escape in JSONL line: " + text);
            assertFalse(text.contains("\r"), () -> "carriage return in JSONL line: " + text);
        }
    }

    @Test
    void reservedFramingNamesAreRejectedInFieldRecords() {
        for (String reserved : List.of("schema_version", "type", "command")) {
            IllegalArgumentException failure =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> CodecSamples
                                    .jsonlCodec()
                                    .encodeResult(
                                            "web",
                                            List.of(new Projection.Field(reserved, new Projection.Flag(true)))),
                            () -> reserved + " is owned by the record framing");
            assertTrue(
                    failure.getMessage().contains(reserved),
                    () -> "the rejection must name the reserved name: " + failure.getMessage());
        }
    }
}

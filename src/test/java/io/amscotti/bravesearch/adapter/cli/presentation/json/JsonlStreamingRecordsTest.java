package io.amscotti.bravesearch.adapter.cli.presentation.json;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import io.amscotti.bravesearch.domain.metadata.RateLimitWindow;
import io.amscotti.bravesearch.domain.metadata.Usage;
import io.amscotti.bravesearch.domain.result.Projection;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Byte-exact contract of the streaming record family: the answer delta with its sequence,
 * the citation and entity records of their projected members, the research progress and
 * preserved upstream events, the usage node the terminal summary carries at exact decimal
 * scale, and the terminal error whose partial counts and cost-unknown marker report how far
 * a broken stream reached — including the interruption code of a user's SIGINT.
 */
final class JsonlStreamingRecordsTest {

    private static final JsonMappers MAPPERS = new JsonMappers();

    private static final JsonlCodec JSONL = new JsonlCodec(MAPPERS);

    @Test
    void answerDeltaCarriesTheSequenceAndTheText() {
        assertArrayEquals(
                "{\"schema_version\":\"1\",\"type\":\"answer_delta\",\"command\":\"answers\",\"sequence\":0,\"text\":\"Hel\"}\n"
                        .getBytes(UTF_8),
                JSONL.encodeAnswerDelta("answers", 0, "Hel"));
        assertArrayEquals(
                "{\"schema_version\":\"1\",\"type\":\"answer_delta\",\"command\":\"answers\",\"sequence\":7,\"text\":\"lo\"}\n"
                        .getBytes(UTF_8),
                JSONL.encodeAnswerDelta("answers", 7, "lo"));
    }

    @Test
    void citationAndEntityRecordsCarryTheirProjectedMembers() {
        assertArrayEquals(
                "{\"schema_version\":\"1\",\"type\":\"citation\",\"command\":\"answers\",\"number\":1,\"url\":\"https://brave.com\",\"snippet\":\"a browser\"}\n"
                        .getBytes(UTF_8),
                JSONL.encodeStreamCitation(
                        "answers",
                        List.of(
                                new Projection.Field("number", new Projection.Decimal(BigDecimal.ONE)),
                                new Projection.Field("url", new Projection.Text("https://brave.com")),
                                new Projection.Field("snippet", new Projection.Text("a browser")))));
        assertArrayEquals(
                "{\"schema_version\":\"1\",\"type\":\"entity\",\"command\":\"answers\",\"name\":\"Brave\",\"url\":\"https://brave.com\"}\n"
                        .getBytes(UTF_8),
                JSONL.encodeStreamEntity(
                        "answers",
                        List.of(
                                new Projection.Field("name", new Projection.Text("Brave")),
                                new Projection.Field("url", new Projection.Text("https://brave.com")))));
    }

    @Test
    void researchProgressCarriesTheTagBesideItsPayloadTree() {
        JsonNode payload = MAPPERS.upstreamReader().readTree("{\"queries\":[\"a\",\"b\"]}");

        assertArrayEquals(
                "{\"schema_version\":\"1\",\"type\":\"research_progress\",\"command\":\"answers\",\"tag\":\"queries\",\"payload\":{\"queries\":[\"a\",\"b\"]}}\n"
                        .getBytes(UTF_8),
                JSONL.encodeResearchProgress("answers", "queries", payload));
    }

    @Test
    void upstreamEventPreservesTheTagNameAndRawPayloadText() {
        assertArrayEquals(
                "{\"schema_version\":\"1\",\"type\":\"upstream_event\",\"command\":\"answers\",\"tag\":\"weird\",\"raw\":\"{not json}\"}\n"
                        .getBytes(UTF_8),
                JSONL.encodeUpstreamEvent("answers", "weird", "{not json}"));
    }

    @Test
    void upstreamEventCarriesTheSseEnvelopeMembersExactlyWhenPresent() {
        assertArrayEquals(
                ("{\"schema_version\":\"1\",\"type\":\"upstream_event\",\"command\":\"answers\",\"tag\":\"weather\","
                        + "\"raw\":\"{}\",\"sse_id\":\"evt-9\",\"sse_retry_ms\":500,\"event_name\":\"weather-alert\"}\n")
                        .getBytes(UTF_8),
                JSONL.encodeUpstreamEvent("answers", "weather", "{}", "weather-alert", "evt-9", 500L));
        assertArrayEquals(
                ("{\"schema_version\":\"1\",\"type\":\"upstream_event\",\"command\":\"answers\",\"tag\":\"weather\","
                        + "\"raw\":\"{}\",\"sse_id\":\"evt-9\"}\n")
                        .getBytes(UTF_8),
                JSONL.encodeUpstreamEvent("answers", "weather", "{}", null, "evt-9", null),
                "absent envelope members stay omitted, a present one rides along");
    }

    @Test
    void theUsageNodeMirrorsTheEnvelopeUsageMembersWithNullsForTheUnobserved() {
        JsonNode node = JSONL.usageNode(new Usage(
                1L, 2L, 900L, 120L, null, null, null, null, new BigDecimal("0.0042"), Map.of("future", "held"), List.of()));

        String expected = "{\"requests\":1,\"queries\":2,\"tokens_in\":900,\"tokens_out\":120,\"requests_cost\":null,"
                + "\"queries_cost\":null,\"tokens_in_cost\":null,\"tokens_out_cost\":null,"
                + "\"total_cost\":0.0042,\"unknown\":{\"future\":\"held\"}}";
        assertArrayEquals(expected.getBytes(UTF_8), MAPPERS.outputMapper().writeValueAsBytes(node));
    }

    @Test
    void theTerminalSummaryCarriesCountsAndTheObservedUsageAndNoCostMarkerWhenUsageArrived() {
        assertArrayEquals(
                "{\"schema_version\":\"1\",\"type\":\"summary\",\"command\":\"answers\",\"http_status\":200,\"request_id\":\"req-1\",\"deltas_emitted\":3,\"citations_seen\":1,\"entities_seen\":0,\"usage\":{\"requests\":1,\"queries\":2,\"tokens_in\":900,\"tokens_out\":120,\"requests_cost\":null,\"queries_cost\":null,\"tokens_in_cost\":null,\"tokens_out_cost\":null,\"total_cost\":0.0042,\"unknown\":null},\"cost_unknown\":false}\n"
                        .getBytes(UTF_8),
                JSONL.encodeStreamSummary(
                        "answers",
                        List.of(
                                new Projection.Field("http_status", new Projection.Decimal(BigDecimal.valueOf(200))),
                                new Projection.Field("request_id", new Projection.Text("req-1")),
                                new Projection.Field("deltas_emitted", new Projection.Decimal(BigDecimal.valueOf(3))),
                                new Projection.Field("citations_seen", new Projection.Decimal(BigDecimal.ONE)),
                                new Projection.Field("entities_seen", new Projection.Decimal(BigDecimal.ZERO))),
                        JSONL.usageNode(new Usage(
                                1L, 2L, 900L, 120L, null, null, null, null, new BigDecimal("0.0042"), Map.of(), List.of())),
                        false));
    }

    @Test
    void aSummaryWithoutObservedUsageOmitsTheUsageMemberAndMarksTheCostUnknown() {
        assertArrayEquals(
                "{\"schema_version\":\"1\",\"type\":\"summary\",\"command\":\"answers\",\"http_status\":200,\"deltas_emitted\":1,\"citations_seen\":0,\"entities_seen\":0,\"cost_unknown\":true}\n"
                        .getBytes(UTF_8),
                JSONL.encodeStreamSummary(
                        "answers",
                        List.of(
                                new Projection.Field("http_status", new Projection.Decimal(BigDecimal.valueOf(200))),
                                new Projection.Field("deltas_emitted", new Projection.Decimal(BigDecimal.ONE)),
                                new Projection.Field("citations_seen", new Projection.Decimal(BigDecimal.ZERO)),
                                new Projection.Field("entities_seen", new Projection.Decimal(BigDecimal.ZERO))),
                        null,
                        true));
    }

    @Test
    void theTerminalErrorCarriesPartialCountsAndTheInterruptionCode() {
        assertArrayEquals(
                "{\"schema_version\":\"1\",\"type\":\"error\",\"command\":\"answers\",\"code\":\"INTERRUPTED\",\"message\":\"interrupted before the stream completed\",\"retryable\":false,\"deltas_emitted\":2,\"citations_seen\":0,\"cost_unknown\":true}\n"
                        .getBytes(UTF_8),
                JSONL.encodeStreamError(
                        "answers",
                        "INTERRUPTED",
                        "interrupted before the stream completed",
                        false,
                        2,
                        0,
                        true));
    }

    @Test
    void theTerminalErrorCarriesTheOpenExchangeWindowsWhenHeadersWereObserved() {
        assertArrayEquals(
                ("{\"schema_version\":\"1\",\"type\":\"error\",\"command\":\"answers\","
                        + "\"code\":\"RATE_LIMITED\",\"message\":\"Upstream rate limit exceeded.\",\"retryable\":false,"
                        + "\"deltas_emitted\":1,\"citations_seen\":0,\"cost_unknown\":true,"
                        + "\"rate_limits\":[{\"policy\":\"request\",\"limit\":1,\"remaining\":0,\"reset_ms\":1000}]}\n")
                        .getBytes(UTF_8),
                JSONL.encodeStreamError(
                        "answers",
                        "RATE_LIMITED",
                        "Upstream rate limit exceeded.",
                        false,
                        1,
                        0,
                        true,
                        List.of(new RateLimitWindow("request", 1, 0, java.time.Duration.ofSeconds(1)))),
                "a live exchange observed its open headers, so the windows ride the error record");
    }
}

package io.amscotti.bravesearch.adapter.cli.presentation.json;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.testsupport.SchemaCatalog;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every emitted machine document and line is valid against the published v1 schemas. */
final class OutputSchemaValidationTest {

    private final SchemaCatalog schemas = new SchemaCatalog();

    @Test
    void fullMetaSuccessEnvelopeValidates() {
        assertTrue(
                schemas.validate("envelope-success.schema.json", CodecSamples.fullMetaSuccess()).isEmpty(),
                "success envelope must satisfy envelope-success.schema.json");
    }

    @Test
    void zeroResultSuccessEnvelopeValidates() {
        assertTrue(
                schemas.validate("envelope-success.schema.json", CodecSamples.zeroResultSuccess()).isEmpty(),
                "zero-result envelope must satisfy envelope-success.schema.json");
    }

    @Test
    void successSchemaAcceptsAnEmptyJsonArrayBody() {
        String emptyArrayBody =
                "{\"schema_version\":\"1\",\"ok\":true,\"command\":\"web\","
                        + "\"data\":{\"projection\":{},\"upstream\":[]},"
                        + "\"meta\":{\"request_id\":null,\"http_status\":200,\"api_version\":null,"
                        + "\"rate_limits\":[],\"usage\":null,\"warnings\":[]}}";
        assertTrue(
                schemas.validateText("envelope-success.schema.json", emptyArrayBody).isEmpty(),
                "a single-request command whose decoded body is the empty array emits the bare empty array");
    }

    @Test
    void failureEnvelopesValidate() {
        assertTrue(
                schemas.validate("envelope-error.schema.json", CodecSamples.authenticationFailure()).isEmpty(),
                "authentication failure must satisfy envelope-error.schema.json");
        assertTrue(
                schemas.validate("envelope-error.schema.json", CodecSamples.rateLimitedFailure()).isEmpty(),
                "rate-limited failure must satisfy envelope-error.schema.json");
        assertTrue(
                schemas
                        .validate("envelope-error.schema.json", CodecSamples.rateLimitedFailureWithWindows())
                        .isEmpty(),
                "rate-limited failure with observed windows must satisfy envelope-error.schema.json");
    }

    @Test
    void everyEncodableJsonlLineValidates() {
        for (byte[] line :
                new byte[][] {
                    CodecSamples.jsonlResultLine(),
                    CodecSamples.jsonlSummaryLine(),
                    CodecSamples.jsonlWarningLine(),
                    CodecSamples.jsonlErrorLine(),
                    CodecSamples.jsonlErrorLineWithWindows()
                }) {
            assertTrue(
                    schemas.validate("jsonl-record.schema.json", line).isEmpty(),
                    () -> "JSONL line must satisfy jsonl-record.schema.json: " + new String(line));
        }
    }

    @Test
    void successSchemaRejectsEnvelopeMissingTheWarningsMember() {
        String missingWarnings =
                "{\"schema_version\":\"1\",\"ok\":true,\"command\":\"web\","
                        + "\"data\":{\"projection\":{},\"upstream\":{}},"
                        + "\"meta\":{\"request_id\":null,\"http_status\":200,\"api_version\":null,"
                        + "\"rate_limits\":[],\"usage\":null}}";
        assertFalse(schemas.validateText("envelope-success.schema.json", missingWarnings).isEmpty());
    }

    @Test
    void successSchemaRejectsUnknownTopLevelMember() {
        String unknownMember =
                "{\"schema_version\":\"1\",\"ok\":true,\"command\":\"web\",\"debug\":\"secret\","
                        + "\"data\":{\"projection\":{},\"upstream\":{}},"
                        + "\"meta\":{\"request_id\":null,\"http_status\":200,\"api_version\":null,"
                        + "\"rate_limits\":[],\"usage\":null,\"warnings\":[]}}";
        assertFalse(schemas.validateText("envelope-success.schema.json", unknownMember).isEmpty());
    }

    @Test
    void researchProgressRecordsAcceptAnyValidJsonPayload() {
        assertTrue(
                schemas.validateText(
                                "jsonl-record.schema.json",
                                "{\"schema_version\":\"1\",\"type\":\"research_progress\",\"command\":\"answers\","
                                        + "\"tag\":\"queries\",\"payload\":[\"q1\",\"q2\"]}")
                        .isEmpty(),
                "a research tag may carry a JSON array: its upstream shapes are unverified");
        assertTrue(
                schemas.validateText(
                                "jsonl-record.schema.json",
                                "{\"schema_version\":\"1\",\"type\":\"research_progress\",\"command\":\"answers\","
                                        + "\"tag\":\"thinking\",\"payload\":\"compare schedulers\"}")
                        .isEmpty(),
                "a research tag may carry any valid JSON document the stream validated");
    }

    @Test
    void streamUpstreamEntriesEnforceTheMembersTheirKindCarries() {
        assertTrue(
                schemas.validateText(
                                "envelope-success-streaming.schema.json",
                                streamingEnvelope(
                                        "{\"event_index\":0,\"kind\":\"text\",\"text\":\"Hel\"}",
                                        "{\"event_index\":1,\"kind\":\"tag\",\"tag\":\"queries\",\"payload\":[]}",
                                        "{\"event_index\":2,\"kind\":\"unknown_tag\",\"tag\":\"weird\",\"raw\":\"{not json}\"}",
                                        "{\"event_index\":3,\"kind\":\"passthrough\",\"reason\":\"role\"}"))
                        .isEmpty(),
                "every kind with its own members validates");
        assertFalse(
                schemas.validateText(
                                "envelope-success-streaming.schema.json",
                                streamingEnvelope("{\"event_index\":0,\"kind\":\"text\"}"))
                        .isEmpty(),
                "a text entry without its text member is not contract-conformant");
        assertFalse(
                schemas.validateText(
                                "envelope-success-streaming.schema.json",
                                streamingEnvelope("{\"event_index\":1,\"kind\":\"tag\",\"tag\":\"queries\"}"))
                        .isEmpty(),
                "a tag entry without its payload member is not contract-conformant");
        assertFalse(
                schemas.validateText(
                                "envelope-success-streaming.schema.json",
                                streamingEnvelope("{\"event_index\":2,\"kind\":\"unknown_tag\",\"tag\":\"weird\"}"))
                        .isEmpty(),
                "an unknown_tag entry without its raw member is not contract-conformant");
        assertFalse(
                schemas.validateText(
                                "envelope-success-streaming.schema.json",
                                streamingEnvelope("{\"event_index\":3,\"kind\":\"passthrough\"}"))
                        .isEmpty(),
                "a passthrough entry without its reason member is not contract-conformant");
    }

    @Test
    void placeEnrichmentSummariesPinTheSharedStatusBoundsAndIdentifiers() {
        for (String command : List.of("places.details", "places.describe")) {
            assertTrue(
                    schemas.validateText(
                                    "jsonl-record.schema.json",
                                    placeEnrichmentSummary(
                                            command,
                                            "\"http_status\":200,\"request_id\":\"req-1\",\"api_version\":\"2024-08-01\""))
                            .isEmpty(),
                    () -> "a completed " + command + " walk's summary validates");
            assertFalse(
                    schemas.validateText(
                                    "jsonl-record.schema.json",
                                    placeEnrichmentSummary(command, "\"http_status\":600"))
                            .isEmpty(),
                    () -> command + " shares the 0–599 status bound of every summary record");
            assertFalse(
                    schemas.validateText(
                                    "jsonl-record.schema.json",
                                    placeEnrichmentSummary(command, "\"http_status\":200,\"request_id\":\"\""))
                            .isEmpty(),
                    () -> command + " shares the non-empty identifier bound of every summary record");
        }
    }

    @Test
    void jsonlSchemaRejectsUnknownRecordType() {
        String unknownType =
                "{\"schema_version\":\"1\",\"type\":\"chatter\",\"command\":\"web\",\"message\":\"hi\"}";
        assertFalse(schemas.validateText("jsonl-record.schema.json", unknownType).isEmpty());
    }

    @Test
    void jsonlSchemaRejectsWarningLineWithWrongPayloadType() {
        String wrongPayload =
                "{\"schema_version\":\"1\",\"type\":\"warning\",\"command\":\"web\",\"message\":42}";
        assertFalse(schemas.validateText("jsonl-record.schema.json", wrongPayload).isEmpty());
    }

    @Test
    void webResultRecordsPinTheWebPayloadFields() {
        assertFalse(
                schemas
                        .validateText(
                                "jsonl-record.schema.json",
                                "{\"schema_version\":\"1\",\"type\":\"result\",\"command\":\"web\",\"position\":0,\"title\":\"No bucket\"}")
                        .isEmpty(),
                "a web result record without its bucket is not contract-conformant");
        assertFalse(
                schemas
                        .validateText(
                                "jsonl-record.schema.json",
                                "{\"schema_version\":\"1\",\"type\":\"result\",\"command\":\"web\",\"position\":0,\"bucket\":\"web\",\"stray\":\"field\"}")
                        .isEmpty(),
                "a web result record rejects payload fields outside the pinned set");
        assertFalse(
                schemas
                        .validateText(
                                "jsonl-record.schema.json",
                                "{\"schema_version\":\"1\",\"type\":\"result\",\"command\":\"news\",\"position\":0,\"bucket\":\"web\"}")
                        .isEmpty(),
                "the pinned web result payload belongs to the web command only");
    }

    @Test
    void webSummaryRecordsPinTheSummaryFieldSet() {
        assertFalse(
                schemas
                        .validateText(
                                "jsonl-record.schema.json",
                                "{\"schema_version\":\"1\",\"type\":\"summary\",\"command\":\"web\",\"result_count\":1,\"page\":1,\"upstream_offset\":0}")
                        .isEmpty(),
                "a web summary record without its http_status is not contract-conformant");
        assertFalse(
                schemas
                        .validateText(
                                "jsonl-record.schema.json",
                                "{\"schema_version\":\"1\",\"type\":\"summary\",\"command\":\"web\",\"result_count\":1,\"page\":1,\"upstream_offset\":0,\"http_status\":200,\"usage\":null}")
                        .isEmpty(),
                "a web summary record rejects payload fields outside the pinned set");
        assertTrue(
                schemas
                        .validateText(
                                "jsonl-record.schema.json",
                                "{\"schema_version\":\"1\",\"type\":\"summary\",\"command\":\"web\",\"result_count\":0,\"page\":1,\"upstream_offset\":0,\"http_status\":200,\"request_id\":\"req-1\",\"api_version\":\"2024-08-01\"}")
                        .isEmpty(),
                "the documented optional members complete the pinned summary");
    }

    /** One place enrichment summary record: the walk counts plus the trailing identifiers. */
    private static String placeEnrichmentSummary(String command, String statusAndIdentifiers) {
        return "{\"schema_version\":\"1\",\"type\":\"summary\",\"command\":\"" + command
                + "\",\"id_count\":1,\"returned_count\":1,\"missing_count\":0,"
                + "\"requested_requests\":1,\"received_requests\":1," + statusAndIdentifiers + "}";
    }

    /** One buffered streaming envelope whose decoded event array carries the given entries. */
    private static String streamingEnvelope(String... upstreamEntries) {
        return "{\"schema_version\":\"1\",\"ok\":true,\"command\":\"answers\","
                + "\"data\":{\"projection\":{\"delta_count\":0,\"citation_count\":0,\"citations\":[],"
                + "\"entity_count\":0,\"entities\":[]},\"upstream\":["
                + String.join(",", upstreamEntries)
                + "]},\"meta\":{\"request_id\":null,\"http_status\":200,\"api_version\":null,"
                + "\"rate_limits\":[],\"usage\":null,\"warnings\":[]}}";
    }
}

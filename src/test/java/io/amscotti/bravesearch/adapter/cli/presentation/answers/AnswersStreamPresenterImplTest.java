package io.amscotti.bravesearch.adapter.cli.presentation.answers;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.amscotti.bravesearch.adapter.cli.presentation.AnswersStreamPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.DiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.SearchPresenterBase;
import io.amscotti.bravesearch.adapter.cli.presentation.WriterDiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.application.port.out.AnswersStreamExchange;
import io.amscotti.bravesearch.application.stream.AbruptEofException;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.domain.answer.AnswerDecodeException;
import io.amscotti.bravesearch.domain.answer.AnswerStreamEvent;
import io.amscotti.bravesearch.domain.metadata.RequestMeta;
import io.amscotti.bravesearch.domain.output.OutputMode;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.request.AnswersRequest;
import io.amscotti.bravesearch.testsupport.SchemaCatalog;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * The streaming renderers of one open answers exchange: human writes its text deltas as
 * separate flushed documents and appends the citations, entities, and usage summary at the
 * terminal; JSONL emits the sequenced record stream with its terminal summary or error —
 * partial counts and the cost-unknown marker included; buffered JSON accumulates the whole
 * stream under its byte ceiling and emits exactly one envelope whose upstream is the decoded
 * event array and whose warnings carry the jsonl advisory; raw relays the decoded bytes with
 * no parsing; and the shared terminal resolution keeps the documented exits — interruption
 * 130, broken pipe silent 0, typed decode failures 8, deadline and transport endings 6.
 */
final class AnswersStreamPresenterImplTest {

    private static final ObjectMapper READER = new ObjectMapper();

    private static final String QUESTION = "what is the brave search api";

    private final JsonMappers mappers = new JsonMappers();

    private final JsonlCodec jsonl = new JsonlCodec(mappers);

    private final AnswersStreamPresenter renderer =
            new AnswersStreamPresenterImpl(new EnvelopeCodec(mappers), jsonl, mappers, 16 * 1024 * 1024, warnings());

    @Test
    void humanStreamWritesEachDeltaAsItsOwnDocumentAndAppendsTheTerminalSections() throws Exception {
        ScriptedStream script = new ScriptedStream(List.of(
                new AnswerStreamEvent.Text("Brave "),
                new AnswerStreamEvent.Text("Search is an independent index."),
                tagged("citation", "{\"number\":1,\"url\":\"https://brave.com\",\"snippet\":\"a browser\"}"),
                tagged("entity", "{\"name\":\"Brave Search\",\"url\":\"https://search.brave.com\"}"),
                tagged("usage", "{\"requests\":1,\"queries\":2,\"tokens_in\":900,\"tokens_out\":120,\"total_cost\":\"0.0042\"}"),
                new AnswerStreamEvent.Passthrough("finish_reason")));
        RecordingWriter results = new RecordingWriter();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exit = render(script, output(OutputMode.HUMAN, false, false), results, stderr);

        assertEquals(0, exit);
        assertEquals(
                """
                Answer for: what is the brave search api
                Brave Search is an independent index.

                Citations:
                1. https://brave.com
                   a browser

                Entities:
                - Brave Search (https://search.brave.com)

                Usage: requests 1, queries 2, tokens 900 in / 120 out, total cost 0.0042
                """,
                results.written());
        assertEquals("Answer for: what is the brave search api\n", new String(results.documentAt(0), UTF_8));
        assertEquals("Brave ", new String(results.documentAt(1), UTF_8), "the first delta is its own document");
        assertEquals(
                "Search is an independent index.",
                new String(results.documentAt(2), UTF_8),
                "the second delta is its own document before any terminal section");
        assertEquals("", stderr.toString(), () -> "a clean human stream stays silent on stderr");
    }

    @Test
    void humanStreamSanitizesUpstreamTerminalControlsInDeltasCitationsEntitiesAndProgress() throws Exception {
        ScriptedStream script = new ScriptedStream(List.of(
                new AnswerStreamEvent.Text("an[2Jswe]8;;https://evil.example\\r"),
                tagged(
                        "citation",
                        "{\"url\":\"https://brave.com/\\u001b[1mstylish\",\"snippet\":\"sn\\u0007ippet\"}"),
                tagged("entity", "{\"name\":\"Ent\\u001b[?25lity\"}"),
                tagged("queries", "{\"queries\":[\"pro[Agress\"]}")));
        RecordingWriter results = new RecordingWriter();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exit = render(script, output(OutputMode.HUMAN, false, false), results, stderr);

        assertEquals(0, exit);
        String human = results.written();
        assertFalse(human.contains("\u001b"), "no escape byte may reach the human stream");
        assertFalse(human.contains("evil.example"), "a forged hyperlink must vanish wholly");
        assertFalse(human.contains("\u0007"), "no BEL may reach the human stream");
        assertTrue(human.contains("answer"), () -> "escape-free delta text survives: human=<" + human + ">");
        assertTrue(human.contains("https://brave.com/stylish"), "the citation url sheds its styling");
        assertTrue(human.contains("sn\ufffdippet"), "a lone control becomes a replacement character");
        assertTrue(human.contains("Entity"), "the entity name sheds its cursor-hiding sequence");
        String progress = stderr.toString();
        assertFalse(progress.contains("\u001b"), "no escape byte may reach a progress line");
        assertTrue(progress.contains("progress") || progress.contains("pro"), "progress text stays readable");
    }

    @Test
    void researchProgressRendersAsConciseStderrLinesSuppressedByQuiet() throws Exception {
        ScriptedStream script = new ScriptedStream(List.of(
                new AnswerStreamEvent.Text("working"),
                tagged("queries", "{\"queries\":[\"brave api\"]}"),
                tagged("analyzing", "{\"step\":2}")));
        RecordingWriter results = new RecordingWriter();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exit = render(script, output(OutputMode.HUMAN, false, false), results, stderr);

        assertEquals(0, exit);
        List<String> lines = stderr.toString().lines().toList();
        assertEquals(2, lines.size(), () -> "one progress line per research event: " + stderr);
        assertTrue(lines.getFirst().contains("research queries"), () -> lines.toString());

        RecordingWriter quietResults = new RecordingWriter();
        ByteArrayOutputStream quietStderr = new ByteArrayOutputStream();
        render(script, output(OutputMode.HUMAN, false, true), quietResults, quietStderr);
        assertEquals("", quietStderr.toString(), "--quiet suppresses the advisory progress lines");
    }

    @Test
    void aProgressQuoteNeverSplitsASurrogatePair() throws Exception {
        // the quote limit lands inside an astral character, so a UTF-16-unit cut would
        // emit a lone surrogate that encodes to mojibake on the diagnostics line
        String payload = "x" + "😀".repeat(90);
        ScriptedStream script = new ScriptedStream(List.of(
                new AnswerStreamEvent.Text("working"),
                tagged("thinking", payload)));
        RecordingWriter results = new RecordingWriter();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exit = render(script, output(OutputMode.HUMAN, false, false), results, stderr);

        assertEquals(0, exit);
        assertEquals(
                "answers: research thinking: x" + "😀".repeat(79) + "…\n",
                stderr.toString(UTF_8),
                () -> "the truncated quote ends on a whole character: " + stderr);
    }

    @Test
    void aColorableRenderContextStylesProgressLabelsAndTheUsageLabel() throws Exception {
        ScriptedStream script = new ScriptedStream(List.of(
                new AnswerStreamEvent.Text("working"),
                tagged("queries", "{\"queries\":[\"brave api\"]}"),
                tagged(
                        "usage",
                        "{\"requests\":1,\"queries\":2,\"tokens_in\":900,\"tokens_out\":120,\"total_cost\":\"0.0042\"}")));
        RecordingWriter results = new RecordingWriter();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exit = render(
                script,
                new OutputRequest(true, OutputMode.HUMAN, false, false, false, true, 100),
                results,
                stderr);

        assertEquals(0, exit);
        assertEquals(
                "\u001b[1manswers: research queries:\u001b[0m {\"queries\":[\"brave api\"]}\n",
                stderr.toString(UTF_8),
                () -> "the label renders bold with the payload plain: " + stderr);
        String stdout = results.written();
        assertTrue(
                stdout.endsWith(
                        "\n\u001b[1mUsage:\u001b[0m requests 1, queries 2, tokens 900 in / 120 out, total cost 0.0042\n"),
                () -> "the usage summary line carries a bold label: " + stdout);
    }

    @Test
    void aStreamWhoseEveryDeltaSanitizesToNothingRendersNoAnswer() throws Exception {
        ScriptedStream script = new ScriptedStream(List.of(
                new AnswerStreamEvent.Text("\u001b[2J"),
                new AnswerStreamEvent.Text("\u001b]8;;https://evil.example\u001b\\")));
        RecordingWriter results = new RecordingWriter();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exit = render(script, output(OutputMode.HUMAN, false, false), results, stderr);

        assertEquals(0, exit, "a delta run that sanitizes to nothing is a rendered run, not a crash");
        assertEquals(
                """
                Answer for: what is the brave search api
                No answer.
                """,
                results.written());
        assertEquals("", stderr.toString());
    }

    @Test
    void anUnparsableUsageMemberDropsAloneAndItsNoteRoutesToStderr() throws Exception {
        ScriptedStream script = new ScriptedStream(List.of(
                new AnswerStreamEvent.Text("done"),
                tagged("usage", "{\"requests\":\"many\",\"queries\":2}")));
        RecordingWriter results = new RecordingWriter();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exit = render(script, output(OutputMode.HUMAN, false, false), results, stderr);

        assertEquals(0, exit);
        assertTrue(results.written().endsWith("Usage: queries 2\n"), () -> results.written());
        List<String> lines = stderr.toString().lines().toList();
        assertEquals(List.of("requests is not a nonnegative integer"), lines, () -> stderr.toString());

        RecordingWriter quietResults = new RecordingWriter();
        int quiet =
                render(script, output(OutputMode.HUMAN, false, true), quietResults, new ByteArrayOutputStream());
        assertEquals(0, quiet);
        assertTrue(quietResults.written().endsWith("Usage: queries 2\n"), () -> quietResults.written());
    }

    @Test
    void usageParseNotesRideAsWarningRecordsAheadOfTheJsonlSummary() throws Exception {
        ScriptedStream script = new ScriptedStream(List.of(
                new AnswerStreamEvent.Text("done"),
                tagged("usage", "{\"requests\":\"many\",\"queries\":2,\"total_cost\":\"cheap\"}")));
        RecordingWriter results = new RecordingWriter();

        int exit = render(script, output(OutputMode.JSONL, false, false), results, new ByteArrayOutputStream());

        assertEquals(0, exit);
        String[] lines = results.written().split("\n", -1);
        assertEquals(4, lines.length - 1, "one delta record, two warning records, one summary record");
        assertTrue(
                lines[1].contains("\"type\":\"warning\"")
                        && lines[1].contains("requests is not a nonnegative integer"),
                lines[1]);
        assertTrue(
                lines[2].contains("\"type\":\"warning\"") && lines[2].contains("total_cost is not a nonnegative decimal"),
                lines[2]);
        assertEquals("summary", READER.readTree(lines[3]).path("type").asText(), "the summary stays terminal");
    }

    @Test
    void bufferedJsonCarriesUsageParseNotesBesideTheAdvisory() throws Exception {
        ScriptedStream script = new ScriptedStream(List.of(
                new AnswerStreamEvent.Text("done"),
                tagged("usage", "{\"requests\":\"many\",\"queries\":2}")));
        RecordingWriter results = new RecordingWriter();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exit = render(script, output(OutputMode.JSON, false, false), results, stderr);

        assertEquals(0, exit);
        assertEquals("", stderr.toString());
        JsonNode warnings = READER.readTree(results.written()).path("meta").path("warnings");
        assertEquals(2, warnings.size(), () -> warnings.toString());
        assertEquals("requests is not a nonnegative integer", warnings.get(0).asText());
        assertTrue(warnings.get(1).asText().contains("jsonl is preferable"), warnings.get(1).asText());
    }

    @Test
    void jsonlStreamEmitsSequencedDeltasEveryEventRecordAndTheTerminalSummary() throws Exception {
        ScriptedStream script = new ScriptedStream(List.of(
                new AnswerStreamEvent.Text("Hel"),
                new AnswerStreamEvent.Text("lo"),
                tagged("citation", "{\"number\":1,\"url\":\"https://brave.com\"}"),
                tagged("entity", "{\"name\":\"Brave\"}"),
                tagged("queries", "{\"queries\":[\"q\"]}"),
                new AnswerStreamEvent.UnknownTag("weird", "{not json}"),
                tagged("usage", "{\"requests\":1,\"queries\":2,\"tokens_in\":3,\"tokens_out\":4,\"total_cost\":\"0.0042\"}")));
        RecordingWriter results = new RecordingWriter();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exit = render(script, output(OutputMode.JSONL, false, false), results, stderr);

        assertEquals(0, exit);
        assertEquals("", stderr.toString());
        String[] lines = results.written().split("\n", -1);
        assertEquals(7, lines.length - 1, "six event records and one summary record");
        assertEquals(
                "{\"schema_version\":\"1\",\"type\":\"answer_delta\",\"command\":\"answers\",\"sequence\":0,\"text\":\"Hel\"}",
                lines[0]);
        assertEquals(
                "{\"schema_version\":\"1\",\"type\":\"answer_delta\",\"command\":\"answers\",\"sequence\":1,\"text\":\"lo\"}",
                lines[1]);
        assertEquals(
                "{\"schema_version\":\"1\",\"type\":\"citation\",\"command\":\"answers\",\"number\":1,\"url\":\"https://brave.com\"}",
                lines[2]);
        assertEquals(
                "{\"schema_version\":\"1\",\"type\":\"entity\",\"command\":\"answers\",\"name\":\"Brave\"}", lines[3]);
        assertTrue(lines[4].contains("\"type\":\"research_progress\"") && lines[4].contains("\"tag\":\"queries\""));
        assertTrue(
                lines[5].contains("\"type\":\"upstream_event\"") && lines[5].contains("\"tag\":\"weird\""),
                lines[5]);
        JsonNode summary = READER.readTree(lines[6]);
        assertEquals("summary", summary.path("type").asText());
        assertEquals(200, summary.path("http_status").asInt());
        assertEquals("req-stream-1", summary.path("request_id").asText());
        assertEquals(2, summary.path("deltas_emitted").asInt());
        assertEquals(1, summary.path("citations_seen").asInt());
        assertEquals(1, summary.path("entities_seen").asInt());
        assertEquals("0.0042", summary.path("usage").path("total_cost").asText());
        assertTrue(!summary.path("cost_unknown").asBoolean());
    }

    @Test
    void jsonlUpstreamEventsCarryTheSseEnvelopeWhenTheBlockHadOne() throws Exception {
        ScriptedStream script = new ScriptedStream(List.of(
                new AnswerStreamEvent.UnknownTag("weather", "{}", "weather-alert", "evt-9", 500L),
                new AnswerStreamEvent.UnknownTag("plain", "{}")));
        RecordingWriter results = new RecordingWriter();

        int exit = render(script, output(OutputMode.JSONL, false, false), results, new ByteArrayOutputStream());

        assertEquals(0, exit);
        String[] lines = results.written().split("\n", -1);
        assertTrue(
                lines[0].contains("\"sse_id\":\"evt-9\"")
                        && lines[0].contains("\"sse_retry_ms\":500")
                        && lines[0].contains("\"event_name\":\"weather-alert\""),
                lines[0]);
        assertFalse(
                lines[1].contains("sse_id") || lines[1].contains("event_name") || lines[1].contains("sse_retry_ms"),
                "a block without envelope members records none: " + lines[1]);
    }

    @Test
    void aStreamWithoutAFinalUsageSummarizesWithTheCostUnknownMarker() throws Exception {
        ScriptedStream script = new ScriptedStream(List.of(new AnswerStreamEvent.Text("partial")));
        RecordingWriter results = new RecordingWriter();

        int exit = render(script, output(OutputMode.JSONL, false, false), results, new ByteArrayOutputStream());

        assertEquals(0, exit);
        String last = lastRecord(results);
        JsonNode summary = READER.readTree(last);
        assertTrue(summary.path("cost_unknown").asBoolean());
        assertTrue(summary.path("usage").isMissingNode(), "no usage member is invented");
    }

    @Test
    void bufferedJsonEmitsExactlyOneEnvelopeWithTheDecodedEventUpstreamAndTheAdvisory() throws Exception {
        ScriptedStream script = new ScriptedStream(List.of(
                new AnswerStreamEvent.Text("Hel"),
                new AnswerStreamEvent.Text("lo"),
                tagged("citation", "{\"number\":1,\"url\":\"https://brave.com\"}"),
                tagged("entity", "{\"name\":\"Brave\"}"),
                tagged("usage", "{\"requests\":1,\"queries\":2,\"tokens_in\":900,\"tokens_out\":120,\"total_cost\":\"0.0042\"}")));
        RecordingWriter results = new RecordingWriter();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exit = render(script, output(OutputMode.JSON, false, false), results, stderr);

        assertEquals(0, exit);
        assertEquals("", stderr.toString());
        String stdout = results.written();
        assertTrue(stdout.indexOf('\n') == stdout.length() - 1, "exactly one LF-terminated envelope");
        JsonNode envelope = READER.readTree(stdout);
        assertEquals("answers", envelope.path("command").asText());
        JsonNode projection = envelope.path("data").path("projection");
        assertEquals("Hello", projection.path("answer").asText());
        assertEquals(2, projection.path("delta_count").asInt());
        assertEquals(1, projection.path("citation_count").asInt());
        assertEquals(1, projection.path("entity_count").asInt());
        assertEquals("https://brave.com", projection.path("citations").get(0).path("url").asText());
        JsonNode upstream = envelope.path("data").path("upstream");
        assertTrue(upstream.isArray());
        assertEquals(5, upstream.size(), "every decoded event rides the upstream array");
        assertEquals("text", upstream.get(0).path("kind").asText());
        assertEquals(0, upstream.get(0).path("event_index").asInt());
        assertEquals("Hel", upstream.get(0).path("text").asText());
        assertEquals("lo", upstream.get(1).path("text").asText());
        assertEquals("tag", upstream.get(2).path("kind").asText());
        assertEquals("citation", upstream.get(2).path("tag").asText());
        assertEquals("https://brave.com", upstream.get(2).path("payload").path("url").asText());
        JsonNode warnings = envelope.path("meta").path("warnings");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).asText().contains("jsonl is preferable"), warnings.get(0).asText());
        assertEquals("0.0042", envelope.path("meta").path("usage").path("total_cost").asText());
        assertEquals("2026-08-30", envelope.path("meta").path("api_version").asText());
    }

    @Test
    void anEntityKindLandsAsEntityTypeBesideTheFramingTypeInBothMachineForms() throws Exception {
        ScriptedStream script = new ScriptedStream(List.of(
                tagged(
                        "entity",
                        "{\"name\":\"Brave Search\",\"url\":\"https://search.brave.com\",\"type\":\"product\"}"),
                tagged("usage", "{\"requests\":1,\"queries\":2}")));
        SchemaCatalog schemas = new SchemaCatalog();

        RecordingWriter jsonlResults = new RecordingWriter();
        int jsonl = render(script, output(OutputMode.JSONL, false, false), jsonlResults, new ByteArrayOutputStream());
        assertEquals(0, jsonl, "an entity carrying its kind member must not end the run");
        String record = jsonlResults.written().split("\n", -1)[0];
        JsonNode entity = READER.readTree(record);
        assertEquals("entity", entity.path("type").asText(), "the framing type keeps the record discriminator");
        assertEquals("product", entity.path("entity_type").asText(), "the payload's kind lands under its own name");
        assertTrue(
                schemas.validateText("jsonl-record.schema.json", record).isEmpty(),
                "the entity record satisfies jsonl-record.schema.json: " + record);

        RecordingWriter jsonResults = new RecordingWriter();
        int json = render(script, output(OutputMode.JSON, false, false), jsonResults, new ByteArrayOutputStream());
        assertEquals(0, json);
        String envelope = jsonResults.written();
        assertTrue(
                schemas.validateText("envelope-success.schema.json", envelope).isEmpty(),
                "the buffered envelope satisfies envelope-success.schema.json: " + envelope);
        assertTrue(
                schemas.validateText("envelope-success-streaming.schema.json", envelope).isEmpty(),
                "the buffered envelope satisfies envelope-success-streaming.schema.json: " + envelope);
        JsonNode projected = READER.readTree(envelope).path("data").path("projection").path("entities").get(0);
        assertEquals("product", projected.path("entity_type").asText());
    }

    @Test
    void aUrllessStreamedCitationNumberedZeroValidatesInBothMachineForms() throws Exception {
        ScriptedStream script = new ScriptedStream(List.of(
                tagged("citation", "{\"number\":0,\"snippet\":\"an index without its url\"}"),
                tagged("usage", "{\"requests\":1}")));
        SchemaCatalog schemas = new SchemaCatalog();

        RecordingWriter jsonlResults = new RecordingWriter();
        int jsonl = render(script, output(OutputMode.JSONL, false, false), jsonlResults, new ByteArrayOutputStream());
        assertEquals(0, jsonl);
        String record = jsonlResults.written().split("\n", -1)[0];
        assertTrue(
                schemas.validateText("jsonl-record.schema.json", record).isEmpty(),
                "a url-less citation numbered zero satisfies jsonl-record.schema.json: " + record);

        RecordingWriter jsonResults = new RecordingWriter();
        int json = render(script, output(OutputMode.JSON, false, false), jsonResults, new ByteArrayOutputStream());
        assertEquals(0, json);
        String envelope = jsonResults.written();
        assertTrue(
                schemas.validateText("envelope-success-streaming.schema.json", envelope).isEmpty(),
                "the buffered envelope with its url-less citation satisfies the streaming schema: " + envelope);
        JsonNode citation = READER.readTree(envelope).path("data").path("projection").path("citations").get(0);
        assertEquals(0, citation.path("number").asInt());
        assertTrue(citation.path("url").isMissingNode(), "no url member is invented");
    }

    @Test
    void aResearchTagCarryingANonObjectPayloadValidatesInBothMachineForms() throws Exception {
        ScriptedStream script = new ScriptedStream(List.of(
                tagged("queries", "[\"low-power mesh\",\"routing benchmarks\"]"),
                tagged("thinking", "\"compare schedulers\""),
                tagged("usage", "{\"requests\":1}")));
        SchemaCatalog schemas = new SchemaCatalog();

        RecordingWriter jsonlResults = new RecordingWriter();
        int jsonl = render(script, output(OutputMode.JSONL, false, false), jsonlResults, new ByteArrayOutputStream());
        assertEquals(0, jsonl);
        String[] records = jsonlResults.written().split("\n", -1);
        assertTrue(
                schemas.validateText("jsonl-record.schema.json", records[0]).isEmpty(),
                "an array payload satisfies the published research_progress record: " + records[0]);
        assertTrue(
                schemas.validateText("jsonl-record.schema.json", records[1]).isEmpty(),
                "a textual payload satisfies the published research_progress record: " + records[1]);

        RecordingWriter jsonResults = new RecordingWriter();
        int json = render(script, output(OutputMode.JSON, false, false), jsonResults, new ByteArrayOutputStream());
        assertEquals(0, json);
        String envelope = jsonResults.written();
        assertTrue(
                schemas.validateText("envelope-success-streaming.schema.json", envelope).isEmpty(),
                "the buffered envelope whose tag entries carry non-object payloads satisfies the streaming schema: "
                        + envelope);
        JsonNode upstream = READER.readTree(envelope).path("data").path("upstream");
        assertEquals(3, upstream.size(), "the two research tags beside the buffered usage tag");
        assertTrue(upstream.get(0).path("payload").isArray(), envelope);
        assertTrue(upstream.get(1).path("payload").isTextual(), envelope);
        assertEquals("usage", upstream.get(2).path("tag").asText(), envelope);
    }

    @Test
    void aFailedBufferedJsonRunEmitsAStreamDetailsEnvelopeItsSchemaAccepts() throws Exception {
        ScriptedStream script = new ScriptedStream(List.of(
                new AnswerStreamEvent.Text("par"),
                tagged("citation", "{\"number\":1,\"url\":\"https://brave.com\"}")));
        script.endWith(new AbruptEofException("the stream body broke before its terminator", null));
        RecordingWriter results = new RecordingWriter();

        int exit = render(script, output(OutputMode.JSON, false, false), results, new ByteArrayOutputStream());

        assertEquals(6, exit);
        String stdout = results.written();
        assertTrue(stdout.indexOf('\n') == stdout.length() - 1, "exactly one LF-terminated envelope");
        assertTrue(
                new SchemaCatalog().validateText("envelope-error.schema.json", stdout).isEmpty(),
                "the streamed failure envelope satisfies envelope-error.schema.json: " + stdout);
        JsonNode details = READER.readTree(stdout).path("error").path("details");
        assertEquals(1, details.path("deltas_emitted").asInt());
        assertEquals(1, details.path("citations_seen").asInt());
        assertTrue(details.path("cost_unknown").asBoolean(), "no final usage arrived");
    }

    @Test
    void bufferedAccumulationBeyondTheCeilingFailsMalformed() throws Exception {
        ScriptedStream script = new ScriptedStream(
                List.of(new AnswerStreamEvent.Text("x".repeat(64)), new AnswerStreamEvent.Text("y".repeat(64))));
        RecordingWriter results = new RecordingWriter();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        AnswersStreamPresenter bounded =
                new AnswersStreamPresenterImpl(new EnvelopeCodec(mappers), new JsonlCodec(mappers), mappers, 32, warnings());

        int exit = render(bounded, script, output(OutputMode.JSON, false, false), results, stderr);

        assertEquals(8, exit, "an over-ceiling buffered stream is the malformed failure");
        JsonNode envelope = READER.readTree(results.written());
        assertEquals("MALFORMED_RESPONSE", envelope.path("error").path("code").asText());
        assertEquals(
                0,
                envelope.path("error").path("details").path("deltas_emitted").asInt(),
                "the event that breached the ceiling never counts as emitted");
    }

    @Test
    void rawStreamRelaysTheDecodedBytesUnparsed() throws Exception {
        FakeExchange exchange = new FakeExchange(
                new ScriptedBytes(List.of("data: {\"ch".getBytes(UTF_8), "oices\":1}\n\n".getBytes(UTF_8))),
                new ScriptedStream(List.of()));
        RecordingWriter results = new RecordingWriter();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exit = renderer.present(
                exchange, request(), output(OutputMode.RAW, false, false), results, sink(stderr));

        assertEquals(0, exit);
        assertEquals("data: {\"choices\":1}\n\n", results.written());
        assertEquals(2, results.writeCount(), "each decoded chunk is its own flushed document");
        assertEquals("", stderr.toString());
    }

    @Test
    void anAbruptEndWithoutUsageIsATransportFailureCarryingTheCostUnknownMarker() throws Exception {
        ScriptedStream script = new ScriptedStream(List.of(new AnswerStreamEvent.Text("par")));
        script.endWith(new AbruptEofException("the stream body broke before its terminator", null));
        RecordingWriter results = new RecordingWriter();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int human = render(script, output(OutputMode.HUMAN, false, false), results, stderr);

        assertEquals(6, human);
        assertEquals("Answer for: what is the brave search api\npar", results.written());
        List<String> lines = stderr.toString().lines().toList();
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains("broke before its terminator"), lines.toString());
        assertTrue(lines.getFirst().contains("cost unknown"), lines.toString());

        RecordingWriter jsonlResults = new RecordingWriter();
        int jsonl = render(script, output(OutputMode.JSONL, false, false), jsonlResults, new ByteArrayOutputStream());
        assertEquals(6, jsonl);
        JsonNode error = READER.readTree(lastRecord(jsonlResults));
        assertEquals("TRANSPORT_ERROR", error.path("code").asText());
        assertEquals(1, error.path("deltas_emitted").asInt());
        assertTrue(error.path("cost_unknown").asBoolean());
    }

    @Test
    void typedDecodeFailuresKeepTheMalformedStatus() throws Exception {
        for (AnswerDecodeException decode : new AnswerDecodeException[] {
            new AnswerDecodeException.MalformedTagPayload("the payload of a <usage> tag is not valid JSON"),
            new AnswerDecodeException.UnterminatedTag("a <citation> tag never closed before the stream ended")
        }) {
            ScriptedStream script = new ScriptedStream(List.of(new AnswerStreamEvent.Text("x")));
            script.endWith(decode);
            RecordingWriter results = new RecordingWriter();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();

            int exit = render(script, output(OutputMode.HUMAN, false, false), results, stderr);

            assertEquals(8, exit, decode.getMessage());
            assertTrue(stderr.toString().lines().count() == 1, stderr.toString());
        }
    }

    @Test
    void anIdleDeadlineIsATransportDiagnostic() throws Exception {
        ScriptedStream script = new ScriptedStream(List.of());
        script.latchThenEnd(CancellationContext.Cause.IDLE_TIMEOUT);

        RecordingWriter results = new RecordingWriter();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = render(script, output(OutputMode.HUMAN, false, false), results, stderr);

        assertEquals(6, exit);
        assertTrue(stderr.toString().contains("idle"), stderr.toString());
    }

    @Test
    void interruptionRendersTheTerminalRecordAndKeepsTheSignalExit() throws Exception {
        ScriptedStream script = new ScriptedStream(List.of(
                new AnswerStreamEvent.Text("one"), new AnswerStreamEvent.Text("two")));
        script.latchThenEnd(CancellationContext.Cause.SIGINT);

        RecordingWriter jsonlResults = new RecordingWriter();
        int jsonl = render(script, output(OutputMode.JSONL, false, false), jsonlResults, new ByteArrayOutputStream());
        assertEquals(130, jsonl);
        JsonNode error = READER.readTree(lastRecord(jsonlResults));
        assertEquals("INTERRUPTED", error.path("code").asText());
        assertEquals(2, error.path("deltas_emitted").asInt());
        assertTrue(error.path("cost_unknown").asBoolean());

        RecordingWriter humanResults = new RecordingWriter();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int human = render(script, output(OutputMode.HUMAN, false, false), humanResults, stderr);
        assertEquals(130, human);
        assertEquals(1, stderr.toString().lines().count(), "one diagnostic names the interruption");
        assertTrue(stderr.toString().contains("interrupted"), stderr.toString());
        assertEquals("Answer for: what is the brave search api\nonetwo", humanResults.written());
    }

    @Test
    void terminationRendersTheTerminalRecordAndKeepsTheTerminationExit() throws Exception {
        ScriptedStream script = new ScriptedStream(List.of(new AnswerStreamEvent.Text("one")));
        script.latchThenEnd(CancellationContext.Cause.SIGTERM);

        RecordingWriter jsonlResults = new RecordingWriter();
        int jsonl = render(script, output(OutputMode.JSONL, false, false), jsonlResults, new ByteArrayOutputStream());
        assertEquals(143, jsonl);
        JsonNode error = READER.readTree(lastRecord(jsonlResults));
        assertEquals("TERMINATED", error.path("code").asText());
        assertEquals(1, error.path("deltas_emitted").asInt());
        assertTrue(error.path("cost_unknown").asBoolean());

        RecordingWriter humanResults = new RecordingWriter();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int human = render(script, output(OutputMode.HUMAN, false, false), humanResults, stderr);
        assertEquals(143, human);
        assertEquals(1, stderr.toString().lines().count(), "one diagnostic names the termination");
        assertTrue(stderr.toString().contains("terminated"), stderr.toString());
    }

    @Test
    void aConsumerDepartingBeforeTheHeadingWriteIsSilentZero() throws Exception {
        ScriptedStream script = new ScriptedStream(List.of(new AnswerStreamEvent.Text("never reached")));
        RecordingWriter results = new RecordingWriter();
        results.failFromWrite(1);
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exit = render(script, output(OutputMode.HUMAN, false, false), results, stderr);

        assertEquals(0, exit, "the heading's broken pipe is silent success like every other first write");
        assertEquals("", stderr.toString(), "a broken pipe on the heading is silent");
        assertEquals(0, results.writeCount(), "nothing is written after the consumer left");
    }

    @Test
    void aDownstreamBrokenPipeIsSilentZeroWithNoFurtherDocuments() throws Exception {
        ScriptedStream script = new ScriptedStream(
                List.of(new AnswerStreamEvent.Text("first"), new AnswerStreamEvent.Text("second")));
        RecordingWriter results = new RecordingWriter();
        results.failFromWrite(3);
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exit = render(script, output(OutputMode.HUMAN, false, false), results, stderr);

        assertEquals(0, exit);
        assertEquals("", stderr.toString(), "a broken pipe is silent");
        assertEquals(2, results.writeCount(), "nothing is written after the consumer left");
    }

    @Test
    void aNonPipeHeadingWriteFailureKeepsTheTransportExitWithOneDiagnosticLine() throws Exception {
        ScriptedStream script = new ScriptedStream(List.of(new AnswerStreamEvent.Text("never reached")));
        RecordingWriter results = new RecordingWriter();
        results.failFromWriteWith(1, "No space left on device");
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exit = render(script, output(OutputMode.HUMAN, false, false), results, stderr);

        assertEquals(6, exit, "a non-pipe heading write failure keeps the transport status");
        assertEquals(
                "answers: writing the result document failed\n",
                stderr.toString(),
                "the heading write failure explains itself in exactly one line");
        assertEquals(0, results.writeCount(), "nothing is written after the failed heading");
    }

    @Test
    void verboseFailuresCarryTheActivityInstants() throws Exception {
        ScriptedStream script = new ScriptedStream(List.of(new AnswerStreamEvent.Text("x")));
        script.endWith(new AbruptEofException("the stream body broke before its terminator", null));
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        render(script, output(OutputMode.HUMAN, true, false), new RecordingWriter(), stderr);

        assertTrue(
                stderr.toString().lines().anyMatch(line -> line.contains("last transport activity")),
                stderr.toString());
    }

    private static String lastRecord(RecordingWriter results) {
        String[] lines = results.written().split("\n", -1);
        return lines[lines.length - 2];
    }

    private int render(
            ScriptedStream script, OutputRequest output, RecordingWriter results, ByteArrayOutputStream stderr) {
        return render(renderer, script, output, results, stderr);
    }

    private int render(
            AnswersStreamPresenter presenter,
            ScriptedStream script,
            OutputRequest output,
            RecordingWriter results,
            ByteArrayOutputStream stderr) {
        return presenter.present(
                new FakeExchange(new ScriptedBytes(List.of()), script), request(), output, results, sink(stderr));
    }

    private static DiagnosticsSink sink(ByteArrayOutputStream stderr) {
        return new WriterDiagnosticsSink(new java.io.OutputStreamWriter(stderr, UTF_8));
    }

    /** The warnings-channel router the composition injects, opening this command's channel. */
    private SearchPresenterBase.WarningsRouter warnings() {
        return (mode, diagnostics, results, quiet) ->
                new ModeAwareWarnings(mode, "answers", diagnostics, results, jsonl, quiet);
    }

    private static OutputRequest output(OutputMode mode, boolean verbose, boolean quiet) {
        return new OutputRequest(true, mode, false, verbose, quiet);
    }

    private static AnswersRequest request() {
        return AnswersRequest.builder(QUESTION).stream(true).build();
    }

    private static AnswerStreamEvent.Tagged tagged(String tag, String payload) {
        return new AnswerStreamEvent.Tagged(tag, payload);
    }

    /** Delivers a fixed event script synchronously under the first request, then its terminal. */
    private static final class ScriptedStream implements Flow.Publisher<AnswerStreamEvent> {

        private final List<AnswerStreamEvent> events;
        volatile FakeExchange exchange;
        private Runnable beforeTerminal = () -> {};
        private Throwable terminalFailure;

        ScriptedStream(List<AnswerStreamEvent> events) {
            this.events = List.copyOf(events);
        }

        void endWith(Throwable failure) {
            terminalFailure = failure;
        }

        void latchThenEnd(CancellationContext.Cause cause) {
            beforeTerminal = () -> exchange.cancellation.latch(cause);
            terminalFailure = new RuntimeException(cause.name());
        }

        @Override
        public void subscribe(Flow.Subscriber<? super AnswerStreamEvent> subscriber) {
            AtomicBoolean deliveredOnce = new AtomicBoolean();
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    if (n <= 0 || !deliveredOnce.compareAndSet(false, true)) {
                        return;
                    }
                    try {
                        for (AnswerStreamEvent event : events) {
                            subscriber.onNext(event);
                        }
                        beforeTerminal.run();
                        if (terminalFailure != null) {
                            subscriber.onError(terminalFailure);
                        } else {
                            subscriber.onComplete();
                        }
                    } catch (RuntimeException subscriberBroke) {
                        // a subscriber that throws is cancelled: delivery stops and the
                        // harness owns the terminal, exactly as the real processor behaves
                    }
                }

                @Override
                public void cancel() {}
            });
        }
    }

    /** Delivers fixed byte chunks synchronously under the first request, then completes. */
    private static final class ScriptedBytes implements Flow.Publisher<byte[]> {

        private final List<byte[]> chunks;

        ScriptedBytes(List<byte[]> chunks) {
            this.chunks = List.copyOf(chunks);
        }

        @Override
        public void subscribe(Flow.Subscriber<? super byte[]> subscriber) {
            AtomicBoolean deliveredOnce = new AtomicBoolean();
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    if (n <= 0 || !deliveredOnce.compareAndSet(false, true)) {
                        return;
                    }
                    for (byte[] chunk : chunks) {
                        subscriber.onNext(chunk.clone());
                    }
                    subscriber.onComplete();
                }

                @Override
                public void cancel() {}
            });
        }
    }

    /** The open exchange double: two scripted representations and one live latch. */
    private static final class FakeExchange implements AnswersStreamExchange {

        private final ScriptedBytes rawChunks;
        private final ScriptedStream semantic;
        final CancellationContext cancellation = new CancellationContext();
        boolean closed;

        FakeExchange(ScriptedBytes rawChunks, ScriptedStream semantic) {
            this.rawChunks = Objects.requireNonNull(rawChunks);
            this.semantic = Objects.requireNonNull(semantic);
            semantic.exchange = this;
        }

        @Override
        public RequestMeta openMeta() {
            return new RequestMeta("req-stream-1", 200, "2026-08-30", List.of(), null);
        }

        @Override
        public Flow.Publisher<byte[]> decodedRawFrames() {
            return rawChunks;
        }

        @Override
        public Flow.Publisher<AnswerStreamEvent> semanticFrames() {
            return semantic;
        }

        @Override
        public CancellationContext cancellation() {
            return cancellation;
        }

        @Override
        public Instant lastTransportActivity() {
            return Instant.parse("2026-09-01T00:00:02Z");
        }

        @Override
        public Instant lastSemanticProgress() {
            return Instant.parse("2026-09-01T00:00:01Z");
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** Captures every written document; can stage a broken pipe from a chosen write on. */
    private static final class RecordingWriter implements ResultWriter {

        private final List<byte[]> documents = new ArrayList<>();
        private int failFrom = Integer.MAX_VALUE;
        private String failureMessage = "Broken pipe";

        void failFromWrite(int oneBased) {
            failFrom = oneBased;
        }

        void failFromWriteWith(int oneBased, String failure) {
            failFrom = oneBased;
            failureMessage = failure;
        }

        int writeCount() {
            return documents.size();
        }

        byte[] documentAt(int index) {
            return documents.get(index);
        }

        String written() {
            StringBuilder all = new StringBuilder();
            for (byte[] document : documents) {
                all.append(new String(document, UTF_8));
            }
            return all.toString();
        }

        @Override
        public void write(byte[] document) {
            Objects.requireNonNull(document);
            if (documents.size() + 1 >= failFrom) {
                throw new UncheckedIOException(new IOException(failureMessage));
            }
            documents.add(document.clone());
        }
    }
}

package io.amscotti.bravesearch.adapter.cli.presentation.rich;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.amscotti.bravesearch.adapter.cli.presentation.RichPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.RawCodec;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.output.OutputMode;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.request.RichRequest;
import io.amscotti.bravesearch.domain.result.RichResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;

/**
 * The rich renderer's own shapes over the fixture family: the human document renders a
 * per-vertical section per block — the known verticals as numbered listings with their
 * usable title, url, and description lines and the provider attribution line, every
 * other vertical as the one-line name-and-count note — the machine projection stays
 * generic (vertical names and item counts only, never item internals, because the
 * upstream response shape is undocumented), one JSONL result record rides per vertical
 * block, and the provider attribution that third-party rich data carries survives every
 * channel: rendered in the human document and kept byte-identical inside the envelope's
 * lossless {@code data.upstream}.
 */
final class RichPresenterImplTest {

    private static final ObjectMapper READER = new ObjectMapper();

    private final JsonMappers mappers = new JsonMappers();

    private final RichPresenterImpl presenter = new RichPresenterImpl(
            new EnvelopeCodec(mappers),
            new JsonlCodec(mappers),
            new RawCodec(),
            new RichProjectionExtractor(mappers),
            (mode, diagnostics, results, quiet) -> new io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings(
                    mode, RichPresenter.COMMAND, diagnostics, results, new JsonlCodec(mappers), quiet));

    @Test
    void humanDocumentRendersKnownVerticalsAsListingsAndUnknownOnesAsTheNameCountNote() {
        String human = renderHuman(success("full-results.json"));

        assertEquals(
                """
                Rich results for: cb-7f3a2b

                videos (2):
                 1  First Provider Video
                    https://videos.example.com/first
                    First provider video description.
                    source: Example Video Provider

                 2  Second Provider Video
                    https://videos.example.com/second
                    source: Example Video Provider

                images (2):
                 1  Provider Image
                    https://images.example.com/one
                    source: Example Image Provider

                 2  (no title)

                faqs (1):
                 1  What is a rich callback?
                    https://example.com/faq
                    A reference to real-time rich results of an earlier web search.
                    source: Example FAQ Provider

                future_widgets: 2 items (unrecognized vertical; the json envelope carries the lossless body)
                4 verticals, 7 items.
                """,
                human);
    }

    @Test
    void aBodyWithoutVerticalsRendersTheSingleNoRichResultsLine() {
        assertEquals("No rich results.\n", renderHuman(success("zero-results.json")));
    }

    @Test
    void theMachineProjectionEnumeratesVerticalNamesAndCountsWithoutItemInternals() throws Exception {
        JsonNode projection = projectionOf(success("full-results.json"));

        assertEquals(4, projection.path("vertical_count").asInt());
        assertEquals(7, projection.path("item_count").asInt());
        JsonNode verticals = projection.path("verticals");
        assertEquals(4, verticals.size());
        assertEquals("videos", verticals.get(0).path("vertical").asText());
        assertEquals(2, verticals.get(0).path("item_count").asInt());
        assertEquals("future_widgets", verticals.get(3).path("vertical").asText());
        assertEquals(2, verticals.get(3).path("item_count").asInt());
        assertFalse(
                verticals.get(0).has("title"),
                "the generic projection never models the internals of an undocumented response shape");
        assertFalse(verticals.get(0).has("items"));
    }

    @Test
    void providerAttributionSurvivesHumanRenderingAndStaysLosslessInMachineOutput() throws Exception {
        String human = renderHuman(success("full-results.json"));
        String envelope = render(success("full-results.json"), machine(OutputMode.JSON, false));

        assertTrue(
                human.lines().anyMatch(line -> line.equals("    source: Example FAQ Provider")),
                "the human document renders the third-party provider attribution line");
        JsonNode upstream = READER.readTree(envelope).path("data").path("upstream");
        assertEquals(
                "Example Video Provider",
                upstream.path("videos").get(0).path("source").asText(),
                "machine output keeps the attribution member losslessly inside data.upstream");
        assertTrue(
                envelope.contains("0.1000000000000000000001"),
                "the spot decimal must survive rendering byte-for-byte");
    }

    @Test
    void jsonlEmitsOneResultRecordPerVerticalBlockThenTheSummary() throws Exception {
        String stream = render(success("full-results.json"), machine(OutputMode.JSONL, false));

        String[] lines = stream.split("\n", -1);
        assertEquals(6, lines.length, "four vertical records, one summary, one terminator");
        JsonNode first = READER.readTree(lines[0]);
        assertEquals("result", first.path("type").asText());
        assertEquals("rich", first.path("command").asText());
        assertEquals("videos", first.path("bucket").asText(), "each vertical block is its own record bucket");
        assertEquals(0, first.path("position").asInt());
        assertEquals(2, first.path("item_count").asInt());
        JsonNode unknown = READER.readTree(lines[3]);
        assertEquals("future_widgets", unknown.path("bucket").asText());
        assertEquals(3, unknown.path("position").asInt());
        JsonNode summary = READER.readTree(lines[4]);
        assertEquals("summary", summary.path("type").asText());
        assertEquals(4, summary.path("result_count").asInt());
        assertEquals(7, summary.path("item_count").asInt());
    }

    @Test
    void anEmptyBodyEmitsTheSummaryAlone() throws Exception {
        String stream = render(success("zero-results.json"), machine(OutputMode.JSONL, false));

        String[] lines = stream.split("\n", -1);
        assertEquals(2, lines.length, "no vertical blocks means no result records");
        JsonNode summary = READER.readTree(lines[0]);
        assertEquals("summary", summary.path("type").asText());
        assertEquals(0, summary.path("result_count").asInt());
        assertEquals(0, summary.path("item_count").asInt());
    }

    private static Outcome.Success<RichResult> success(String fixture) {
        return new Outcome.Success<>(new RichResult(
                200, new UpstreamPayload(fixture(fixture)), RateLimitSnapshot.empty(), null, "req-7f3a2b", "2026-08-30"));
    }

    private String renderHuman(Outcome<RichResult> outcome) {
        return render(outcome, new OutputRequest(false, OutputMode.HUMAN, false, false, false));
    }

    private JsonNode projectionOf(Outcome<RichResult> outcome) throws Exception {
        String envelope = render(outcome, machine(OutputMode.JSON, false));
        return READER.readTree(envelope).path("data").path("projection");
    }

    private String render(Outcome<RichResult> outcome, OutputRequest output) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exit = presenter.present(
                outcome,
                new RichRequest("cb-7f3a2b"),
                output,
                new io.amscotti.bravesearch.adapter.cli.presentation.OutputStreamResultWriter(stdout),
                diagnostic -> {});
        assertEquals(0, exit);
        return stdout.toString(UTF_8);
    }

    private static OutputRequest machine(OutputMode mode, boolean pretty) {
        return new OutputRequest(true, mode, pretty, false, false);
    }

    private static byte[] fixture(String name) {
        String resource = "/fixtures/brave/rich/" + name;
        try (InputStream bytes = RichPresenterImplTest.class.getResourceAsStream(resource)) {
            if (bytes == null) {
                throw new IllegalStateException("missing fixture " + resource);
            }
            return bytes.readAllBytes();
        } catch (IOException missing) {
            throw new UncheckedIOException(missing);
        }
    }
}

package io.amscotti.bravesearch.adapter.cli.presentation.spellcheck;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.amscotti.bravesearch.adapter.cli.presentation.OutputStreamResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.RawCodec;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.output.OutputMode;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.request.SpellcheckRequest;
import io.amscotti.bravesearch.domain.result.SpellcheckSearchResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;

/**
 * Rendering shapes of the spellcheck presenter: the human listing heads with {@code
 * Spellcheck results for: <query>} and numbers the corrections; the count line counts
 * corrections; an empty correction list renders the single {@code No corrections.} line,
 * because a clean query is the answer, not an absence of output. The JSONL stream is one
 * result record per correction — bucket {@code spellcheck}, the corrected query as
 * {@code query} — followed by one summary record.
 */
final class SpellcheckPresenterImplTest {

    private final JsonMappers mappers = new JsonMappers();

    private final SpellcheckPresenterImpl presenter = new SpellcheckPresenterImpl(
            new EnvelopeCodec(mappers),
            new JsonlCodec(mappers),
            new RawCodec(),
            new SpellcheckProjectionExtractor(mappers),
            (mode, diagnostics, results, quiet) ->
                    new io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings(
                            mode, "spellcheck", diagnostics, results, new JsonlCodec(mappers), quiet));

    @Test
    void humanModeNumbersTheCorrections() {
        assertEquals(
                "Spellcheck results for: three word query\n"
                        + "\n"
                        + " 1  three word corrected query\n"
                        + "1 correction.\n",
                render(success(), humanMode()));
    }

    @Test
    void anEmptyCorrectionListRendersTheSingleNoCorrectionsLine() {
        assertEquals("No corrections.\n", render(emptySuccess(), humanMode()));
    }

    @Test
    void jsonlModeWritesOneRecordPerCorrectionThenTheSummary() {
        String jsonl = render(success(), machine(OutputMode.JSONL));

        String[] lines = jsonl.split("\n", -1);
        assertEquals(3, lines.length, "one result record, one summary, one terminator");
        assertEquals(
                "{\"schema_version\":\"1\",\"type\":\"result\",\"command\":\"spellcheck\",\"position\":0,"
                        + "\"bucket\":\"spellcheck\",\"query\":\"three word corrected query\"}",
                lines[0]);
        assertEquals(
                "{\"schema_version\":\"1\",\"type\":\"summary\",\"command\":\"spellcheck\",\"result_count\":1,"
                        + "\"page\":1,\"upstream_offset\":0,\"http_status\":200,\"request_id\":\"req-7f3a2b\","
                        + "\"api_version\":\"2024-08-01\"}",
                lines[1]);
    }

    private static OutputRequest humanMode() {
        return new OutputRequest(false, OutputMode.HUMAN, false, false, false);
    }

    private static OutputRequest machine(OutputMode mode) {
        return new OutputRequest(true, mode, false, false, false);
    }

    private String render(Outcome<SpellcheckSearchResult> outcome, OutputRequest output) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        presenter.present(
                outcome,
                SpellcheckRequest.builder("three word query").build(),
                output,
                new OutputStreamResultWriter(stdout),
                diagnostic -> {});
        return stdout.toString(UTF_8);
    }

    private static Outcome.Success<SpellcheckSearchResult> success() {
        return new Outcome.Success<>(
                new SpellcheckSearchResult(
                        200,
                        new UpstreamPayload(fixture("full-results.json")),
                        RateLimitSnapshot.empty(),
                        null,
                        "req-7f3a2b",
                        "2024-08-01"));
    }

    private static Outcome.Success<SpellcheckSearchResult> emptySuccess() {
        return new Outcome.Success<>(
                new SpellcheckSearchResult(
                        200,
                        new UpstreamPayload(fixture("zero-results.json")),
                        RateLimitSnapshot.empty(),
                        null,
                        null,
                        null));
    }

    private static byte[] fixture(String name) {
        String resource = "/fixtures/brave/spellcheck/" + name;
        try (InputStream bytes = SpellcheckPresenterImplTest.class.getResourceAsStream(resource)) {
            if (bytes == null) {
                throw new IllegalStateException("missing fixture " + resource);
            }
            return bytes.readAllBytes();
        } catch (IOException missing) {
            throw new UncheckedIOException(missing);
        }
    }
}

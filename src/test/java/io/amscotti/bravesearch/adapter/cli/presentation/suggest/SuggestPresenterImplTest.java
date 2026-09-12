package io.amscotti.bravesearch.adapter.cli.presentation.suggest;

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
import io.amscotti.bravesearch.domain.request.SuggestRequest;
import io.amscotti.bravesearch.domain.result.SuggestSearchResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;

/**
 * Rendering shapes of the suggest presenter: the human listing heads with {@code
 * Suggestions for: <query>} and numbers the completions, with the enriched rich members
 * as indented lines; the count line counts suggestions; an empty suggestion list renders
 * the single {@code No suggestions.} line. The JSON projection counts the suggestions on
 * the constant first page of a non-paginated endpoint, and the JSONL stream is one
 * result record per suggestion — bucket {@code suggest}, the completion as {@code
 * query}, the kind as {@code suggestion_type} (the frame already owns the record's
 * {@code type} member) — followed by one summary record.
 */
final class SuggestPresenterImplTest {

    private final JsonMappers mappers = new JsonMappers();

    private final SuggestPresenterImpl presenter = new SuggestPresenterImpl(
            new EnvelopeCodec(mappers),
            new JsonlCodec(mappers),
            new RawCodec(),
            new SuggestProjectionExtractor(mappers),
            (mode, diagnostics, results, quiet) ->
                    new io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings(
                            mode, "suggest", diagnostics, results, new JsonlCodec(mappers), quiet));

    @Test
    void humanModeNumbersTheCompletionsWithTheRichMembersIndented() {
        String human = render(success(), humanMode());

        assertEquals(
                "Suggestions for: three word query\n"
                        + "\n"
                        + " 1  three word query one\n"
                        + "\n"
                        + " 2  three word query two\n"
                        + "    Enriched Title\n"
                        + "    Enriched description text.\n"
                        + "    https://images.example.com/enriched.png\n"
                        + "2 suggestions.\n",
                human);
    }

    @Test
    void anEmptySuggestionListRendersTheSingleNoSuggestionsLine() {
        assertEquals("No suggestions.\n", render(emptySuccess(), humanMode()));
    }

    @Test
    void jsonlModeWritesOneRecordPerSuggestionThenTheSummary() {
        String jsonl = render(success(), machine(OutputMode.JSONL));

        String[] lines = jsonl.split("\n", -1);
        assertEquals(4, lines.length, "two result records, one summary, one terminator");
        assertEquals(
                "{\"schema_version\":\"1\",\"type\":\"result\",\"command\":\"suggest\",\"position\":0,"
                        + "\"bucket\":\"suggest\",\"query\":\"three word query one\",\"suggestion_type\":\"query\"}",
                lines[0]);
        assertEquals(
                "{\"schema_version\":\"1\",\"type\":\"result\",\"command\":\"suggest\",\"position\":1,"
                        + "\"bucket\":\"suggest\",\"query\":\"three word query two\",\"suggestion_type\":\"entity\","
                        + "\"title\":\"Enriched Title\",\"description\":\"Enriched description text.\","
                        + "\"img\":\"https://images.example.com/enriched.png\"}",
                lines[1]);
        assertEquals(
                "{\"schema_version\":\"1\",\"type\":\"summary\",\"command\":\"suggest\",\"result_count\":2,"
                        + "\"page\":1,\"upstream_offset\":0,\"http_status\":200,\"request_id\":\"req-7f3a2b\","
                        + "\"api_version\":\"2024-08-01\"}",
                lines[2]);
    }

    private static OutputRequest humanMode() {
        return new OutputRequest(false, OutputMode.HUMAN, false, false, false);
    }

    private static OutputRequest machine(OutputMode mode) {
        return new OutputRequest(true, mode, false, false, false);
    }

    private String render(Outcome<SuggestSearchResult> outcome, OutputRequest output) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        presenter.present(
                outcome,
                SuggestRequest.builder("three word query").build(),
                output,
                new OutputStreamResultWriter(stdout),
                diagnostic -> {});
        return stdout.toString(UTF_8);
    }

    private static Outcome.Success<SuggestSearchResult> success() {
        return new Outcome.Success<>(
                new SuggestSearchResult(
                        200,
                        new UpstreamPayload(fixture("full-results.json")),
                        RateLimitSnapshot.empty(),
                        null,
                        "req-7f3a2b",
                        "2024-08-01"));
    }

    private static Outcome.Success<SuggestSearchResult> emptySuccess() {
        return new Outcome.Success<>(
                new SuggestSearchResult(
                        200,
                        new UpstreamPayload(fixture("zero-results.json")),
                        RateLimitSnapshot.empty(),
                        null,
                        null,
                        null));
    }

    private static byte[] fixture(String name) {
        String resource = "/fixtures/brave/suggest/" + name;
        try (InputStream bytes = SuggestPresenterImplTest.class.getResourceAsStream(resource)) {
            if (bytes == null) {
                throw new IllegalStateException("missing fixture " + resource);
            }
            return bytes.readAllBytes();
        } catch (IOException missing) {
            throw new UncheckedIOException(missing);
        }
    }
}

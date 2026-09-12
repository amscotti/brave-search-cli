package io.amscotti.bravesearch.adapter.cli.presentation.answers;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.amscotti.bravesearch.adapter.cli.presentation.AnswersPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.OutputStreamResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.RawCodec;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.metadata.Usage;
import io.amscotti.bravesearch.domain.output.OutputMode;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.request.AnswersRequest;
import io.amscotti.bravesearch.domain.result.AnswersResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The blocking answers renderer's own shapes over the fixture family: the human document
 * renders the answer text under one heading with the citations section and the trailing
 * usage-and-cost line — each exactly when the response carried it — the machine projection
 * carries the answer, the citation count, and the citations with their usable members
 * while the envelope's {@code data.upstream} keeps the lossless document, and raw writes
 * the decoded body bytes exactly.
 */
final class AnswersPresenterImplTest {

    private static final ObjectMapper READER = new ObjectMapper();

    private static final Usage FIXTURE_USAGE =
            new Usage(1L, 2L, 900L, 120L, null, null, null, null, new BigDecimal("0.0042"), Map.of(), java.util.List.of());

    private final JsonMappers mappers = new JsonMappers();

    private final AnswersPresenterImpl presenter = new AnswersPresenterImpl(
            new EnvelopeCodec(mappers),
            new JsonlCodec(mappers),
            new RawCodec(),
            new AnswersProjectionExtractor(mappers),
            (mode, diagnostics, results, quiet) -> new io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings(
                    mode, AnswersPresenter.COMMAND, diagnostics, results, new JsonlCodec(mappers), quiet));

    @Test
    void humanDocumentRendersTheAnswerThenTheUsageLineTheResponseEarned() {
        String human = renderHuman(success("blocking-answer.json"));

        assertEquals(
                """
                Answer for: what is the brave search api
                Brave Search is an independent index with its own crawler.

                Usage: requests 1, queries 2, tokens 900 in / 120 out, total cost 0.0042
                """,
                human);
    }

    @Test
    void aResponseWithoutUsageMetadataRendersNoUsageLine() {
        String human = renderHuman(
                new Outcome.Success<>(new AnswersResult(
                        200, new UpstreamPayload(fixture("blocking-answer.json")), RateLimitSnapshot.empty(), null, null, null)));

        assertEquals(
                """
                Answer for: what is the brave search api
                Brave Search is an independent index with its own crawler.
                """,
                human);
    }

    @Test
    void citationsRenderAsTheNumberedSectionBetweenAnswerAndUsage() {
        String human = renderHuman(success("blocking-answer-citations.json"));

        assertEquals(
                """
                Answer for: what is the brave search api
                Grounded answer with citations.

                Citations:
                1. https://search.brave.com/
                   an independent index
                2. https://brave.com

                Usage: requests 1, queries 2, tokens 900 in / 120 out, total cost 0.0042
                """,
                human);
    }

    @Test
    void aResponseWithoutAnswerTextRendersTheNoAnswerLine() {
        String human = renderHuman(
                new Outcome.Success<>(new AnswersResult(
                        200, new UpstreamPayload("{}".getBytes(UTF_8)), RateLimitSnapshot.empty(), null, null, null)));

        assertEquals(
                """
                Answer for: what is the brave search api
                No answer.
                """,
                human);
    }

    @Test
    void usageLineSeparatesExactlyTheObservedMembers() {
        OutputRequest plain = new OutputRequest(false, OutputMode.HUMAN, false, false, false);

        assertEquals(
                "Usage: queries 2",
                AnswersPresenterImpl.usageLine(
                        new Usage(null, 2L, null, null, null, null, null, null, null, Map.of(), java.util.List.of()),
                        plain),
                "the first observed member carries no separator");
        assertEquals(
                "Usage: tokens 7 out",
                AnswersPresenterImpl.usageLine(
                        new Usage(null, null, null, 7L, null, null, null, null, null, Map.of(), java.util.List.of()),
                        plain));
        assertEquals(
                "Usage: total cost 0.50",
                AnswersPresenterImpl.usageLine(
                        new Usage(
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                new BigDecimal("0.50"),
                                Map.of(),
                                java.util.List.of()),
                        plain));
        assertEquals(
                "Usage: queries 2, tokens 7 out",
                AnswersPresenterImpl.usageLine(
                        new Usage(null, 2L, null, 7L, null, null, null, null, null, Map.of(), java.util.List.of()),
                        plain),
                "the separator joins each later observed member");
    }

    @Test
    void theProjectionCarriesTheAnswerTheCitationCountAndEachCitationsMembers() throws Exception {
        String envelope = render(success("blocking-answer-citations.json"), machine(OutputMode.JSON, false));

        JsonNode projection = READER.readTree(envelope).path("data").path("projection");
        assertEquals("Grounded answer with citations.", projection.path("answer").asText());
        assertEquals(2, projection.path("citation_count").asInt());
        JsonNode first = projection.path("citations").get(0);
        assertEquals(1, first.path("number").asInt());
        assertEquals("https://search.brave.com/", first.path("url").asText());
        assertEquals("an independent index", first.path("snippet").asText());
        assertEquals(0, first.path("start_index").asInt());
        assertEquals(12, first.path("end_index").asInt());
        JsonNode upstream = READER.readTree(envelope).path("data").path("upstream");
        assertEquals("ans_02", upstream.path("id").asText(), "the lossless document rides data.upstream");
        assertEquals(1020, upstream.path("usage").path("total_tokens").asInt());
        JsonNode meta = READER.readTree(envelope).path("meta");
        assertEquals("req-answers-17", meta.path("request_id").asText());
        assertEquals(1L, meta.path("usage").path("requests").asLong());
        assertEquals("0.0042", meta.path("usage").path("total_cost").asText());
    }

    @Test
    void aCitationFreeAnswerOmitsEveryCitationMember() throws Exception {
        String envelope = render(success("blocking-answer.json"), machine(OutputMode.JSON, false));

        JsonNode projection = READER.readTree(envelope).path("data").path("projection");
        assertEquals(0, projection.path("citation_count").asInt());
        assertTrue(projection.path("citations").isEmpty(), "no citations means the empty array, never members invented");
        assertFalse(projection.has("citations_uncertain"));
    }

    @Test
    void anAnswerFreeResponseOmitsTheAnswerMemberOfTheProjection() throws Exception {
        String envelope = render(
                new Outcome.Success<>(new AnswersResult(
                        200, new UpstreamPayload("{}".getBytes(UTF_8)), RateLimitSnapshot.empty(), null, null, null)),
                machine(OutputMode.JSON, false));

        JsonNode projection = READER.readTree(envelope).path("data").path("projection");
        assertFalse(projection.has("answer"), "an absent answer is omitted, never emitted as null");
        assertEquals(0, projection.path("citation_count").asInt());
    }

    @Test
    void rawOutputIsTheDecodedBodyBytesExactly() {
        String raw = render(success("blocking-answer.json"), machine(OutputMode.RAW, false));

        assertEquals(new String(fixture("blocking-answer.json"), UTF_8), raw);
    }

    private static Outcome.Success<AnswersResult> success(String fixture) {
        return new Outcome.Success<>(new AnswersResult(
                200,
                new UpstreamPayload(fixture(fixture)),
                RateLimitSnapshot.empty(),
                FIXTURE_USAGE,
                "req-answers-17",
                "2026-08-30"));
    }

    private String renderHuman(Outcome<AnswersResult> outcome) {
        return render(outcome, new OutputRequest(false, OutputMode.HUMAN, false, false, false));
    }

    private String render(Outcome<AnswersResult> outcome, OutputRequest output) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exit = presenter.present(
                outcome,
                AnswersRequest.builder("what is the brave search api").stream(false).build(),
                output,
                new OutputStreamResultWriter(stdout),
                diagnostic -> {});
        assertEquals(0, exit);
        return stdout.toString(UTF_8);
    }

    private static OutputRequest machine(OutputMode mode, boolean pretty) {
        return new OutputRequest(true, mode, pretty, false, false);
    }

    private static byte[] fixture(String name) {
        String resource = "/fixtures/brave/answers/" + name;
        try (InputStream bytes = AnswersPresenterImplTest.class.getResourceAsStream(resource)) {
            if (bytes == null) {
                throw new IllegalStateException("missing fixture " + resource);
            }
            return bytes.readAllBytes();
        } catch (IOException missing) {
            throw new UncheckedIOException(missing);
        }
    }
}

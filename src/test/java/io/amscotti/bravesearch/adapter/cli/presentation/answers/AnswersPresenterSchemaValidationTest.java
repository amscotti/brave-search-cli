package io.amscotti.bravesearch.adapter.cli.presentation.answers;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.amscotti.bravesearch.adapter.cli.presentation.AnswersPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.OutputStreamResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.RawCodec;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.error.UpstreamError;
import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.metadata.Usage;
import io.amscotti.bravesearch.domain.output.OutputMode;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.request.AnswersRequest;
import io.amscotti.bravesearch.domain.result.AnswersResult;
import io.amscotti.bravesearch.testsupport.SchemaCatalog;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Every machine document the blocking answers renderer emits satisfies the published v1
 * schemas: the success and failure envelopes against their envelope schemas, and the
 * blocking projection — answer, citation count, citations — against the answers-blocking
 * schemas.
 */
final class AnswersPresenterSchemaValidationTest {

    private static final Usage FIXTURE_USAGE =
            new Usage(1L, 2L, 900L, 120L, null, null, null, null, new BigDecimal("0.0042"), Map.of(), java.util.List.of());

    private final JsonMappers mappers = new JsonMappers();

    private final SchemaCatalog schemas = new SchemaCatalog();

    private final AnswersPresenterImpl presenter = new AnswersPresenterImpl(
            new EnvelopeCodec(mappers),
            new JsonlCodec(mappers),
            new RawCodec(),
            new AnswersProjectionExtractor(mappers),
            (mode, diagnostics, results, quiet) -> new io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings(
                    mode, AnswersPresenter.COMMAND, diagnostics, results, new JsonlCodec(mappers), quiet));

    @Test
    void everySuccessDocumentSatisfiesItsSchema() throws Exception {
        assertValid("envelope-success.schema.json", render(success("blocking-answer-citations.json"), machine(OutputMode.JSON, false)));
        assertValid("envelope-success.schema.json", render(success("blocking-answer-citations.json"), machine(OutputMode.JSON, true)));

        String envelope = render(success("blocking-answer-citations.json"), machine(OutputMode.JSON, false));
        JsonNode projection = new ObjectMapper().readTree(envelope).path("data").path("projection");
        assertValid("answers-blocking.schema.json", projection.toString());
        for (JsonNode citation : projection.path("citations")) {
            assertValid("answers-blocking-citation.schema.json", citation.toString());
        }

        assertValid(
                "answers-blocking.schema.json",
                new ObjectMapper()
                        .readTree(render(
                                        new Outcome.Success<>(new AnswersResult(
                                                200,
                                                new UpstreamPayload(fixture("blocking-answer.json")),
                                                RateLimitSnapshot.empty(),
                                                null,
                                                null,
                                                null)),
                                        machine(OutputMode.JSON, false)))
                        .path("data")
                        .path("projection")
                        .toString());
    }

    @Test
    void everyFailureDocumentSatisfiesItsSchema() {
        assertValid("envelope-error.schema.json", render(authenticationFailure(), machine(OutputMode.JSON, false)));
        assertValid("envelope-error.schema.json", render(transportFailure(), machine(OutputMode.JSON, false)));
    }

    private void assertValid(String schema, String document) {
        assertTrue(
                schemas.validateText(schema, document).isEmpty(),
                () -> "document must satisfy " + schema + ": " + document);
    }

    private String render(Outcome<AnswersResult> outcome, OutputRequest output) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exit = presenter.present(
                outcome,
                AnswersRequest.builder("what is the brave search api").stream(false).build(),
                output,
                new OutputStreamResultWriter(stdout),
                diagnostic -> {});
        assertTrue(exit == 0 || exit == 4 || exit == 6, "a known exit status: " + exit);
        return stdout.toString(UTF_8);
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

    private static Outcome.Failure<AnswersResult> authenticationFailure() {
        return new Outcome.Failure<>(
                FailureKind.AUTHENTICATION,
                "upstream exchange failed with status 401",
                new UpstreamError("unauthorized", null, new UpstreamPayload("{\"error\":{}}".getBytes(UTF_8))),
                null,
                401);
    }

    private static Outcome.Failure<AnswersResult> transportFailure() {
        return new Outcome.Failure<>(FailureKind.TRANSPORT, "connect timed out");
    }

    private static OutputRequest machine(OutputMode mode, boolean pretty) {
        return new OutputRequest(true, mode, pretty, false, false);
    }

    private static byte[] fixture(String name) {
        String resource = "/fixtures/brave/answers/" + name;
        try (InputStream bytes = AnswersPresenterSchemaValidationTest.class.getResourceAsStream(resource)) {
            if (bytes == null) {
                throw new IllegalStateException("missing fixture " + resource);
            }
            return bytes.readAllBytes();
        } catch (IOException missing) {
            throw new UncheckedIOException(missing);
        }
    }
}

package io.amscotti.bravesearch.adapter.cli.presentation.videos;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.presentation.OutputStreamResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.VideoSearchPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.RawCodec;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.error.UpstreamError;
import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.output.OutputMode;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.request.VideoSearchRequest;
import io.amscotti.bravesearch.domain.result.VideoSearchResult;
import io.amscotti.bravesearch.testsupport.SchemaCatalog;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;

/**
 * Every machine document and line the videos presenter emits satisfies the published v1
 * schemas: the success and failure envelopes against their envelope schemas, and each
 * JSONL line — warning, result, summary, error — against the jsonl record schema with its
 * pinned videos payloads.
 */
final class VideoPresenterSchemaValidationTest {

    private final JsonMappers mappers = new JsonMappers();

    private final SchemaCatalog schemas = new SchemaCatalog();

    private final VideoSearchPresenterImpl presenter = new VideoSearchPresenterImpl(
            new EnvelopeCodec(mappers),
            new JsonlCodec(mappers),
            new RawCodec(),
            new VideoProjectionExtractor(mappers),
            (mode, diagnostics, results, quiet) -> new io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings(
                    mode, VideoSearchPresenter.COMMAND, diagnostics, results, new JsonlCodec(mappers), quiet));

    @Test
    void everySuccessDocumentOfEveryModeSatisfiesItsSchema() {
        assertValid("envelope-success.schema.json", render(success(), machine(OutputMode.JSON, false)));
        assertValid("envelope-success.schema.json", render(success(), machine(OutputMode.JSON, true)));
        for (String line : render(success(), machine(OutputMode.JSONL, false)).split("\n", -1)) {
            if (!line.isEmpty()) {
                assertValid("jsonl-record.schema.json", line);
            }
        }
    }

    @Test
    void everyFailureDocumentOfEveryModeSatisfiesItsSchema() {
        assertValid("envelope-error.schema.json", render(authenticationFailure(), machine(OutputMode.JSON, false)));
        assertValid("envelope-error.schema.json", render(authenticationFailure(), machine(OutputMode.JSON, true)));
        assertValid("envelope-error.schema.json", render(transportFailure(), machine(OutputMode.JSON, false)));
        assertValid(
                "jsonl-record.schema.json",
                lineOf(render(authenticationFailure(), machine(OutputMode.JSONL, false))));
        assertValid("jsonl-record.schema.json", lineOf(render(transportFailure(), machine(OutputMode.JSONL, false))));
    }

    @Test
    void zeroResultDocumentsSatisfyTheirSchemas() {
        VideoSearchResult empty = new VideoSearchResult(
                200, new UpstreamPayload(fixture("zero-results.json")), RateLimitSnapshot.empty(), null, null, null);
        assertValid("envelope-success.schema.json", render(new Outcome.Success<>(empty), machine(OutputMode.JSON, false)));
        assertValid(
                "jsonl-record.schema.json",
                lineOf(render(new Outcome.Success<>(empty), machine(OutputMode.JSONL, false))));
    }

    private static String lineOf(String jsonlStream) {
        return jsonlStream.substring(0, jsonlStream.indexOf('\n'));
    }

    private void assertValid(String schema, String document) {
        assertTrue(
                schemas.validateText(schema, document).isEmpty(),
                () -> "document must satisfy " + schema + ": " + document);
    }

    private String render(Outcome<VideoSearchResult> outcome, OutputRequest output) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exit = presenter.present(
                outcome,
                VideoSearchRequest.builder("three word query").build(),
                output,
                new OutputStreamResultWriter(stdout),
                diagnostic -> {});
        assertTrue(exit == 0 || exit == 4 || exit == 6, "a known exit status: " + exit);
        return stdout.toString(UTF_8);
    }

    private static Outcome.Success<VideoSearchResult> success() {
        return new Outcome.Success<>(
                new VideoSearchResult(
                        200,
                        new UpstreamPayload(fixture("full-results.json")),
                        RateLimitSnapshot.empty(),
                        null,
                        "req-7f3a2b",
                        "2024-08-01"));
    }

    private static Outcome.Failure<VideoSearchResult> authenticationFailure() {
        return new Outcome.Failure<>(
                FailureKind.AUTHENTICATION,
                "upstream exchange failed with status 401",
                new UpstreamError("unauthorized", null, new UpstreamPayload(fixture("unauthorized-error.json"))),
                null,
                401);
    }

    private static Outcome.Failure<VideoSearchResult> transportFailure() {
        return new Outcome.Failure<>(FailureKind.TRANSPORT, "connect timed out");
    }

    private static OutputRequest machine(OutputMode mode, boolean pretty) {
        return new OutputRequest(true, mode, pretty, false, false);
    }

    private static byte[] fixture(String name) {
        String resource = "/fixtures/brave/videos/" + name;
        try (java.io.InputStream bytes = VideoPresenterSchemaValidationTest.class.getResourceAsStream(resource)) {
            if (bytes == null) {
                throw new IllegalStateException("missing fixture " + resource);
            }
            return bytes.readAllBytes();
        } catch (IOException missing) {
            throw new UncheckedIOException(missing);
        }
    }
}

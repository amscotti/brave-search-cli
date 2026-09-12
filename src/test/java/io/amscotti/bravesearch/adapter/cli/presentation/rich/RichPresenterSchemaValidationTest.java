package io.amscotti.bravesearch.adapter.cli.presentation.rich;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.presentation.RichPresenter;
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
import io.amscotti.bravesearch.domain.request.RichRequest;
import io.amscotti.bravesearch.domain.result.RichResult;
import io.amscotti.bravesearch.testsupport.SchemaCatalog;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;

/**
 * Every machine document and line the rich presenter emits satisfies the published v1
 * schemas: the success and failure envelopes against their envelope schemas, and each
 * JSONL line against the jsonl record schema with its pinned rich payloads — one result
 * record per vertical block and the summary.
 */
final class RichPresenterSchemaValidationTest {

    private final JsonMappers mappers = new JsonMappers();

    private final SchemaCatalog schemas = new SchemaCatalog();

    private final RichPresenterImpl presenter = new RichPresenterImpl(
            new EnvelopeCodec(mappers),
            new JsonlCodec(mappers),
            new RawCodec(),
            new RichProjectionExtractor(mappers),
            (mode, diagnostics, results, quiet) -> new io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings(
                    mode, RichPresenter.COMMAND, diagnostics, results, new JsonlCodec(mappers), quiet));

    @Test
    void everySuccessDocumentOfEveryModeSatisfiesItsSchema() {
        assertValid("envelope-success.schema.json", render(success(), machine(OutputMode.JSON, false)));
        assertValid("envelope-success.schema.json", render(success(), machine(OutputMode.JSON, true)));
        for (String line : render(success(), machine(OutputMode.JSONL, false)).split("\n", -1)) {
            if (!line.isEmpty()) {
                assertValid("jsonl-record.schema.json", line);
            }
        }
        assertValid("rich-result.schema.json", lineOf(render(success(), machine(OutputMode.JSONL, false)), 0));
        assertValid("rich-summary.schema.json", lineOf(render(success(), machine(OutputMode.JSONL, false)), 4));
    }

    @Test
    void everyFailureDocumentOfEveryModeSatisfiesItsSchema() {
        assertValid("envelope-error.schema.json", render(authenticationFailure(), machine(OutputMode.JSON, false)));
        assertValid("envelope-error.schema.json", render(transportFailure(), machine(OutputMode.JSON, false)));
        assertValid(
                "jsonl-record.schema.json",
                lineOf(render(authenticationFailure(), machine(OutputMode.JSONL, false)), 0));
        assertValid("jsonl-record.schema.json", lineOf(render(transportFailure(), machine(OutputMode.JSONL, false)), 0));
    }

    @Test
    void zeroResultDocumentsSatisfyTheirSchemas() {
        RichResult empty =
                new RichResult(200, new UpstreamPayload(fixture("zero-results.json")), RateLimitSnapshot.empty(), null, null, null);
        assertValid("envelope-success.schema.json", render(new Outcome.Success<>(empty), machine(OutputMode.JSON, false)));
        assertValid(
                "rich-summary.schema.json", lineOf(render(new Outcome.Success<>(empty), machine(OutputMode.JSONL, false)), 0));
    }

    private static String lineOf(String jsonlStream, int index) {
        return jsonlStream.split("\n", -1)[index];
    }

    private void assertValid(String schema, String document) {
        assertTrue(
                schemas.validateText(schema, document).isEmpty(),
                () -> "document must satisfy " + schema + ": " + document);
    }

    private String render(Outcome<RichResult> outcome, OutputRequest output) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exit = presenter.present(
                outcome,
                new RichRequest("cb-7f3a2b"),
                output,
                new io.amscotti.bravesearch.adapter.cli.presentation.OutputStreamResultWriter(stdout),
                diagnostic -> {});
        assertTrue(exit == 0 || exit == 4 || exit == 6, "a known exit status: " + exit);
        return stdout.toString(UTF_8);
    }

    private static Outcome.Success<RichResult> success() {
        return new Outcome.Success<>(new RichResult(
                200,
                new UpstreamPayload(fixture("full-results.json")),
                RateLimitSnapshot.empty(),
                null,
                "req-7f3a2b",
                "2026-08-30"));
    }

    private static Outcome.Failure<RichResult> authenticationFailure() {
        return new Outcome.Failure<>(
                FailureKind.AUTHENTICATION,
                "upstream exchange failed with status 401",
                new UpstreamError("unauthorized", null, new UpstreamPayload(fixture("unauthorized-error.json"))),
                null,
                401);
    }

    private static Outcome.Failure<RichResult> transportFailure() {
        return new Outcome.Failure<>(FailureKind.TRANSPORT, "connect timed out");
    }

    private static OutputRequest machine(OutputMode mode, boolean pretty) {
        return new OutputRequest(true, mode, pretty, false, false);
    }

    private static byte[] fixture(String name) {
        String resource = "/fixtures/brave/rich/" + name;
        try (InputStream bytes = RichPresenterSchemaValidationTest.class.getResourceAsStream(resource)) {
            if (bytes == null) {
                throw new IllegalStateException("missing fixture " + resource);
            }
            return bytes.readAllBytes();
        } catch (IOException missing) {
            throw new UncheckedIOException(missing);
        }
    }
}

package io.amscotti.bravesearch.adapter.cli.presentation.context;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.presentation.ContextPresenter;
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
import io.amscotti.bravesearch.domain.output.OutputMode;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.request.ContextRequest;
import io.amscotti.bravesearch.domain.result.ContextResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Byte-exact renderings of one completed LLM context retrieval on every output channel:
 * the human document is the context passages verbatim under one heading plus the trailing
 * snippet and source count line, JSON mode writes one envelope whose projection carries the
 * two stable counts over the lossless upstream tree, JSONL mode writes exactly one result
 * record — the whole context as one logical result — followed by the summary, raw mode
 * passes the body bytes through, and every failure renders through the shared failure
 * matrix of the presenter skeleton. Pinned against the fixture bodies under {@code
 * fixtures/brave/context}.
 */
final class ContextPresenterImplTest {

    private static final ContextRequest REQUEST = ContextRequest.builder("three word query").build();

    private static final String HUMAN_GOLDEN = "Context for: three word query\n"
            + "\n"
            + "First context passage about the query subject.\n"
            + "\n"
            + "Second passage, déjà vu, with an emoji ☕.\n"
            + "2 snippets across 2 sources.\n";

    /** The two JSONL lines of the grounded fixture: one context record, then the summary. */
    private static final String JSONL_GOLDEN = "{\"schema_version\":\"1\",\"type\":\"result\",\"command\":\"context\","
            + "\"bucket\":\"context\",\"content\":\"First context passage about the query subject.\\n"
            + "Second passage, déjà vu, with an emoji ☕.\"}\n"
            + "{\"schema_version\":\"1\",\"type\":\"summary\",\"command\":\"context\","
            + "\"snippet_count\":2,\"source_count\":2,\"http_status\":200,"
            + "\"request_id\":\"req-7f3a2b\",\"api_version\":\"2024-08-01\"}\n";

    private final JsonMappers mappers = new JsonMappers();

    private final ContextPresenterImpl presenter = new ContextPresenterImpl(
            new EnvelopeCodec(mappers),
            new JsonlCodec(mappers),
            new RawCodec(),
            new ContextProjectionExtractor(mappers),
            (mode, diagnostics, results, quiet) -> new io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings(
                    mode, ContextPresenter.COMMAND, diagnostics, results, new JsonlCodec(mappers), quiet));

    @Test
    void humanModeRendersThePassagesVerbatimWithTheCountLine() {
        Rendered rendered = render(successOf(fixtureBody("grounded-context.json")), human(false), REQUEST);

        assertEquals(0, rendered.exit);
        assertEquals(HUMAN_GOLDEN, rendered.stdoutText());
        assertEquals("", rendered.stderr);
        assertFalse(rendered.stdoutText().contains("\r"), "human output uses LF endings only");
        assertFalse(rendered.stdoutText().contains("\u001b"), "human output carries no ANSI escapes");
    }

    @Test
    void humanModeKeepsMultiLinePassagesVerbatim() {
        String body = "{\"grounding\":{\"generic\":[\"first line\\nstill first passage\",\"second\"]},\"sources\":{\"https://a\":{}}}";
        Rendered rendered = render(successOf(body.getBytes(UTF_8)), human(false), REQUEST);

        assertEquals(
                "Context for: three word query\n"
                        + "\n"
                        + "first line\n"
                        + "still first passage\n"
                        + "\n"
                        + "second\n"
                        + "2 snippets across 1 source.\n",
                rendered.stdoutText());
    }

    @Test
    void humanModeWithoutUsablePassagesPrintsExactlyOneLine() {
        Rendered rendered = render(successOf(fixtureBody("zero-context.json")), human(false), REQUEST);

        assertEquals(0, rendered.exit);
        assertEquals("No context.\n", rendered.stdoutText());
    }

    @Test
    void jsonModeWritesOneEnvelopeWithTheCountsAndTheLosslessTree() {
        Rendered rendered = render(successOf(fixtureBody("grounded-context.json")), machine(OutputMode.JSON, false), REQUEST);

        assertEquals(0, rendered.exit);
        assertEquals("", rendered.stderr);
        JsonNode envelope = read(rendered.stdoutText());
        assertEquals("1", envelope.path("schema_version").asText());
        assertTrue(envelope.path("ok").asBoolean());
        assertEquals("context", envelope.path("command").asText());
        assertEquals(2, envelope.path("data").path("projection").path("snippet_count").asInt());
        assertEquals(2, envelope.path("data").path("projection").path("source_count").asInt());
        assertEquals(
                "0.1000000000000000000001",
                envelope.path("data")
                        .path("upstream")
                        .path("unknown_future_block")
                        .path("long")
                        .asText(),
                "the spot decimal survives the whole render at exact scale");
        assertTrue(rendered.stdoutText().contains("\"cost\":1.10"), "the trailing-zero cost stays byte-for-byte");
        assertEquals("req-7f3a2b", envelope.path("meta").path("request_id").asText());
        assertEquals(200, envelope.path("meta").path("http_status").asInt());
        assertTrue(rendered.stdoutText().indexOf('\n') == rendered.stdoutText().length() - 1, "exactly one LF terminates the envelope");
    }

    @Test
    void jsonlModeWritesOneContextResultRecordThenTheSummary() {
        Rendered rendered = render(successOf(fixtureBody("grounded-context.json")), machine(OutputMode.JSONL, false), REQUEST);

        assertEquals(0, rendered.exit);
        assertEquals(JSONL_GOLDEN, rendered.stdoutText());
        assertEquals("", rendered.stderr);
    }

    @Test
    void jsonlModeWithoutUsablePassagesEmitsTheSummaryAlone() {
        Rendered rendered = render(successOf(fixtureBody("zero-context.json")), machine(OutputMode.JSONL, false), REQUEST);

        assertEquals(
                "{\"schema_version\":\"1\",\"type\":\"summary\",\"command\":\"context\","
                        + "\"snippet_count\":0,\"source_count\":0,\"http_status\":200,"
                        + "\"request_id\":\"req-7f3a2b\",\"api_version\":\"2024-08-01\"}\n",
                rendered.stdoutText());
    }

    @Test
    void rawModePassesTheBodyBytesThroughUnchanged() {
        byte[] body = fixtureBody("grounded-context.json");

        Rendered rendered = render(successOf(body), machine(OutputMode.RAW, false), REQUEST);

        assertEquals(0, rendered.exit);
        assertArrayEquals(body, rendered.stdoutBytes);
        assertEquals("", rendered.stderr);
    }

    @Test
    void localRecallMembersNeverBreakExtraction() {
        Rendered rendered = render(successOf(fixtureBody("local-recall-context.json")), human(false), REQUEST);

        assertEquals(0, rendered.exit);
        assertTrue(rendered.stdoutText().contains("Nearby passage about the area.\n"), rendered.stdoutText());
        assertTrue(rendered.stdoutText().endsWith("1 snippet across 1 source.\n"));
    }

    @Test
    void anAuthenticationFailureRendersEachModeAndKeepsExitFour() {
        Outcome.Failure<ContextResult> failure = new Outcome.Failure<>(
                FailureKind.AUTHENTICATION,
                "upstream exchange failed with status 401",
                new UpstreamError("unauthorized", null, new UpstreamPayload(fixtureBody("unauthorized-error.json"))),
                null,
                401);

        Rendered human = render(failure, human(false), REQUEST);
        assertEquals(4, human.exit);
        assertEquals("", human.stdoutText());
        assertEquals("context: upstream exchange failed with status 401\n", human.stderr);

        Rendered json = render(failure, machine(OutputMode.JSON, false), REQUEST);
        assertEquals(4, json.exit);
        JsonNode envelope = read(json.stdoutText());
        assertFalse(envelope.path("ok").asBoolean());
        assertEquals("AUTHENTICATION_FAILED", envelope.path("error").path("code").asText());
        assertEquals("unauthorized", envelope.path("error").path("upstream_code").asText());
        assertEquals(401, envelope.path("meta").path("http_status").asInt());

        Rendered raw = render(failure, machine(OutputMode.RAW, false), REQUEST);
        assertEquals(4, raw.exit);
        assertArrayEquals(fixtureBody("unauthorized-error.json"), raw.stdoutBytes, "raw emits the upstream error body byte-exactly");
    }

    @Test
    void aBodyThatIsNotReadableJsonIsTheMalformedFailureInEveryParsingMode() {
        ContextResult broken = resultOf("not json at all".getBytes(UTF_8));

        Rendered json = render(new Outcome.Success<>(broken), machine(OutputMode.JSON, false), REQUEST);
        assertEquals(8, json.exit);
        assertEquals(read(json.stdoutText()).path("error").path("code").asText(), "MALFORMED_RESPONSE");

        Rendered humanRender = render(new Outcome.Success<>(broken), human(false), REQUEST);
        assertEquals(8, humanRender.exit);
        assertEquals("context: upstream success body is not readable JSON\n", humanRender.stderr);
    }

    private static JsonNode read(String document) {
        return new JsonMappers().upstreamReader().readTree(document.getBytes(UTF_8));
    }

    private static Outcome.Success<ContextResult> successOf(byte[] body) {
        return new Outcome.Success<>(resultOf(body));
    }

    private static ContextResult resultOf(byte[] body) {
        return new ContextResult(
                200,
                new UpstreamPayload(body),
                new RateLimitSnapshot(List.of(), List.of(), java.time.Instant.EPOCH),
                null,
                "req-7f3a2b",
                "2024-08-01");
    }

    private static OutputRequest human(boolean quiet) {
        return new OutputRequest(false, OutputMode.HUMAN, false, false, quiet);
    }

    private static OutputRequest machine(OutputMode mode, boolean pretty) {
        return new OutputRequest(true, mode, pretty, false, false);
    }

    private static byte[] fixtureBody(String name) {
        String resource = "/fixtures/brave/context/" + name;
        try (java.io.InputStream bytes = ContextPresenterImplTest.class.getResourceAsStream(resource)) {
            if (bytes == null) {
                throw new IllegalStateException("missing fixture " + resource);
            }
            return bytes.readAllBytes();
        } catch (IOException missing) {
            throw new UncheckedIOException(missing);
        }
    }

    private Rendered render(Outcome<ContextResult> outcome, OutputRequest output, ContextRequest request) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = presenter.present(
                outcome,
                request,
                output,
                new OutputStreamResultWriter(stdout),
                line -> stderr.writeBytes((line + "\n").getBytes(UTF_8)));
        return new Rendered(exit, stdout.toByteArray(), stderr.toString(UTF_8));
    }

    private static final class Rendered {
        final int exit;
        final byte[] stdoutBytes;
        final String stderr;

        Rendered(int exit, byte[] stdoutBytes, String stderr) {
            this.exit = exit;
            this.stdoutBytes = stdoutBytes;
            this.stderr = stderr;
        }

        String stdoutText() {
            return new String(stdoutBytes, UTF_8);
        }
    }
}

package io.amscotti.bravesearch.adapter.cli.presentation.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.presentation.OutputStreamResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.WebSearchPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.RawCodec;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import io.amscotti.bravesearch.domain.metadata.RateLimitWindow;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.metadata.Usage;
import io.amscotti.bravesearch.domain.output.OutputMode;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.request.WebSearchRequest;
import io.amscotti.bravesearch.domain.result.WebSearchResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Byte-exact renderings of one completed web search on every output channel: the concise
 * human listing, the single JSON envelope with its lossless upstream tree, the JSONL
 * result/summary pair set, raw body passthrough, and the failure documents of each mode —
 * each pinned against the fixture bodies under {@code fixtures/brave/web}.
 */
final class WebSearchPresenterImplTest {

    private static final WebSearchRequest FULL_CONTEXT = WebSearchRequest.builder("three word query").build();

    private static final WebSearchRequest ZERO_CONTEXT = WebSearchRequest.builder("nothing matches").build();

    private final JsonMappers mappers = new JsonMappers();

    private final WebSearchPresenterImpl presenter = new WebSearchPresenterImpl(
            new EnvelopeCodec(mappers),
            new JsonlCodec(mappers),
            new RawCodec(),
            new WebProjectionExtractor(mappers),
            (mode, diagnostics, results, quiet) -> new io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings(
                    mode, WebSearchPresenter.COMMAND, diagnostics, results, new JsonlCodec(mappers), quiet));

    @Test
    void humanModeListsNumberedResultsWithAHeadingAndACountLine() {
        Rendered rendered = render(successOf(fullResults()), human(false), FULL_CONTEXT);

        assertEquals(0, rendered.exit());
        assertEquals(
                "Web results for: three word query\n"
                        + "\n"
                        + " 1  First Title\n"
                        + "    https://example.com/first\n"
                        + "    First description text.\n"
                        + "\n"
                        + " 2  Second Title\n"
                        + "    https://example.com/second\n"
                        + "\n"
                        + " 3  (no title)\n"
                        + "    https://example.com/third\n"
                        + "    Third description.\n"
                        + "3 results.\n"
                        + "quota: 0 of 1 remaining (window resets in 1s)\n",
                rendered.stdoutText());
        assertEquals(List.of(), rendered.stderr());
    }

    @Test
    void aNearlyExhaustedWindowAppendsTheQuotaFooterAfterTheCountLine() {
        WebSearchResult observed = new WebSearchResult(
                200,
                new UpstreamPayload(oneResultBody()),
                new RateLimitSnapshot(
                        List.of(new RateLimitWindow("minute", 10, 2, Duration.ofSeconds(3))), List.of(), java.time.Instant.EPOCH),
                null,
                null,
                null);

        Rendered rendered = render(successOf(observed), human(false), FULL_CONTEXT);

        assertEquals(
                "Web results for: three word query\n"
                        + "\n"
                        + " 1  Only\n"
                        + "    https://example.com/only\n"
                        + "    One line.\n"
                        + "1 result.\n"
                        + "quota: 2 of 10 remaining (window resets in 3s)\n",
                rendered.stdoutText());
    }

    @Test
    void aHealthyRateWindowRendersNoQuotaFooter() {
        WebSearchResult observed = new WebSearchResult(
                200,
                new UpstreamPayload(oneResultBody()),
                new RateLimitSnapshot(
                        List.of(new RateLimitWindow("minute", 10, 3, Duration.ofSeconds(3))), List.of(), java.time.Instant.EPOCH),
                null,
                null,
                null);

        Rendered rendered = render(successOf(observed), human(false), FULL_CONTEXT);

        assertEquals(
                "Web results for: three word query\n"
                        + "\n"
                        + " 1  Only\n"
                        + "    https://example.com/only\n"
                        + "    One line.\n"
                        + "1 result.\n",
                rendered.stdoutText());
    }

    @Test
    void quietSuppressesTheQuotaFooterLikeEveryAdvisoryLine() {
        Rendered rendered = render(successOf(fullResults()), human(true), FULL_CONTEXT);

        assertEquals(0, rendered.exit());
        assertFalse(rendered.stdoutText().contains("quota"), () -> rendered.stdoutText());
        assertTrue(rendered.stdoutText().endsWith("3 results.\n"), () -> rendered.stdoutText());
    }

    @Test
    void aNarrowRenderWidthWrapsTitlesAndDescriptionsToTheRequestedColumns() {
        WebSearchResult wide = resultOf(
                ("{\"web\":{\"results\":[{\"title\":\"A title of exactly the kind that keeps going past narrow columns\","
                                + "\"url\":\"https://example.com/first\","
                                + "\"description\":\"A description sentence that also runs far beyond the requested width.\"}]}}")
                        .getBytes(UTF_8));

        Rendered rendered = render(
                successOf(wide),
                new OutputRequest(false, OutputMode.HUMAN, false, false, false, false, 40),
                FULL_CONTEXT);

        assertEquals(
                "Web results for: three word query\n"
                        + "\n"
                        + " 1  A title of exactly the kind that\n"
                        + "    keeps going past narrow columns\n"
                        + "    https://example.com/first\n"
                        + "    A description sentence that also\n"
                        + "    runs far beyond the requested width.\n"
                        + "1 result.\n",
                rendered.stdoutText());
    }

    @Test
    void aColorableRenderContextStylesTitlesBoldAndUrlsDim() {
        Rendered rendered = render(
                successOf(resultOf(oneResultBody())),
                new OutputRequest(false, OutputMode.HUMAN, false, false, false, true, 100),
                FULL_CONTEXT);

        assertEquals(
                "\u001b[1mWeb results for: three word query\u001b[0m\n"
                        + "\n"
                        + " 1  \u001b[1mOnly\u001b[0m\n"
                        + "    \u001b[2mhttps://example.com/only\u001b[0m\n"
                        + "    One line.\n"
                        + "1 result.\n",
                rendered.stdoutText());
    }

    private static byte[] oneResultBody() {
        return "{\"web\":{\"results\":[{\"title\":\"Only\",\"url\":\"https://example.com/only\",\"description\":\"One line.\"}]}}"
                .getBytes(UTF_8);
    }

    @Test
    void humanModeWithZeroResultsPrintsExactlyOneLine() {
        Rendered rendered = render(successOf(resultOf(fixture("zero-results.json"))), human(false), ZERO_CONTEXT);

        assertEquals(0, rendered.exit());
        assertEquals("No results.\n", rendered.stdoutText());
        assertEquals(List.of(), rendered.stderr());
    }

    @Test
    void upstreamTerminalControlsNeverReachTheHumanDocumentButStayLosslessInMachineModes() {
        // JSON-escaped wire form of an adversarial result: cursor-wipe CSI, an OSC-8
        // hyperlink with both its ST terminators, a carriage return, a BEL, and a
        // styled-URL CSI — exactly the shapes an upstream forger would send
        byte[] body = ("{\"web\":{\"results\":[{"
                        + "\"title\":\"inno\\u001b[2Jcent\\u001b]8;;https://evil.example\\u001b\\\\link\\u001b]8;;\\u001b\\\\ title\","
                        + "\"url\":\"https://example.com/\\u001b[1mstyled\","
                        + "\"description\":\"de\\rscript\\u0007ion \\u001b[?25lhidden\"}]}}")
                .getBytes(UTF_8);
        WebSearchResult adversarial = resultOf(body);

        Rendered humanRendered = render(successOf(adversarial), human(false), FULL_CONTEXT);
        String human = humanRendered.stdoutText();
        assertEquals(0, humanRendered.exit());
        assertFalse(human.contains("\u001b"), "no escape byte may reach the human document");
        assertFalse(human.contains("\r"), "no carriage return may reach the human document");
        assertFalse(human.contains("evil.example"), "a forged hyperlink must vanish wholly");
        assertFalse(human.contains("[2J"), "a cursor-wipe sequence must vanish wholly");
        assertTrue(human.contains("innocentlink"), "escape-free title text survives wholly removed sequences");
        assertTrue(human.contains("de\ufffdscript\ufffdion hidden"), "lone controls become replacement characters");
        assertTrue(human.contains("https://example.com/styled"), "the url sheds its forged styling");

        Rendered jsonlRendered = render(successOf(adversarial), machine(OutputMode.JSONL, false), FULL_CONTEXT);
        assertEquals(0, jsonlRendered.exit());
        assertTrue(
                jsonlRendered.stdoutText().contains("evil.example"),
                "the machine record keeps the forged hyperlink text losslessly");
        assertTrue(
                jsonlRendered.stdoutText().contains("\\r"),
                "the machine record keeps the carriage return escaped, not stripped");

        Rendered rawRendered = render(successOf(adversarial), machine(OutputMode.RAW, false), FULL_CONTEXT);
        assertEquals(0, rawRendered.exit());
        assertArrayEquals(body, rawRendered.stdout(), "raw stays the byte-exact body, controls included");
    }

    @Test
    void humanHeadingNamesThePageOnlyBeyondTheFirst() {
        WebSearchResult single = resultOf("{\"web\":{\"results\":[{\"title\":\"Only\"}]}}".getBytes(UTF_8));

        Rendered firstPage = render(successOf(single), human(false), WebSearchRequest.builder("q").page(1).build());
        Rendered thirdPage = render(successOf(single), human(false), WebSearchRequest.builder("q").page(3).build());

        assertEquals("Web results for: q\n\n 1  Only\n1 result.\n", firstPage.stdoutText());
        assertEquals("Web results for: q (page 3)\n\n 1  Only\n1 result.\n", thirdPage.stdoutText());
    }

    @Test
    void jsonModeWritesOneEnvelopeWithTheWebProjectionAndTheLosslessUpstreamTree() {
        Rendered rendered = render(successOf(fullResults()), machine(OutputMode.JSON, false), FULL_CONTEXT);

        assertEquals(0, rendered.exit());
        assertEquals(List.of(), rendered.stderr());
        assertEquals(JSON_SUCCESS_GOLDEN, rendered.stdoutText());
    }

    @Test
    void jsonModeKeepsDecimalsOfUnknownUpstreamFieldsAtExactScale() {
        assertTrue(JSON_SUCCESS_GOLDEN.contains("\"cost\":1.10,"), "the unknown cost decimal keeps its trailing zero");
        assertTrue(
                JSON_SUCCESS_GOLDEN.contains("\"long\":0.1000000000000000000001"),
                "the long unknown significand survives digit-for-digit");
    }

    @Test
    void jsonModeWithZeroResultsEmitsAnEmptyResultList() {
        Rendered rendered = render(
                successOf(resultOf(fixture("zero-results.json"))), machine(OutputMode.JSON, false), ZERO_CONTEXT);

        assertEquals(JSON_ZERO_GOLDEN, rendered.stdoutText());
    }

    @Test
    void jsonPrettyModeIndentsStableOrderingAndStillEndsInOneLf() {
        WebSearchResult result =
                resultOf("{\"web\":{\"results\":[{\"title\":\"Only\",\"url\":\"https://example.com/only\",\"description\":\"One line.\"}]}}"
                        .getBytes(UTF_8));

        Rendered rendered = render(successOf(result), machine(OutputMode.JSON, true), WebSearchRequest.builder("only query").page(2).build());

        assertEquals(0, rendered.exit());
        assertEquals(JSON_PRETTY_GOLDEN, rendered.stdoutText());
    }

    @Test
    void jsonModeEncodesANonObjectUpstreamBodyAsParsedWithZeroResults() {
        WebSearchResult array = resultOf("[1,2,3]".getBytes(UTF_8));

        Rendered rendered = render(successOf(array), machine(OutputMode.JSON, false), FULL_CONTEXT);

        assertEquals(
                "{\"schema_version\":\"1\",\"ok\":true,\"command\":\"web\","
                        + "\"data\":{\"projection\":{\"result_count\":0,\"page\":1,\"upstream_offset\":0,\"results\":[]},"
                        + "\"upstream\":[1,2,3]},"
                        + "\"meta\":{\"request_id\":null,\"http_status\":200,\"api_version\":null,"
                        + "\"rate_limits\":[],\"usage\":null,\"warnings\":[]}}\n",
                rendered.stdoutText());
    }

    @Test
    void jsonlModeEmitsOneResultRecordPerLogicalResultThenExactlyOneSummary() {
        Rendered rendered = render(successOf(fullResults()), machine(OutputMode.JSONL, false), FULL_CONTEXT);

        assertEquals(0, rendered.exit());
        assertEquals(List.of(), rendered.stderr());
        assertEquals(JSONL_GOLDEN, rendered.stdoutText());
    }

    @Test
    void jsonlModeWithZeroResultsEmitsTheSummaryAlone() {
        Rendered rendered = render(
                successOf(resultOf(fixture("zero-results.json"))), machine(OutputMode.JSONL, false), ZERO_CONTEXT);

        assertEquals(
                "{\"schema_version\":\"1\",\"type\":\"summary\",\"command\":\"web\","
                        + "\"result_count\":0,\"page\":1,\"upstream_offset\":0,\"http_status\":200}\n",
                rendered.stdoutText());
    }

    @Test
    void jsonlModeIgnoresPrettyBecauseRecordsStayCompact() {
        Rendered rendered = render(
                successOf(resultOf(fixture("zero-results.json"))), machine(OutputMode.JSONL, true), ZERO_CONTEXT);

        assertEquals(
                "{\"schema_version\":\"1\",\"type\":\"summary\",\"command\":\"web\","
                        + "\"result_count\":0,\"page\":1,\"upstream_offset\":0,\"http_status\":200}\n",
                rendered.stdoutText());
    }

    @Test
    void rawModeWritesTheBodyBytesExactlyWithNoAddedLf() {
        byte[] body = fixture("full-results.json");

        Rendered rendered = render(successOf(resultOf(body)), machine(OutputMode.RAW, false), FULL_CONTEXT);

        assertEquals(0, rendered.exit());
        assertArrayEquals(body, rendered.stdout(), "raw output is the decoded body byte-for-byte");
        assertEquals(List.of(), rendered.stderr());
    }

    @Test
    void anAuthenticationFailureRendersEachModeOwnFailureDocument() {
        Outcome.Failure<WebSearchResult> failure = new Outcome.Failure<>(
                FailureKind.AUTHENTICATION,
                "upstream exchange failed with status 401",
                new io.amscotti.bravesearch.domain.error.UpstreamError(
                        "unauthorized", null, new UpstreamPayload(fixture("unauthorized-error.json"))),
                null,
                401);

        Rendered json = render(failure, machine(OutputMode.JSON, false), FULL_CONTEXT);
        assertEquals(4, json.exit());
        assertEquals(
                "{\"schema_version\":\"1\",\"ok\":false,\"command\":\"web\",\"error\":{\"code\":\"AUTHENTICATION_FAILED\","
                        + "\"message\":\"upstream exchange failed with status 401\",\"retryable\":false,"
                        + "\"upstream_code\":\"unauthorized\",\"details\":null},"
                        + "\"meta\":{\"http_status\":401,\"rate_limits\":[]}}\n",
                json.stdoutText());
        assertEquals(List.of("web: upstream exchange failed with status 401"), json.stderr());

        Rendered jsonl = render(failure, machine(OutputMode.JSONL, false), FULL_CONTEXT);
        assertEquals(4, jsonl.exit());
        assertEquals(
                "{\"schema_version\":\"1\",\"type\":\"error\",\"command\":\"web\",\"code\":\"AUTHENTICATION_FAILED\","
                        + "\"message\":\"upstream exchange failed with status 401\",\"retryable\":false}\n",
                jsonl.stdoutText());
        assertEquals(List.of(), jsonl.stderr());

        Rendered human = render(failure, human(false), FULL_CONTEXT);
        assertEquals(4, human.exit());
        assertEquals("", human.stdoutText());
        assertEquals(List.of("web: upstream exchange failed with status 401"), human.stderr());

        Rendered raw = render(failure, machine(OutputMode.RAW, false), FULL_CONTEXT);
        assertEquals(4, raw.exit());
        assertArrayEquals(fixture("unauthorized-error.json"), raw.stdout(), "raw failure emits the bounded error body");
        assertEquals(List.of("web: upstream exchange failed with status 401"), raw.stderr());
    }

    @Test
    void aRateLimitedFailureIsRetryableAndExplainsItselfWithItsWindows() {
        RateLimitSnapshot windows =
                new RateLimitSnapshot(List.of(new RateLimitWindow("request", 1, 0, Duration.ofSeconds(2))), List.of(), java.time.Instant.EPOCH);
        Outcome.Failure<WebSearchResult> failure = new Outcome.Failure<>(
                FailureKind.RATE_LIMITED, "Upstream rate limit exceeded.", null, windows, 429);

        Rendered jsonl = render(failure, machine(OutputMode.JSONL, false), FULL_CONTEXT);
        Rendered json = render(failure, machine(OutputMode.JSON, false), FULL_CONTEXT);

        assertEquals(
                "{\"schema_version\":\"1\",\"type\":\"error\",\"command\":\"web\",\"code\":\"RATE_LIMITED\","
                        + "\"message\":\"Upstream rate limit exceeded.\",\"retryable\":true,"
                        + "\"rate_limits\":[{\"policy\":\"request\",\"limit\":1,\"remaining\":0,\"reset_ms\":2000}]}\n",
                jsonl.stdoutText());
        assertEquals(
                "{\"schema_version\":\"1\",\"ok\":false,\"command\":\"web\",\"error\":{\"code\":\"RATE_LIMITED\","
                        + "\"message\":\"Upstream rate limit exceeded.\",\"retryable\":true,\"upstream_code\":null,"
                        + "\"details\":null},\"meta\":{\"http_status\":429,"
                        + "\"rate_limits\":[{\"policy\":\"request\",\"limit\":1,\"remaining\":0,\"reset_ms\":2000}]}}\n",
                json.stdoutText());
        assertEquals(5, json.exit());
    }

    @Test
    void aTransportFailureWithoutAStatusReportsStatusZero() {
        Outcome.Failure<WebSearchResult> failure = new Outcome.Failure<>(FailureKind.TRANSPORT, "connect timed out");

        Rendered json = render(failure, machine(OutputMode.JSON, false), FULL_CONTEXT);

        assertEquals(6, json.exit());
        assertEquals(
                "{\"schema_version\":\"1\",\"ok\":false,\"command\":\"web\",\"error\":{\"code\":\"TRANSPORT_ERROR\","
                        + "\"message\":\"connect timed out\",\"retryable\":false,\"upstream_code\":null,\"details\":null},"
                        + "\"meta\":{\"http_status\":0,\"rate_limits\":[]}}\n",
                json.stdoutText());
    }

    @Test
    void anUnreadableSuccessBodyRendersAsMalformedInEveryParsingModeButStaysRawInRawMode() {
        WebSearchResult garbage = resultOf("gateway exploded <html>".getBytes(UTF_8));

        Rendered human = render(successOf(garbage), human(false), FULL_CONTEXT);
        assertEquals(8, human.exit());
        assertEquals("", human.stdoutText());
        assertEquals(1, human.stderr().size());
        assertTrue(human.stderr().getFirst().startsWith("web: "), () -> String.join("\n", human.stderr()));

        Rendered json = render(successOf(garbage), machine(OutputMode.JSON, false), FULL_CONTEXT);
        assertEquals(8, json.exit());
        assertEquals(
                "{\"schema_version\":\"1\",\"ok\":false,\"command\":\"web\",\"error\":{\"code\":\"MALFORMED_RESPONSE\","
                        + "\"message\":\"upstream success body is not readable JSON\",\"retryable\":false,"
                        + "\"upstream_code\":null,\"details\":null},"
                        + "\"meta\":{\"http_status\":200,\"rate_limits\":[]}}\n",
                json.stdoutText());

        Rendered jsonl = render(successOf(garbage), machine(OutputMode.JSONL, false), FULL_CONTEXT);
        assertEquals(8, jsonl.exit());
        assertEquals(
                "{\"schema_version\":\"1\",\"type\":\"error\",\"command\":\"web\",\"code\":\"MALFORMED_RESPONSE\","
                        + "\"message\":\"upstream success body is not readable JSON\",\"retryable\":false,"
                        + "\"rate_limits\":[]}\n",
                jsonl.stdoutText());

        Rendered raw = render(successOf(garbage), machine(OutputMode.RAW, false), FULL_CONTEXT);
        assertEquals(0, raw.exit(), "raw mode never parses the body it passes through");
        assertEquals("gateway exploded <html>", raw.stdoutText());
    }

    @Test
    void exchangeNotesRouteToTheChannelTheActiveModeOwns() {
        WebSearchResult observed = new WebSearchResult(
                200,
                new UpstreamPayload(fixture("zero-results.json")),
                new RateLimitSnapshot(
                        List.of(), List.of("rate-limit headers: dropped 1 malformed window"), java.time.Instant.EPOCH),
                new Usage(null, null, null, null, null, null, null, null, null, java.util.Map.of(), List.of("usage headers: skipped 1 unknown value")),
                null,
                null);

        Rendered humanRun = render(successOf(observed), human(false), ZERO_CONTEXT);
        assertEquals(
                List.of(
                        "rate-limit headers: dropped 1 malformed window",
                        "usage headers: skipped 1 unknown value"),
                humanRun.stderr());
        assertEquals("No results.\n", humanRun.stdoutText());

        Rendered quietRun = render(successOf(observed), human(true), ZERO_CONTEXT);
        assertEquals(List.of(), quietRun.stderr(), "quiet suppresses advisory human diagnostics");
        assertEquals("No results.\n", quietRun.stdoutText());

        Rendered jsonRun = render(successOf(observed), machine(OutputMode.JSON, false), ZERO_CONTEXT);
        assertEquals(
                List.of("rate-limit headers: dropped 1 malformed window", "usage headers: skipped 1 unknown value"),
                warningsOf(jsonRun.stdoutText()));

        Rendered jsonlRun = render(successOf(observed), machine(OutputMode.JSONL, false), ZERO_CONTEXT);
        assertEquals(
                "{\"schema_version\":\"1\",\"type\":\"warning\",\"command\":\"web\","
                        + "\"message\":\"rate-limit headers: dropped 1 malformed window\"}\n"
                        + "{\"schema_version\":\"1\",\"type\":\"warning\",\"command\":\"web\","
                        + "\"message\":\"usage headers: skipped 1 unknown value\"}\n"
                        + "{\"schema_version\":\"1\",\"type\":\"summary\",\"command\":\"web\","
                        + "\"result_count\":0,\"page\":1,\"upstream_offset\":0,\"http_status\":200}\n",
                jsonlRun.stdoutText());
    }

    @Test
    void unknownUpstreamFieldsSurviveLosslesslyWhileTheProjectionCarriesOnlyStableFields() {
        byte[] body = fixture("full-results.json");

        Rendered json = render(successOf(resultOf(body)), machine(OutputMode.JSON, false), FULL_CONTEXT);

        JsonNode document = mappers.upstreamReader().readTree(json.stdout());
        JsonNode expectedUpstream = mappers.upstreamReader().readTree(body);
        assertEquals(
                expectedUpstream,
                document.path("data").path("upstream"),
                "data.upstream byte-parses to the same semantic tree as the original body");
        List<String> projectionKeys = new ArrayList<>();
        collectKeys(document.path("data").path("projection"), projectionKeys);
        assertEquals(
                List.of(
                        "result_count", "page", "upstream_offset", "results",
                        "position", "title", "url", "description",
                        "position", "title", "url",
                        "position", "url", "description"),
                projectionKeys,
                "the projection carries exactly the stable web fields");
    }

    @Test
    void aBrokenStdoutPipeStaysASilentSuccessWhileOtherWriteFailuresKeepTheTransportStatus() {
        WebSearchResult result = resultOf(fixture("zero-results.json"));

        Rendered brokenPipe = renderWithWriter(
                successOf(result),
                machine(OutputMode.JSON, false),
                ZERO_CONTEXT,
                throwingWriter(new IOException("Broken pipe")));
        assertEquals(0, brokenPipe.exit(), "a downstream broken pipe is successful early termination");
        assertEquals(List.of(), brokenPipe.stderr());

        Rendered failed = renderWithWriter(
                successOf(result),
                machine(OutputMode.JSON, false),
                ZERO_CONTEXT,
                throwingWriter(new IOException("disk on fire")));
        assertEquals(6, failed.exit(), "any other write failure keeps the transport status");
        assertEquals(1, failed.stderr().size());
    }

    @Test
    void literalNullResultElementsAreSkippedAndThePositionGapSurvivesEveryMode() {
        WebSearchResult withNulls =
                resultOf("{\"web\":{\"results\":[null,{\"title\":\"After The Null\"},null]}}".getBytes(UTF_8));

        Rendered jsonl = render(successOf(withNulls), machine(OutputMode.JSONL, false), FULL_CONTEXT);
        assertEquals(
                "{\"schema_version\":\"1\",\"type\":\"result\",\"command\":\"web\",\"position\":1,\"bucket\":\"web\","
                        + "\"title\":\"After The Null\"}\n"
                        + "{\"schema_version\":\"1\",\"type\":\"summary\",\"command\":\"web\",\"result_count\":1,"
                        + "\"page\":1,\"upstream_offset\":0,\"http_status\":200}\n",
                jsonl.stdoutText());

        Rendered human = render(successOf(withNulls), human(false), FULL_CONTEXT);
        assertEquals("Web results for: three word query\n\n 1  After The Null\n1 result.\n", human.stdoutText());

        Rendered json = render(successOf(withNulls), machine(OutputMode.JSON, false), FULL_CONTEXT);
        JsonNode projection = mappers.upstreamReader().readTree(json.stdout()).path("data").path("projection");
        assertEquals(1, projection.path("results").size());
        assertEquals(
                1,
                projection.path("results").get(0).path("position").asInt(),
                "the projection keeps the original upstream index, not a renumbered one");
    }

    @Test
    void anOverBoundUpstreamErrorBodyWritesNoStdoutInRawModeAndKeepsExitEight() {
        Outcome.Failure<WebSearchResult> overBound = new Outcome.Failure<>(
                FailureKind.MALFORMED, "response exceeds structured error limit", null, null, 413);

        Rendered raw = render(overBound, machine(OutputMode.RAW, false), FULL_CONTEXT);

        assertEquals(8, raw.exit());
        assertEquals("", raw.stdoutText(), "an over-bound error body never reaches stdout");
        assertEquals(List.of("web: response exceeds structured error limit"), raw.stderr());
    }

    private static final String JSON_SUCCESS_GOLDEN =
            "{\"schema_version\":\"1\",\"ok\":true,\"command\":\"web\","
                    + "\"data\":{\"projection\":{\"result_count\":3,\"page\":1,\"upstream_offset\":0,"
                    + "\"results\":["
                    + "{\"position\":0,\"title\":\"First Title\",\"url\":\"https://example.com/first\",\"description\":\"First description text.\"},"
                    + "{\"position\":1,\"title\":\"Second Title\",\"url\":\"https://example.com/second\"},"
                    + "{\"position\":2,\"url\":\"https://example.com/third\",\"description\":\"Third description.\"}"
                    + "]},"
                    + "\"upstream\":{\"query\":{\"original\":\"three word query\"},\"web\":{\"results\":["
                    + "{\"title\":\"First Title\",\"url\":\"https://example.com/first\",\"description\":\"First description text.\",\"unknown_result_field\":{\"score\":0.125}},"
                    + "{\"title\":\"Second Title\",\"url\":\"https://example.com/second\",\"unknown_age\":\"2026-08-02\"},"
                    + "{\"title\":42,\"url\":\"https://example.com/third\",\"description\":\"Third description.\"}"
                    + "]},\"unknown_future_block\":{\"cost\":1.10,\"long\":0.1000000000000000000001}}},"
                    + "\"meta\":{\"request_id\":\"req-7f3a2b\",\"http_status\":200,\"api_version\":\"2024-08-01\","
                    + "\"rate_limits\":[{\"policy\":\"request\",\"limit\":1,\"remaining\":0,\"reset_ms\":1500}],"
                    + "\"usage\":null,\"warnings\":[]}}\n";

    private static final String JSON_ZERO_GOLDEN =
            "{\"schema_version\":\"1\",\"ok\":true,\"command\":\"web\","
                    + "\"data\":{\"projection\":{\"result_count\":0,\"page\":1,\"upstream_offset\":0,\"results\":[]},"
                    + "\"upstream\":{\"query\":{\"original\":\"nothing matches\"},\"web\":{}}},"
                    + "\"meta\":{\"request_id\":null,\"http_status\":200,\"api_version\":null,"
                    + "\"rate_limits\":[],\"usage\":null,\"warnings\":[]}}\n";

    private static final String JSON_PRETTY_GOLDEN =
            """
            {
              "schema_version": "1",
              "ok": true,
              "command": "web",
              "data": {
                "projection": {
                  "result_count": 1,
                  "page": 2,
                  "upstream_offset": 1,
                  "results": [
                    {
                      "position": 0,
                      "title": "Only",
                      "url": "https://example.com/only",
                      "description": "One line."
                    }
                  ]
                },
                "upstream": {
                  "web": {
                    "results": [
                      {
                        "title": "Only",
                        "url": "https://example.com/only",
                        "description": "One line."
                      }
                    ]
                  }
                }
              },
              "meta": {
                "request_id": null,
                "http_status": 200,
                "api_version": null,
                "rate_limits": [],
                "usage": null,
                "warnings": []
              }
            }
            """;

    private static final String JSONL_GOLDEN =
            "{\"schema_version\":\"1\",\"type\":\"result\",\"command\":\"web\",\"position\":0,\"bucket\":\"web\","
                    + "\"title\":\"First Title\",\"url\":\"https://example.com/first\",\"description\":\"First description text.\"}\n"
                    + "{\"schema_version\":\"1\",\"type\":\"result\",\"command\":\"web\",\"position\":1,\"bucket\":\"web\","
                    + "\"title\":\"Second Title\",\"url\":\"https://example.com/second\"}\n"
                    + "{\"schema_version\":\"1\",\"type\":\"result\",\"command\":\"web\",\"position\":2,\"bucket\":\"web\","
                    + "\"url\":\"https://example.com/third\",\"description\":\"Third description.\"}\n"
                    + "{\"schema_version\":\"1\",\"type\":\"summary\",\"command\":\"web\",\"result_count\":3,"
                    + "\"page\":1,\"upstream_offset\":0,\"http_status\":200,"
                    + "\"request_id\":\"req-7f3a2b\",\"api_version\":\"2024-08-01\"}\n";

    private static Outcome.Success<WebSearchResult> successOf(WebSearchResult result) {
        return new Outcome.Success<>(result);
    }

    private static WebSearchResult fullResults() {
        return new WebSearchResult(
                200,
                new UpstreamPayload(fixture("full-results.json")),
                new RateLimitSnapshot(
                        List.of(new RateLimitWindow("request", 1, 0, Duration.ofMillis(1500))), List.of(), java.time.Instant.EPOCH),
                null,
                "req-7f3a2b",
                "2024-08-01");
    }

    private static WebSearchResult resultOf(byte[] body) {
        return new WebSearchResult(200, new UpstreamPayload(body), null, null, null, null);
    }

    private static OutputRequest human(boolean quiet) {
        return new OutputRequest(false, OutputMode.HUMAN, false, false, quiet);
    }

    private static OutputRequest machine(OutputMode mode, boolean pretty) {
        return new OutputRequest(true, mode, pretty, false, false);
    }

    private static byte[] fixture(String name) {
        String resource = "/fixtures/brave/web/" + name;
        try (java.io.InputStream bytes = WebSearchPresenterImplTest.class.getResourceAsStream(resource)) {
            if (bytes == null) {
                throw new IllegalStateException("missing fixture " + resource);
            }
            return bytes.readAllBytes();
        } catch (IOException missing) {
            throw new UncheckedIOException(missing);
        }
    }

    private static List<String> warningsOf(String jsonDocument) {
        List<String> warnings = new ArrayList<>();
        SHARED.upstreamReader().readTree(jsonDocument.getBytes(UTF_8))
                .path("meta")
                .path("warnings")
                .forEach(node -> warnings.add(node.stringValue()));
        return warnings;
    }

    private static final JsonMappers SHARED = new JsonMappers();

    private static void collectKeys(JsonNode node, List<String> into) {
        if (node.isObject()) {
            node.propertyNames().forEach(name -> {
                into.add(name);
                collectKeys(node.path(name), into);
            });
        } else if (node.isArray()) {
            node.forEach(element -> collectKeys(element, into));
        }
    }

    private Rendered render(Outcome<WebSearchResult> outcome, OutputRequest output, WebSearchRequest request) {
        return renderWithWriter(outcome, output, request, null);
    }

    private Rendered renderWithWriter(
            Outcome<WebSearchResult> outcome, OutputRequest output, WebSearchRequest request, ResultWriter failing) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        List<String> stderr = new ArrayList<>();
        ResultWriter results = failing != null ? failing : new OutputStreamResultWriter(stdout);
        int exit = presenter.present(outcome, request, output, results, diagnostic -> stderr.add(diagnostic));
        return new Rendered(exit, stdout.toByteArray(), stderr);
    }

    private static ResultWriter throwingWriter(IOException failure) {
        return document -> {
            throw new UncheckedIOException(failure);
        };
    }

    private record Rendered(int exit, byte[] stdout, List<String> stderr) {
        String stdoutText() {
            return new String(stdout, UTF_8);
        }
    }
}

package io.amscotti.bravesearch.adapter.cli.presentation.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.presentation.OutputStreamResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.application.service.PaginationService;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import io.amscotti.bravesearch.domain.metadata.RateLimitWindow;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.output.OutputMode;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.request.WebSearchRequest;
import io.amscotti.bravesearch.domain.result.PagedSearch;
import io.amscotti.bravesearch.domain.result.WebSearchResult;
import io.amscotti.bravesearch.testsupport.SchemaCatalog;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Byte-exact renderings of one completed multi-request web pagination on every output
 * channel: the deduplicated aggregate human listing with its counts line, the single JSON
 * envelope whose {@code data.upstream} is the ordered per-request array, JSONL result
 * records streamed per completed page with their page provenance and one terminal summary,
 * and the partial-failure matrix — buffered modes emit no result payload, JSONL keeps its
 * already-written records and ends in the counted error record, every mode keeps the failed
 * request's exit status.
 */
final class WebPagedSearchPresenterImplTest {

    private static final WebSearchRequest REQUEST = WebSearchRequest.builder("three word query").build();

    private static final String PAGE_ONE_BODY =
            "{\"query\":{\"original\":\"three word query\",\"more_results_available\":true},"
                    + "\"web\":{\"results\":["
                    + "{\"title\":\"First Title\",\"url\":\"https://example.com/first\",\"description\":\"First description text.\"},"
                    + "{\"title\":\"Overlap Title\",\"url\":\"https://example.com/shared\"}]}}";

    private static final String PAGE_TWO_BODY =
            "{\"query\":{\"original\":\"three word query\"},"
                    + "\"web\":{\"results\":["
                    + "{\"title\":\"Shared Again\",\"url\":\"https://example.com/shared\"},"
                    + "{\"title\":\"Second Page\",\"url\":\"https://example.com/second\"}]}}";

    private final JsonMappers mappers = new JsonMappers();

    private final WebPagedSearchPresenterImpl presenter = new WebPagedSearchPresenterImpl(
            new EnvelopeCodec(mappers),
            new JsonlCodec(mappers),
            new WebProjectionExtractor(mappers),
            (mode, diagnostics, results, quiet) -> new io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings(
                    mode, WebPagedSearchPresenterImplTest.COMMAND, diagnostics, results, new JsonlCodec(mappers), quiet));

    private final SchemaCatalog schemas = new SchemaCatalog();

    static final String COMMAND = "web";

    @Test
    void humanModeListsTheDeduplicatedAggregateWithACountsLine() {
        Rendered rendered = renderSuccess(human(false));

        assertEquals(0, rendered.exit());
        assertEquals(
                "Web results for: three word query (pages 1-2)\n"
                        + "\n"
                        + " 1  First Title\n"
                        + "    https://example.com/first\n"
                        + "    First description text.\n"
                        + "\n"
                        + " 2  Overlap Title\n"
                        + "    https://example.com/shared\n"
                        + "\n"
                        + " 3  Second Page\n"
                        + "    https://example.com/second\n"
                        + "3 results across 2 of 3 requested pages, 1 duplicate removed.\n",
                rendered.stdoutText());
        assertEquals(List.of(), rendered.stderr());
    }

    @Test
    void humanModeWithOneReceivedPageNamesNoPageSuffix() {
        PagedSearch.Page<WebSearchResult> only = new PagedSearch.Page<>(0, 1, null, resultOf(200, PAGE_ONE_BODY, snapshotWithNotes(), null, null));

        Rendered rendered = render(
                new PaginationService.PagedRun<>(3, List.of(only), null), REQUEST, human(false));

        assertEquals(
                "Web results for: three word query\n"
                        + "\n"
                        + " 1  First Title\n"
                        + "    https://example.com/first\n"
                        + "    First description text.\n"
                        + "\n"
                        + " 2  Overlap Title\n"
                        + "    https://example.com/shared\n"
                        + "2 results across 1 of 3 requested pages, 0 duplicates removed.\n",
                rendered.stdoutText());
    }

    @Test
    void humanModeWithZeroKeptResultsStillReportsTheCounts() {
        String emptyOne = "{\"web\":{\"results\":[]}}";
        PaginationService.PagedRun<WebSearchResult> run = new PaginationService.PagedRun<>(
                3,
                List.of(new PagedSearch.Page<>(0, 1, null, resultOf(200, emptyOne, RateLimitSnapshot.empty(), null, null))),
                null);

        Rendered rendered = render(run, REQUEST, human(false));

        assertEquals("0 results across 1 of 3 requested pages, 0 duplicates removed.\n", rendered.stdoutText());
    }

    @Test
    void jsonModeWritesOneEnvelopeWithTheUpstreamArrayAndTheCountedProjection() {
        Rendered rendered = renderSuccess(machine(OutputMode.JSON, false));

        assertEquals(0, rendered.exit());
        assertEquals(JSON_PAGED_GOLDEN, rendered.stdoutText());
        assertTrue(
                schemas.validate("envelope-success.schema.json", rendered.stdout()).isEmpty(),
                "the paged envelope satisfies the published success schema");
    }

    @Test
    void jsonlModeStreamsDeduplicatedRecordsPerPageAndEndsInOneCountedSummary() {
        Rendered rendered = renderSuccess(machine(OutputMode.JSONL, false));

        assertEquals(0, rendered.exit());
        assertEquals(JSONL_PAGED_GOLDEN, rendered.stdoutText());
        for (String line : rendered.stdoutText().stripTrailing().split("\n")) {
            assertTrue(
                    schemas.validate("jsonl-record.schema.json", line.getBytes(UTF_8)).isEmpty(),
                    "every paged JSONL line satisfies the published record schema: " + line);
        }
    }

    @Test
    void bufferedModesEmitNoResultPayloadWhenALaterPageFails() {
        PaginationService.PagedRun<WebSearchResult> run = failedAfterFirstPage();

        Rendered human = render(run, REQUEST, human(false));
        assertEquals(4, human.exit(), "the failed request's kind decides the exit status");
        assertEquals("", human.stdoutText(), "human mode buffers: no partial result payload");
        assertEquals(
                List.of("web: upstream exchange failed with status 401 (1 of 3 requested pages completed)"),
                human.stderr());

        Rendered json = render(run, REQUEST, machine(OutputMode.JSON, false));
        assertEquals(4, json.exit());
        assertEquals(
                "{\"schema_version\":\"1\",\"ok\":false,\"command\":\"web\","
                        + "\"error\":{\"code\":\"AUTHENTICATION_FAILED\","
                        + "\"message\":\"upstream exchange failed with status 401\",\"retryable\":false,"
                        + "\"upstream_code\":null,\"details\":{\"requested_pages\":3,\"received_pages\":1}},"
                        + "\"meta\":{\"http_status\":401,\"rate_limits\":[]}}\n",
                json.stdoutText());
        assertTrue(
                schemas.validate("envelope-error.schema.json", json.stdout()).isEmpty(),
                "the counted failure envelope satisfies the published error schema");
        assertEquals(
                List.of("web: upstream exchange failed with status 401 (1 of 3 requested pages completed)"),
                json.stderr());
    }

    @Test
    void jsonlKeepsItsStreamedRecordsAndEndsInTheCountedErrorRecord() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ResultWriter results = new OutputStreamResultWriter(stdout);
        List<String> stderr = new ArrayList<>();

        Consumer<PagedSearch.Page<WebSearchResult>> observer =
                presenter.pageObserver(machine(OutputMode.JSONL, false), results, stderr::add);
        observer.accept(successRun().completedPages().getFirst());
        int exit = presenter.present(failedAfterFirstPage(), REQUEST, machine(OutputMode.JSONL, false), results, stderr::add);

        assertEquals(4, exit);
        assertEquals(
                "{\"schema_version\":\"1\",\"type\":\"result\",\"command\":\"web\",\"position\":0,\"page\":1,\"bucket\":\"web\","
                        + "\"title\":\"First Title\",\"url\":\"https://example.com/first\",\"description\":\"First description text.\"}\n"
                        + "{\"schema_version\":\"1\",\"type\":\"result\",\"command\":\"web\",\"position\":1,\"page\":1,\"bucket\":\"web\","
                        + "\"title\":\"Overlap Title\",\"url\":\"https://example.com/shared\"}\n"
                        + "{\"schema_version\":\"1\",\"type\":\"error\",\"command\":\"web\",\"code\":\"AUTHENTICATION_FAILED\","
                        + "\"message\":\"upstream exchange failed with status 401\",\"retryable\":false,"
                        + "\"requested_pages\":3,\"received_pages\":1}\n",
                stdout.toString(UTF_8));
        assertEquals(List.of(), stderr, "jsonl owns its failure explanation on stdout");
    }

    @Test
    void anUnreadablePageBodyRendersAsMalformedInEveryParsingMode() {
        PaginationService.PagedRun<WebSearchResult> run = new PaginationService.PagedRun<>(
                3,
                List.of(new PagedSearch.Page<>(
                        0, 1, null, resultOf(200, "gateway exploded <html>", RateLimitSnapshot.empty(), null, null))),
                null);

        Rendered json = render(run, REQUEST, machine(OutputMode.JSON, false));
        assertEquals(8, json.exit());
        assertTrue(json.stdoutText().contains("\"code\":\"MALFORMED_RESPONSE\""), json.stdoutText());

        Rendered human = render(run, REQUEST, human(false));
        assertEquals(8, human.exit());
        assertEquals("", human.stdoutText());
        assertEquals(1, human.stderr().size());
    }

    @Test
    void theWaitedPacingDelayRidesThePacedUpstreamEntry() {
        Rendered json = renderSuccess(machine(OutputMode.JSON, false));

        assertTrue(
                json.stdoutText().contains("\"waited_ms\":1500"),
                "the entry of the paced request reports its wait: " + json.stdoutText());
    }

    private Rendered renderSuccess(OutputRequest output) {
        return render(successRun(), REQUEST, output);
    }

    private static PaginationService.PagedRun<WebSearchResult> successRun() {
        return new PaginationService.PagedRun<>(
                3,
                List.of(
                        new PagedSearch.Page<>(
                                0,
                                1,
                                null,
                                resultOf(
                                        200,
                                        PAGE_ONE_BODY,
                                        snapshotOf(new RateLimitWindow("request", 1, 0, Duration.ofMillis(1500))),
                                        "req-1",
                                        "2024-08-01")),
                        new PagedSearch.Page<>(
                                1,
                                2,
                                Duration.ofMillis(1500),
                                resultOf(200, PAGE_TWO_BODY, RateLimitSnapshot.empty(), "req-2", null))),
                null);
    }

    private static PaginationService.PagedRun<WebSearchResult> failedAfterFirstPage() {
        return new PaginationService.PagedRun<>(
                3,
                List.of(new PagedSearch.Page<>(
                        0, 1, null, resultOf(200, PAGE_ONE_BODY, RateLimitSnapshot.empty(), "req-1", null))),
                new Outcome.Failure<>(FailureKind.AUTHENTICATION, "upstream exchange failed with status 401", null, null, 401));
    }

    private static RateLimitSnapshot snapshotOf(RateLimitWindow... windows) {
        return new RateLimitSnapshot(List.of(windows), List.of(), Instant.EPOCH);
    }

    private static RateLimitSnapshot snapshotWithNotes() {
        return new RateLimitSnapshot(List.of(), List.of(), Instant.EPOCH);
    }

    private static WebSearchResult resultOf(
            int status, String body, RateLimitSnapshot rateLimits, String requestId, String apiVersion) {
        return new WebSearchResult(
                status, new UpstreamPayload(body.getBytes(UTF_8)), rateLimits, null, requestId, apiVersion);
    }

    private static final String JSON_PAGED_GOLDEN =
            "{\"schema_version\":\"1\",\"ok\":true,\"command\":\"web\","
                    + "\"data\":{\"projection\":{\"result_count\":3,\"requested_pages\":3,\"received_pages\":2,"
                    + "\"duplicates_removed\":1,\"results\":["
                    + "{\"page\":1,\"position\":0,\"title\":\"First Title\",\"url\":\"https://example.com/first\",\"description\":\"First description text.\"},"
                    + "{\"page\":1,\"position\":1,\"title\":\"Overlap Title\",\"url\":\"https://example.com/shared\"},"
                    + "{\"page\":2,\"position\":1,\"title\":\"Second Page\",\"url\":\"https://example.com/second\"}"
                    + "]},"
                    + "\"upstream\":["
                    + "{\"request_index\":0,\"meta\":{\"request_id\":\"req-1\",\"http_status\":200,\"api_version\":\"2024-08-01\","
                    + "\"rate_limits\":[{\"policy\":\"request\",\"limit\":1,\"remaining\":0,\"reset_ms\":1500}],\"usage\":null},"
                    + "\"body\":" + PAGE_ONE_BODY + "},"
                    + "{\"request_index\":1,\"meta\":{\"request_id\":\"req-2\",\"http_status\":200,\"api_version\":null,"
                    + "\"rate_limits\":[],\"usage\":null,\"waited_ms\":1500},"
                    + "\"body\":" + PAGE_TWO_BODY + "}"
                    + "]},"
                    + "\"meta\":{\"request_id\":\"req-2\",\"http_status\":200,\"api_version\":null,"
                    + "\"rate_limits\":[],\"usage\":null,\"warnings\":[]}}\n";

    private static final String JSONL_PAGED_GOLDEN =
            "{\"schema_version\":\"1\",\"type\":\"result\",\"command\":\"web\",\"position\":0,\"page\":1,\"bucket\":\"web\","
                    + "\"title\":\"First Title\",\"url\":\"https://example.com/first\",\"description\":\"First description text.\"}\n"
                    + "{\"schema_version\":\"1\",\"type\":\"result\",\"command\":\"web\",\"position\":1,\"page\":1,\"bucket\":\"web\","
                    + "\"title\":\"Overlap Title\",\"url\":\"https://example.com/shared\"}\n"
                    + "{\"schema_version\":\"1\",\"type\":\"result\",\"command\":\"web\",\"position\":1,\"page\":2,\"bucket\":\"web\","
                    + "\"title\":\"Second Page\",\"url\":\"https://example.com/second\"}\n"
                    + "{\"schema_version\":\"1\",\"type\":\"summary\",\"command\":\"web\",\"result_count\":3,"
                    + "\"requested_pages\":3,\"received_pages\":2,\"duplicates_removed\":1,"
                    + "\"http_status\":200,\"request_id\":\"req-2\"}\n";

    private static OutputRequest human(boolean quiet) {
        return new OutputRequest(false, OutputMode.HUMAN, false, false, quiet);
    }

    private static OutputRequest machine(OutputMode mode, boolean pretty) {
        return new OutputRequest(true, mode, pretty, false, false);
    }

    private Rendered render(
            PaginationService.PagedRun<WebSearchResult> run, WebSearchRequest request, OutputRequest output) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        List<String> stderr = new ArrayList<>();
        ResultWriter results = new OutputStreamResultWriter(stdout);
        Consumer<PagedSearch.Page<WebSearchResult>> observer = presenter.pageObserver(output, results, stderr::add);
        run.completedPages().forEach(observer);
        int exit = presenter.present(run, request, output, results, stderr::add);
        return new Rendered(exit, stdout.toByteArray(), stderr);
    }

    private record Rendered(int exit, byte[] stdout, List<String> stderr) {
        String stdoutText() {
            return new String(stdout, UTF_8);
        }
    }
}

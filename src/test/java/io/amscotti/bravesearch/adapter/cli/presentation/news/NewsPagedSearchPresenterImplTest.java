package io.amscotti.bravesearch.adapter.cli.presentation.news;

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
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.output.OutputMode;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.request.NewsSearchRequest;
import io.amscotti.bravesearch.domain.result.NewsSearchResult;
import io.amscotti.bravesearch.domain.result.PagedSearch;
import io.amscotti.bravesearch.testsupport.SchemaCatalog;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Byte-exact renderings of one completed multi-request news pagination on every output
 * channel — the deduplicated aggregate human listing with the news heading, age lines, and
 * counts line, the single JSON envelope whose {@code data.upstream} is the ordered
 * per-request array, JSONL result records streamed per completed page with their page
 * provenance and one terminal summary — and the partial-failure matrix the channel contract
 * fixes: buffered modes emit no result payload, JSONL keeps its already-written records and
 * ends in the counted error record, every mode keeps the failed request's exit status.
 */
final class NewsPagedSearchPresenterImplTest {

    private static final NewsSearchRequest REQUEST = NewsSearchRequest.builder("three word query").build();

    private static final String PAGE_ONE_BODY =
            "{\"query\":{\"original\":\"three word query\"},"
                    + "\"news\":{\"results\":["
                    + "{\"title\":\"First Headline\",\"url\":\"https://example.com/first\",\"description\":\"First text.\",\"age\":\"2 hours ago\"},"
                    + "{\"title\":\"Overlap Headline\",\"url\":\"https://example.com/shared\",\"age\":\"1 hour ago\"}]}}";

    private static final String PAGE_TWO_BODY =
            "{\"query\":{\"original\":\"three word query\"},"
                    + "\"news\":{\"results\":["
                    + "{\"title\":\"Shared Again\",\"url\":\"https://example.com/shared\",\"age\":\"33 minutes ago\"},"
                    + "{\"title\":\"Second Page\",\"url\":\"https://example.com/second\"}]}}";

    private final JsonMappers mappers = new JsonMappers();

    private final NewsPagedSearchPresenterImpl presenter = new NewsPagedSearchPresenterImpl(
            new EnvelopeCodec(mappers),
            new JsonlCodec(mappers),
            new NewsProjectionExtractor(mappers),
            (mode, diagnostics, results, quiet) -> new io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings(
                    mode, NewsPagedSearchPresenterImplTest.COMMAND, diagnostics, results, new JsonlCodec(mappers), quiet));

    private final SchemaCatalog schemas = new SchemaCatalog();

    static final String COMMAND = "news";

    @Test
    void humanModeListsTheDeduplicatedAggregateWithItsCountsLine() {
        Rendered rendered = renderSuccess(human(false));

        assertEquals(0, rendered.exit());
        assertEquals(
                "News results for: three word query (pages 1-2)\n"
                        + "\n"
                        + " 1  First Headline\n"
                        + "    https://example.com/first\n"
                        + "    First text.\n"
                        + "    2 hours ago\n"
                        + "\n"
                        + " 2  Overlap Headline\n"
                        + "    https://example.com/shared\n"
                        + "    1 hour ago\n"
                        + "\n"
                        + " 3  Second Page\n"
                        + "    https://example.com/second\n"
                        + "3 results across 2 of 3 requested pages, 1 duplicate removed.\n",
                rendered.stdoutText());
    }

    @Test
    void jsonModeWritesOneEnvelopeWithTheUpstreamArrayAndTheCountedProjection() {
        Rendered rendered = renderSuccess(machine(OutputMode.JSON, false));

        assertEquals(0, rendered.exit());
        assertTrue(
                rendered.stdoutText().contains(
                        "\"projection\":{\"result_count\":3,\"requested_pages\":3,\"received_pages\":2,"
                                + "\"duplicates_removed\":1"),
                rendered.stdoutText());
        assertTrue(rendered.stdoutText().contains("\"request_index\":1"), "the upstream array is ordered per request");
        assertTrue(
                schemas.validate("envelope-success.schema.json", rendered.stdout()).isEmpty(),
                "the paged envelope satisfies the published success schema");
    }

    @Test
    void jsonlModeStreamsDeduplicatedRecordsPerPageAndEndsInOneCountedSummary() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ResultWriter results = new OutputStreamResultWriter(stdout);
        List<String> stderr = new ArrayList<>();

        Consumer<PagedSearch.Page<NewsSearchResult>> observer =
                presenter.pageObserver(machine(OutputMode.JSONL, false), results, stderr::add);
        observer.accept(successRun().completedPages().getFirst());
        int exit = presenter.present(successRun(), REQUEST, machine(OutputMode.JSONL, false), results, stderr::add);

        assertEquals(0, exit);
        assertEquals(
                "{\"schema_version\":\"1\",\"type\":\"result\",\"command\":\"news\",\"position\":0,\"page\":1,\"bucket\":\"news\","
                        + "\"title\":\"First Headline\",\"url\":\"https://example.com/first\",\"description\":\"First text.\",\"age\":\"2 hours ago\"}\n"
                        + "{\"schema_version\":\"1\",\"type\":\"result\",\"command\":\"news\",\"position\":1,\"page\":1,\"bucket\":\"news\","
                        + "\"title\":\"Overlap Headline\",\"url\":\"https://example.com/shared\",\"age\":\"1 hour ago\"}\n"
                        + "{\"schema_version\":\"1\",\"type\":\"summary\",\"command\":\"news\",\"result_count\":3,"
                        + "\"requested_pages\":3,\"received_pages\":2,\"duplicates_removed\":1,\"http_status\":200}\n",
                stdout.toString(UTF_8));
        for (String line : stdout.toString(UTF_8).stripTrailing().split("\n")) {
            assertTrue(
                    schemas.validateText("jsonl-record.schema.json", line).isEmpty(),
                    "every paged JSONL line satisfies the published record schema: " + line);
        }
    }

    @Test
    void bufferedModesEmitNoResultPayloadWhenALaterPageFails() {
        PaginationService.PagedRun<NewsSearchResult> run = failedAfterFirstPage();

        Rendered human = render(run, REQUEST, human(false));
        assertEquals(4, human.exit(), "the failed request's kind decides the exit status");
        assertEquals("", human.stdoutText(), "human mode buffers: no partial result payload");
        assertEquals(
                List.of("news: upstream exchange failed with status 401 (1 of 3 requested pages completed)"),
                human.stderr());

        Rendered json = render(run, REQUEST, machine(OutputMode.JSON, false));
        assertEquals(4, json.exit());
        assertTrue(
                json.stdoutText().contains(
                        "\"details\":{\"requested_pages\":3,\"received_pages\":1}"),
                json.stdoutText());
        assertTrue(
                schemas.validate("envelope-error.schema.json", json.stdout()).isEmpty(),
                "the counted failure envelope satisfies the published error schema");
    }

    @Test
    void jsonlKeepsItsStreamedRecordsAndEndsInTheCountedErrorRecord() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ResultWriter results = new OutputStreamResultWriter(stdout);
        List<String> stderr = new ArrayList<>();

        Consumer<PagedSearch.Page<NewsSearchResult>> observer =
                presenter.pageObserver(machine(OutputMode.JSONL, false), results, stderr::add);
        observer.accept(successRun().completedPages().getFirst());
        int exit = presenter.present(failedAfterFirstPage(), REQUEST, machine(OutputMode.JSONL, false), results, stderr::add);

        assertEquals(4, exit);
        assertEquals(
                "{\"schema_version\":\"1\",\"type\":\"result\",\"command\":\"news\",\"position\":0,\"page\":1,\"bucket\":\"news\","
                        + "\"title\":\"First Headline\",\"url\":\"https://example.com/first\",\"description\":\"First text.\",\"age\":\"2 hours ago\"}\n"
                        + "{\"schema_version\":\"1\",\"type\":\"result\",\"command\":\"news\",\"position\":1,\"page\":1,\"bucket\":\"news\","
                        + "\"title\":\"Overlap Headline\",\"url\":\"https://example.com/shared\",\"age\":\"1 hour ago\"}\n"
                        + "{\"schema_version\":\"1\",\"type\":\"error\",\"command\":\"news\",\"code\":\"AUTHENTICATION_FAILED\","
                        + "\"message\":\"upstream exchange failed with status 401\",\"retryable\":false,"
                        + "\"requested_pages\":3,\"received_pages\":1}\n",
                stdout.toString(UTF_8));
        assertEquals(List.of(), stderr, "jsonl owns its failure explanation on stdout");
    }

    @Test
    void anUnreadablePageBodyRendersAsMalformedInEveryParsingMode() {
        PaginationService.PagedRun<NewsSearchResult> run = new PaginationService.PagedRun<>(
                3, List.of(new PagedSearch.Page<>(0, 1, null, resultOf(200, "gateway exploded <html>"))), null);

        Rendered json = render(run, REQUEST, machine(OutputMode.JSON, false));
        assertEquals(8, json.exit());
        assertTrue(json.stdoutText().contains("\"code\":\"MALFORMED_RESPONSE\""), json.stdoutText());

        Rendered human = render(run, REQUEST, human(false));
        assertEquals(8, human.exit());
        assertEquals("", human.stdoutText());
        assertEquals(1, human.stderr().size());
    }

    private Rendered renderSuccess(OutputRequest output) {
        return render(successRun(), REQUEST, output);
    }

    private static PaginationService.PagedRun<NewsSearchResult> successRun() {
        return new PaginationService.PagedRun<>(
                3,
                List.of(
                        new PagedSearch.Page<>(0, 1, null, resultOf(200, PAGE_ONE_BODY)),
                        new PagedSearch.Page<>(1, 2, null, resultOf(200, PAGE_TWO_BODY))),
                null);
    }

    private static PaginationService.PagedRun<NewsSearchResult> failedAfterFirstPage() {
        return new PaginationService.PagedRun<>(
                3,
                List.of(new PagedSearch.Page<>(0, 1, null, resultOf(200, PAGE_ONE_BODY))),
                new Outcome.Failure<>(FailureKind.AUTHENTICATION, "upstream exchange failed with status 401", null, null, 401));
    }

    private static NewsSearchResult resultOf(int status, String body) {
        return new NewsSearchResult(status, new UpstreamPayload(body.getBytes(UTF_8)), null, null, null, null);
    }

    private Rendered render(
            PaginationService.PagedRun<NewsSearchResult> run, NewsSearchRequest request, OutputRequest output) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        List<String> stderr = new ArrayList<>();
        int exit =
                presenter.present(run, request, output, new OutputStreamResultWriter(stdout), stderr::add);
        return new Rendered(exit, stdout.toString(UTF_8), stdout.toByteArray(), stderr);
    }

    private static OutputRequest machine(OutputMode mode, boolean pretty) {
        return new OutputRequest(true, mode, pretty, false, false);
    }

    private static OutputRequest human(boolean quiet) {
        return new OutputRequest(false, OutputMode.HUMAN, false, false, quiet);
    }

    private record Rendered(int exit, String stdoutText, byte[] stdout, List<String> stderr) {}
}

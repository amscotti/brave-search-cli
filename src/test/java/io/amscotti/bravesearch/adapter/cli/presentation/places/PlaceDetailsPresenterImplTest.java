package io.amscotti.bravesearch.adapter.cli.presentation.places;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings;
import io.amscotti.bravesearch.adapter.cli.presentation.OutputStreamResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.PlaceDetailsPresenter;
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
import io.amscotti.bravesearch.domain.request.PlaceEnrichmentRequest;
import io.amscotti.bravesearch.domain.result.PagedSearch;
import io.amscotti.bravesearch.domain.result.PlaceEnrichmentResult;
import io.amscotti.bravesearch.testsupport.SchemaCatalog;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Byte-exact renderings of one completed places detail walk on every output channel —
 * the order-reconstructed human listing with its heading, placeholder lines, and counts
 * line, the single JSON envelope whose {@code data.upstream} is the ordered per-request
 * array, JSONL per-id records streamed per completed chunk in input order with
 * placeholders at their original positions and one terminal summary — and the
 * partial-failure matrix: buffered modes emit no result payload, JSONL keeps its
 * already-written records and ends in the counted error record, every mode keeps the
 * failed request's exit status.
 */
final class PlaceDetailsPresenterImplTest {

    /** Four ids in one chunk: poi-a duplicated, poi-b missing upstream, poi-c returned. */
    private static final PlaceEnrichmentRequest REQUEST = new PlaceEnrichmentRequest(
            PlaceEnrichmentRequest.Kind.DETAILS, List.of("poi-a", "poi-b", "poi-a", "poi-c"));

    private static final String CHUNK_ONE_BODY =
            "{\"type\":\"local_pois\",\"results\":["
                    + "{\"id\":\"poi-a\",\"title\":\"First POI\",\"url\":\"https://example.com/first\","
                    + "\"description\":\"First text.\",\"postal_address\":{\"displayAddress\":\"1 Main St\"},"
                    + "\"contact\":{\"telephone\":\"+1 215 555 0100\"},\"price_range\":\"$$\","
                    + "\"timezone\":\"America/New_York\",\"thumbnail\":{\"src\":\"https://example.com/thumb.jpg\"}},"
                    + "{\"id\":\"poi-c\",\"title\":\"Third POI\"}]}";

    private static final String FIRST_RECORD =
            "{\"schema_version\":\"1\",\"type\":\"result\",\"command\":\"places.details\",\"position\":0,"
                    + "\"bucket\":\"details\",\"id\":\"poi-a\",\"present\":true,\"title\":\"First POI\","
                    + "\"url\":\"https://example.com/first\",\"description\":\"First text.\","
                    + "\"display_address\":\"1 Main St\",\"phone\":\"+1 215 555 0100\",\"price_range\":\"$$\","
                    + "\"timezone\":\"America/New_York\",\"thumbnail\":\"https://example.com/thumb.jpg\"}";

    private final JsonMappers mappers = new JsonMappers();

    private final JsonlCodec jsonl = new JsonlCodec(mappers);

    private final PlaceDetailsPresenterImpl presenter = new PlaceDetailsPresenterImpl(
            new EnvelopeCodec(mappers),
            jsonl,
            new PlaceDetailsExtractor(mappers),
            (mode, diagnostics, results, quiet) ->
                    new ModeAwareWarnings(mode, PlaceDetailsPresenter.COMMAND, diagnostics, results, jsonl, quiet));

    private final SchemaCatalog schemas = new SchemaCatalog();

    @Test
    void humanModeListsEveryInputPositionWithPlaceholdersAndTheCountsLine() {
        Rendered rendered = renderSuccess(human(false));

        assertEquals(0, rendered.exit());
        assertEquals(
                "Place details for 4 ids\n"
                        + "\n"
                        + " 1  First POI\n"
                        + "    poi-a\n"
                        + "    https://example.com/first\n"
                        + "    First text.\n"
                        + "    1 Main St\n"
                        + "    +1 215 555 0100\n"
                        + "    https://example.com/thumb.jpg\n"
                        + "    $$\n"
                        + "    America/New_York\n"
                        + "\n"
                        + " 2  poi-b\n"
                        + "    (not returned)\n"
                        + "\n"
                        + " 3  First POI\n"
                        + "    poi-a\n"
                        + "    https://example.com/first\n"
                        + "    First text.\n"
                        + "    1 Main St\n"
                        + "    +1 215 555 0100\n"
                        + "    https://example.com/thumb.jpg\n"
                        + "    $$\n"
                        + "    America/New_York\n"
                        + "\n"
                        + " 4  Third POI\n"
                        + "    poi-c\n"
                        + "4 ids, 3 returned, 1 not returned.\n",
                rendered.stdoutText());
        assertEquals(List.of(), rendered.stderr());
    }

    @Test
    void jsonModeWritesOneEnvelopeWithTheUpstreamArrayAndTheReconstructedProjection() {
        Rendered rendered = renderSuccess(machine(OutputMode.JSON, false));

        assertEquals(0, rendered.exit());
        assertTrue(
                rendered.stdoutText().contains(
                        "\"projection\":{\"id_count\":4,\"returned_count\":3,\"missing_count\":1,"
                                + "\"requested_requests\":1,\"received_requests\":1,\"results\":["
                                + "{\"position\":0,\"id\":\"poi-a\",\"present\":true,\"title\":\"First POI\""),
                rendered.stdoutText());
        assertTrue(
                rendered.stdoutText().contains("\"position\":1,\"id\":\"poi-b\",\"present\":false}"),
                "the placeholder position renders present false with no payload members");
        assertTrue(rendered.stdoutText().contains("\"request_index\":0"), "the upstream array names its requests");
        assertTrue(
                schemas.validate("envelope-success.schema.json", rendered.stdout()).isEmpty(),
                "the enrichment envelope satisfies the published success schema");
    }

    @Test
    void jsonlStreamsPerIdRecordsInInputOrderAndEndsInOneCountedSummary() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ResultWriter results = new OutputStreamResultWriter(stdout);
        List<String> stderr = new ArrayList<>();

        Consumer<PagedSearch.Page<PlaceEnrichmentResult>> observer =
                presenter.chunkObserver(REQUEST, machine(OutputMode.JSONL, false), results, stderr::add);
        observer.accept(successRun().completedPages().getFirst());
        int exit = presenter.present(successRun(), REQUEST, machine(OutputMode.JSONL, false), results, stderr::add);

        assertEquals(0, exit);
        assertEquals(
                FIRST_RECORD + "\n"
                        + "{\"schema_version\":\"1\",\"type\":\"result\",\"command\":\"places.details\","
                        + "\"position\":1,\"bucket\":\"details\",\"id\":\"poi-b\",\"present\":false}\n"
                        + FIRST_RECORD.replace("\"position\":0", "\"position\":2") + "\n"
                        + "{\"schema_version\":\"1\",\"type\":\"result\",\"command\":\"places.details\","
                        + "\"position\":3,\"bucket\":\"details\",\"id\":\"poi-c\",\"present\":true,"
                        + "\"title\":\"Third POI\"}\n"
                        + "{\"schema_version\":\"1\",\"type\":\"summary\",\"command\":\"places.details\","
                        + "\"id_count\":4,\"returned_count\":3,\"missing_count\":1,"
                        + "\"requested_requests\":1,\"received_requests\":1,\"http_status\":200}\n",
                stdout.toString(UTF_8));
        for (String line : stdout.toString(UTF_8).stripTrailing().split("\n")) {
            assertTrue(
                    schemas.validateText("jsonl-record.schema.json", line).isEmpty(),
                    "every JSONL line satisfies the published record schema: " + line);
        }
    }

    @Test
    void aSecondChunksRecordsCarryTheirGlobalInputPositions() throws Exception {
        PlaceEnrichmentRequest request = new PlaceEnrichmentRequest(
                PlaceEnrichmentRequest.Kind.DETAILS,
                java.util.stream.IntStream.rangeClosed(1, 21).mapToObj(number -> "poi-" + number).toList());
        String chunkOne = "{\"results\":[{\"id\":\"poi-1\",\"title\":\"One\"}]}";
        String chunkTwo = "{\"results\":[{\"id\":\"poi-21\",\"title\":\"Twenty One\"}]}";
        PaginationService.PagedRun<PlaceEnrichmentResult> run = new PaginationService.PagedRun<>(
                2,
                List.of(
                        new PagedSearch.Page<>(0, 1, null, resultOf(200, chunkOne)),
                        new PagedSearch.Page<>(1, 2, null, resultOf(200, chunkTwo))),
                null);

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ResultWriter results = new OutputStreamResultWriter(stdout);
        Consumer<PagedSearch.Page<PlaceEnrichmentResult>> observer =
                presenter.chunkObserver(request, machine(OutputMode.JSONL, false), results, stderr -> {});
        run.completedPages().forEach(observer);
        presenter.present(run, request, machine(OutputMode.JSONL, false), results, diagnostics -> {});

        JsonNode summary = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(stdout.toString(UTF_8).stripTrailing().split("\n")[21]);
        assertEquals(2, summary.path("requested_requests").asInt());
        assertEquals(2, summary.path("received_requests").asInt());
        assertEquals(21, summary.path("id_count").asInt());
        assertEquals(2, summary.path("returned_count").asInt());
        assertEquals(19, summary.path("missing_count").asInt());
        String[] lines = stdout.toString(UTF_8).stripTrailing().split("\n");
        assertTrue(
                lines[20].contains("\"position\":20") && lines[20].contains("\"id\":\"poi-21\""),
                "the second chunk's records carry global input positions: " + lines[20]);
    }

    @Test
    void bufferedModesEmitNoResultPayloadWhenALaterChunkFails() {
        PlaceEnrichmentRequest request = requestOfTwentyFiveIds();
        PaginationService.PagedRun<PlaceEnrichmentResult> run = failedAfterFirstChunk();

        Rendered human = render(run, request, human(false));
        assertEquals(4, human.exit(), "the failed request's kind decides the exit status");
        assertEquals("", human.stdoutText(), "human mode buffers: no partial result payload");
        assertEquals(
                List.of(
                        "places.details: the Brave API rejected the API key; check the key with 'brave-search config show', or store a new one with 'brave-search config set-key' (1 of 2 requests completed)"),
                human.stderr());

        Rendered json = render(run, request, machine(OutputMode.JSON, false));
        assertEquals(4, json.exit());
        assertTrue(
                json.stdoutText().contains("\"details\":{\"requested_requests\":2,\"received_requests\":1}"),
                json.stdoutText());
        assertTrue(
                schemas.validate("envelope-error.schema.json", json.stdout()).isEmpty(),
                "the counted failure envelope satisfies the published error schema");
    }

    @Test
    void jsonlKeepsItsStreamedRecordsAndEndsInTheCountedErrorRecord() {
        PlaceEnrichmentRequest request = requestOfTwentyFiveIds();
        PaginationService.PagedRun<PlaceEnrichmentResult> failed = failedAfterFirstChunk();

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ResultWriter results = new OutputStreamResultWriter(stdout);
        List<String> stderr = new ArrayList<>();
        Consumer<PagedSearch.Page<PlaceEnrichmentResult>> observer =
                presenter.chunkObserver(request, machine(OutputMode.JSONL, false), results, stderr::add);
        observer.accept(failed.completedPages().getFirst());
        int exit = presenter.present(failed, request, machine(OutputMode.JSONL, false), results, stderr::add);

        assertEquals(4, exit);
        String[] lines = stdout.toString(UTF_8).stripTrailing().split("\n");
        assertEquals(
                21,
                lines.length,
                "the first chunk's twenty streamed records stand, then the counted error record");
        assertTrue(lines[0].contains("\"id\":\"poi-1\""));
        assertTrue(lines[19].contains("\"id\":\"poi-20\""));
        assertEquals(
                "{\"schema_version\":\"1\",\"type\":\"error\",\"command\":\"places.details\","
                        + "\"code\":\"AUTHENTICATION_FAILED\",\"message\":\"upstream exchange failed with status 401\","
                        + "\"retryable\":false,\"requested_requests\":2,\"received_requests\":1}",
                lines[20]);
        assertEquals(List.of(), stderr, "jsonl owns its failure explanation on stdout");
    }

    @Test
    void anUnreadableChunkBodyRendersAsMalformedInEveryParsingMode() {
        PlaceEnrichmentRequest request = new PlaceEnrichmentRequest(
                PlaceEnrichmentRequest.Kind.DETAILS, List.of("poi-a"));
        PaginationService.PagedRun<PlaceEnrichmentResult> run = new PaginationService.PagedRun<>(
                1, List.of(new PagedSearch.Page<>(0, 1, null, resultOf(200, "gateway exploded <html>"))), null);

        Rendered json = render(run, request, machine(OutputMode.JSON, false));
        assertEquals(8, json.exit());
        assertTrue(json.stdoutText().contains("\"code\":\"MALFORMED_RESPONSE\""), json.stdoutText());

        Rendered human = render(run, request, human(false));
        assertEquals(8, human.exit());
        assertEquals("", human.stdoutText());
        assertEquals(1, human.stderr().size());
    }

    @Test
    void aBrokenPipeWhileRenderingTheFinalDocumentIsSilentZeroAndAnyOtherWriteFailureExitsSix() {
        List<String> silent = new ArrayList<>();

        int early = presenter.present(successRun(), REQUEST, machine(OutputMode.JSON, false), document -> {
            throw new UncheckedIOException(new IOException("Broken pipe"));
        }, silent::add);

        assertEquals(0, early, "a consumer that closed the pipe ended the run successfully");
        assertEquals(List.of(), silent, "and nothing is diagnosed");

        List<String> loud = new ArrayList<>();

        int failed = presenter.present(successRun(), REQUEST, machine(OutputMode.JSON, false), document -> {
            throw new UncheckedIOException(new IOException("No space left on device"));
        }, loud::add);

        assertEquals(6, failed, "every other write failure keeps the transport status");
        assertEquals(List.of("places.details: writing the result document failed"), loud, "with one line");
    }

    @Test
    void anUnparseableChunkBodyEndsJsonlInTheCountedMalformedRecordWithTheLatestWindows() {
        PlaceEnrichmentRequest request = new PlaceEnrichmentRequest(
                PlaceEnrichmentRequest.Kind.DETAILS, List.of("poi-a"));
        PaginationService.PagedRun<PlaceEnrichmentResult> run = new PaginationService.PagedRun<>(
                1,
                List.of(new PagedSearch.Page<>(0, 1, null, resultOf(200, "<html>gateway exploded</html>", exhaustedRequestWindow()))),
                null);

        Rendered jsonl = render(run, request, machine(OutputMode.JSONL, false));

        assertEquals(8, jsonl.exit(), "a 2xx chunk that is not JSON is the malformed verdict");
        assertEquals(List.of(), jsonl.stderr(), "jsonl owns its failure explanation on stdout");
        assertEquals(1, jsonl.stdoutText().lines().count(), "the error record ends the stream alone: " + jsonl.stdoutText());
        assertTrue(jsonl.stdoutText().contains("\"type\":\"error\""), jsonl.stdoutText());
        assertTrue(jsonl.stdoutText().contains("\"code\":\"MALFORMED_RESPONSE\""), jsonl.stdoutText());
        assertTrue(
                jsonl.stdoutText().contains("\"requested_requests\":1,\"received_requests\":1"),
                "the counted framing is kept: " + jsonl.stdoutText());
        assertTrue(
                jsonl.stdoutText().contains("\"policy\":\"request\""),
                "the latest completed chunk's windows ride along: " + jsonl.stdoutText());
    }

    @Test
    void aWalkThatFailsOnARateLimitCarriesTheObservedWindowsInItsFailureRecords() {
        // the windows are what tells an agent when a retry can succeed, so neither machine
        // record may drop them
        Outcome.Failure<PlaceEnrichmentResult> rateLimited = new Outcome.Failure<>(
                FailureKind.RATE_LIMITED, "upstream exchange failed with status 429", null, exhaustedRequestWindow(), 429);
        PaginationService.PagedRun<PlaceEnrichmentResult> run = new PaginationService.PagedRun<>(
                2, List.of(new PagedSearch.Page<>(0, 1, null, resultOf(200, CHUNK_ONE_BODY))), rateLimited);

        Rendered json = render(run, requestOfTwentyFiveIds(), machine(OutputMode.JSON, false));
        Rendered jsonl = render(run, requestOfTwentyFiveIds(), machine(OutputMode.JSONL, false));

        assertEquals(5, json.exit(), "the rate-limit kind keeps its exit status");
        assertTrue(json.stdoutText().contains("\"code\":\"RATE_LIMITED\""), json.stdoutText());
        assertTrue(json.stdoutText().contains("\"retryable\":true"), json.stdoutText());
        assertTrue(json.stdoutText().contains("\"policy\":\"request\""), "the envelope keeps the windows: " + json.stdoutText());
        assertEquals(5, jsonl.exit());
        assertTrue(
                jsonl.stdoutText().contains("\"policy\":\"request\""),
                "and so does the jsonl error record: " + jsonl.stdoutText());
    }

    @Test
    void aRefusedPacingWaitKeepsItsTimingDiagnosticAndObservedWindowsAcrossModes() {
        String refusal =
                "the rate-limit window resets in 2592000s, beyond this walk's maximum wait of 60s; retry after the reset";
        RateLimitSnapshot monthly = new RateLimitSnapshot(
                List.of(new RateLimitWindow("month", 2000, 0, Duration.ofSeconds(2592000))), List.of(), Instant.EPOCH);
        PaginationService.PagedRun<PlaceEnrichmentResult> run = new PaginationService.PagedRun<>(
                2,
                List.of(new PagedSearch.Page<>(0, 1, null, resultOf(200, CHUNK_ONE_BODY, monthly))),
                new Outcome.Failure<>(FailureKind.RATE_LIMITED, refusal, null, monthly, 0));

        Rendered human = render(run, requestOfTwentyFiveIds(), human(false));
        assertEquals(5, human.exit());
        assertEquals("", human.stdoutText());
        assertEquals(List.of("places.details: " + refusal + " (1 of 2 requests completed)"), human.stderr());
        assertFalse(human.stderr().getFirst().contains("retry after a short wait"), human.stderr().toString());

        Rendered json = render(run, requestOfTwentyFiveIds(), machine(OutputMode.JSON, false));
        assertEquals(5, json.exit());
        assertEquals(List.of("places.details: " + refusal + " (1 of 2 requests completed)"), json.stderr());
        assertTrue(json.stdoutText().contains("\"code\":\"RATE_LIMITED\""), json.stdoutText());
        assertTrue(json.stdoutText().contains("\"policy\":\"month\""), json.stdoutText());
        assertTrue(json.stdoutText().contains("\"reset_ms\":2592000000"), json.stdoutText());
        assertTrue(
                schemas.validate("envelope-error.schema.json", json.stdout()).isEmpty(),
                "the refused-wait failure envelope satisfies the published error schema: " + json.stdoutText());

        Rendered jsonl = render(run, requestOfTwentyFiveIds(), machine(OutputMode.JSONL, false));
        assertEquals(5, jsonl.exit());
        assertEquals(List.of(), jsonl.stderr(), "jsonl owns its failure explanation on stdout");
        assertTrue(jsonl.stdoutText().contains("\"code\":\"RATE_LIMITED\""), jsonl.stdoutText());
        assertTrue(jsonl.stdoutText().contains("\"policy\":\"month\""), jsonl.stdoutText());
        assertTrue(jsonl.stdoutText().contains("\"reset_ms\":2592000000"), jsonl.stdoutText());
        for (String line : jsonl.stdoutText().stripTrailing().split("\n")) {
            assertTrue(
                    schemas.validateText("jsonl-record.schema.json", line).isEmpty(),
                    "every jsonl record satisfies the published schema: " + line);
        }
    }

    /** One exhausted request window, as the failing chunk's own response headers explained it. */
    private static RateLimitSnapshot exhaustedRequestWindow() {
        return new RateLimitSnapshot(
                List.of(new RateLimitWindow("request", 1, 0, Duration.ofSeconds(2))), List.of(), Instant.EPOCH);
    }

    private Rendered renderSuccess(OutputRequest output) {
        return render(successRun(), REQUEST, output);
    }

    private static PaginationService.PagedRun<PlaceEnrichmentResult> successRun() {
        return new PaginationService.PagedRun<>(
                1, List.of(new PagedSearch.Page<>(0, 1, null, resultOf(200, CHUNK_ONE_BODY))), null);
    }

    /** A 25-id invocation — two chunks — whose second chunk fails authentication. */
    private static PaginationService.PagedRun<PlaceEnrichmentResult> failedAfterFirstChunk() {
        StringBuilder entries = new StringBuilder();
        for (int number = 1; number <= 20; number++) {
            entries.append("{\"id\":\"poi-").append(number).append("\",\"title\":\"POI ").append(number).append("\"},");
        }
        String chunkOne = "{\"results\":[" + entries.substring(0, entries.length() - 1) + "]}";
        return new PaginationService.PagedRun<>(
                2,
                List.of(new PagedSearch.Page<>(0, 1, null, resultOf(200, chunkOne))),
                new Outcome.Failure<>(FailureKind.AUTHENTICATION, "upstream exchange failed with status 401"));
    }

    private static PlaceEnrichmentRequest requestOfTwentyFiveIds() {
        return new PlaceEnrichmentRequest(
                PlaceEnrichmentRequest.Kind.DETAILS,
                java.util.stream.IntStream.rangeClosed(1, 25).mapToObj(number -> "poi-" + number).toList());
    }

    private static PlaceEnrichmentResult resultOf(int status, String body) {
        return resultOf(status, body, null);
    }

    private static PlaceEnrichmentResult resultOf(int status, String body, RateLimitSnapshot rateLimits) {
        return new PlaceEnrichmentResult(
                status, new UpstreamPayload(body.getBytes(UTF_8)), rateLimits, null, null, null);
    }

    private Rendered render(
            PaginationService.PagedRun<PlaceEnrichmentResult> run,
            PlaceEnrichmentRequest request,
            OutputRequest output) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        List<String> stderr = new ArrayList<>();
        int exit = presenter.present(run, request, output, new OutputStreamResultWriter(stdout), stderr::add);
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

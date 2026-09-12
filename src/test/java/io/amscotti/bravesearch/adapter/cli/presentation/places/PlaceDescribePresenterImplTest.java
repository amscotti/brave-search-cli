package io.amscotti.bravesearch.adapter.cli.presentation.places;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings;
import io.amscotti.bravesearch.adapter.cli.presentation.OutputStreamResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.PlaceDescribePresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.application.service.PaginationService;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.output.OutputMode;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.request.PlaceEnrichmentRequest;
import io.amscotti.bravesearch.domain.result.PagedSearch;
import io.amscotti.bravesearch.domain.result.PlaceEnrichmentResult;
import io.amscotti.bravesearch.testsupport.SchemaCatalog;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Byte-exact renderings of one completed places describe walk — the same enrichment
 * channel contract as the detail renderer, carrying the description payload: the human
 * listing heads every block with its id because the payload has no title, and the
 * records carry the description text beside the position, id, and present members.
 */
final class PlaceDescribePresenterImplTest {

    private static final PlaceEnrichmentRequest REQUEST = new PlaceEnrichmentRequest(
            PlaceEnrichmentRequest.Kind.DESCRIPTIONS, List.of("poi-a", "poi-b"));

    private static final String CHUNK_BODY =
            "{\"results\":[{\"id\":\"poi-a\",\"description\":\"An AI-generated summary.\"}]}";

    private final JsonMappers mappers = new JsonMappers();

    private final JsonlCodec jsonl = new JsonlCodec(mappers);

    private final PlaceDescribePresenterImpl presenter = new PlaceDescribePresenterImpl(
            new EnvelopeCodec(mappers),
            jsonl,
            new PlaceDescriptionsExtractor(mappers),
            (mode, diagnostics, results, quiet) ->
                    new ModeAwareWarnings(mode, PlaceDescribePresenter.COMMAND, diagnostics, results, jsonl, quiet));

    private final SchemaCatalog schemas = new SchemaCatalog();

    @Test
    void humanModeHeadsEveryBlockWithItsIdAndClosesWithTheCountsLine() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        List<String> stderr = new java.util.ArrayList<>();

        int exit = presenter.present(
                successRun(), REQUEST, human(false), new OutputStreamResultWriter(stdout), stderr::add);

        assertEquals(0, exit);
        assertEquals(
                "Place descriptions for 2 ids\n"
                        + "\n"
                        + " 1  poi-a\n"
                        + "    An AI-generated summary.\n"
                        + "\n"
                        + " 2  poi-b\n"
                        + "    (not returned)\n"
                        + "2 ids, 1 returned, 1 not returned.\n",
                stdout.toString(UTF_8));
    }

    @Test
    void jsonlRecordsCarryTheDescriptionBesideThePositionAndPresentMembers() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        List<String> stderr = new java.util.ArrayList<>();

        Consumer<PagedSearch.Page<PlaceEnrichmentResult>> observer =
                presenter.chunkObserver(REQUEST, machine(OutputMode.JSONL), new OutputStreamResultWriter(stdout), stderr::add);
        observer.accept(successRun().completedPages().getFirst());
        int exit = presenter.present(
                successRun(), REQUEST, machine(OutputMode.JSONL), new OutputStreamResultWriter(stdout), stderr::add);

        assertEquals(0, exit);
        assertEquals(
                "{\"schema_version\":\"1\",\"type\":\"result\",\"command\":\"places.describe\",\"position\":0,"
                        + "\"bucket\":\"descriptions\",\"id\":\"poi-a\",\"present\":true,"
                        + "\"description\":\"An AI-generated summary.\"}\n"
                        + "{\"schema_version\":\"1\",\"type\":\"result\",\"command\":\"places.describe\","
                        + "\"position\":1,\"bucket\":\"descriptions\",\"id\":\"poi-b\",\"present\":false}\n"
                        + "{\"schema_version\":\"1\",\"type\":\"summary\",\"command\":\"places.describe\","
                        + "\"id_count\":2,\"returned_count\":1,\"missing_count\":1,"
                        + "\"requested_requests\":1,\"received_requests\":1,\"http_status\":200}\n",
                stdout.toString(UTF_8));
        for (String line : stdout.toString(UTF_8).stripTrailing().split("\n")) {
            assertTrue(
                    schemas.validateText("jsonl-record.schema.json", line).isEmpty(),
                    "every JSONL line satisfies the published record schema: " + line);
        }
    }

    @Test
    void jsonModeCarriesTheReconstructedProjection() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        List<String> stderr = new java.util.ArrayList<>();

        int exit =
                presenter.present(successRun(), REQUEST, machine(OutputMode.JSON), new OutputStreamResultWriter(stdout), stderr::add);

        assertEquals(0, exit);
        assertTrue(
                stdout.toString(UTF_8).contains(
                        "\"projection\":{\"id_count\":2,\"returned_count\":1,\"missing_count\":1,"
                                + "\"requested_requests\":1,\"received_requests\":1,\"results\":["
                                + "{\"position\":0,\"id\":\"poi-a\",\"present\":true,"
                                + "\"description\":\"An AI-generated summary.\"},{\"position\":1,\"id\":\"poi-b\",\"present\":false}]}"),
                stdout.toString(UTF_8));
        assertTrue(
                schemas.validate("envelope-success.schema.json", stdout.toByteArray()).isEmpty(),
                "the envelope satisfies the published success schema");
    }

    private static PaginationService.PagedRun<PlaceEnrichmentResult> successRun() {
        return new PaginationService.PagedRun<>(
                1,
                List.of(new PagedSearch.Page<>(
                        0, 1, null, new PlaceEnrichmentResult(200, new UpstreamPayload(CHUNK_BODY.getBytes(UTF_8)), null, null, null, null))),
                null);
    }

    private static OutputRequest machine(OutputMode mode) {
        return new OutputRequest(true, mode, false, false, false);
    }

    private static OutputRequest human(boolean quiet) {
        return new OutputRequest(false, OutputMode.HUMAN, false, false, quiet);
    }
}

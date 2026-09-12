package io.amscotti.bravesearch.adapter.cli.presentation.places;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.presentation.OutputStreamResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.RawCodec;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.output.OutputMode;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.request.GeoLoc;
import io.amscotti.bravesearch.domain.request.PlaceAnchor;
import io.amscotti.bravesearch.domain.request.PlaceSearchRequest;
import io.amscotti.bravesearch.domain.result.PlaceSearchResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Rendering shapes of the places presenter: the human listing heads with the mode's
 * own heading — {@code Places for:} a query, {@code Places near:} an anchor, {@code
 * Places everywhere} for the query-less anchor-less broad global search — numbers the
 * places of every bucket in one listing, and appends the ranking-bias note whenever a
 * radius was requested, because the radius is not a hard boundary. The JSONL stream
 * carries each entry's own bucket word — the per-entry bucket seam — and the JSON
 * projection carries each entry's bucket beside its position.
 */
final class PlacesSearchPresenterImplTest {

    private final JsonMappers mappers = new JsonMappers();

    private final PlacesSearchPresenterImpl presenter = new PlacesSearchPresenterImpl(
            new EnvelopeCodec(mappers),
            new JsonlCodec(mappers),
            new RawCodec(),
            new PlacesProjectionExtractor(mappers),
            (mode, diagnostics, results, quiet) ->
                    new io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings(
                            mode, "places.search", diagnostics, results, new JsonlCodec(mappers), quiet));

    @Test
    void humanModeNumbersThePlacesOfEveryBucketUnderTheQueryHeading() {
        String human = render(success(), PlaceSearchRequest.builder().query("three word query").build(), humanMode());

        assertEquals(
                "Places for: three word query\n"
                        + "\n"
                        + " 1  First Place\n"
                        + "    1 Main St, Philadelphia PA\n"
                        + "    +1 215 555 0100\n"
                        + "    https://example.com/first\n"
                        + "    Rating: 4.5 (1212 reviews)\n"
                        + "    Distance: 1.2 km\n"
                        + "\n"
                        + " 2  Second Place\n"
                        + "    2 Main St, Philadelphia PA\n"
                        + "    Rating: 4 (7 reviews)\n"
                        + "\n"
                        + " 3  (no title)\n"
                        + "\n"
                        + " 4  Philadelphia\n"
                        + "    Pennsylvania, United States\n"
                        + "\n"
                        + " 5  1200 Market St\n"
                        + "5 places.\n",
                human);
    }

    @Test
    void exploreAndBroadGlobalHeadingsNameTheirMode() {
        String explore = render(
                success(),
                PlaceSearchRequest.builder().anchor(new PlaceAnchor.LocationName("Philadelphia PA US")).build(),
                humanMode());
        assertTrue(explore.startsWith("Places near: Philadelphia PA US\n"), () -> explore);

        String coordinates = render(
                success(),
                PlaceSearchRequest.builder().anchor(new PlaceAnchor.Coordinates(40.69, -74.25)).build(),
                humanMode());
        assertTrue(coordinates.startsWith("Places near: 40.69, -74.25\n"), () -> coordinates);

        String geoloc = render(
                success(),
                PlaceSearchRequest.builder()
                        .geoloc(new GeoLoc(new BigDecimal("40.690"), new BigDecimal("-74.250")))
                        .build(),
                humanMode());
        assertTrue(geoloc.startsWith("Places near: 40.690x-74.250\n"), () -> geoloc);

        String broad = render(success(), PlaceSearchRequest.builder().build(), humanMode());
        assertTrue(broad.startsWith("Places everywhere\n"), () -> broad);
    }

    @Test
    void aRequestedRadiusAppendsTheRankingBiasNoteAndItsAbsenceOmitsIt() {
        String withRadius = render(
                success(),
                PlaceSearchRequest.builder().query("q").radius(new BigDecimal("1500")).build(),
                humanMode());

        assertTrue(
                withRadius.endsWith("Radius biases ranking; it is not a hard boundary.\n"),
                "the note follows the count line: " + withRadius);

        String withoutRadius = render(success(), PlaceSearchRequest.builder().query("q").build(), humanMode());
        assertTrue(!withoutRadius.contains("Radius biases"), () -> withoutRadius);
    }

    @Test
    void anEmptyListingRendersTheSingleNoPlacesLine() {
        String human = render(emptySuccess(), PlaceSearchRequest.builder().build(), humanMode());

        assertEquals("No places.\n", human);
    }

    @Test
    void jsonlModeLabelsEveryRecordWithItsOwnBucketAndBucketLocalPosition() {
        String jsonl = render(success(), PlaceSearchRequest.builder().query("three word query").build(), machine(OutputMode.JSONL));

        String[] lines = jsonl.split("\n", -1);
        assertEquals(7, lines.length, "five result records, one summary, one terminator");
        assertEquals(
                "{\"schema_version\":\"1\",\"type\":\"result\",\"command\":\"places.search\",\"position\":0,"
                        + "\"bucket\":\"results\",\"id\":\"place-first\",\"title\":\"First Place\","
                        + "\"address\":\"1 Main St, Philadelphia PA\",\"rating_value\":\"4.5\","
                        + "\"rating_count\":\"1212\",\"distance\":\"1.2 km\",\"phone\":\"+1 215 555 0100\","
                        + "\"website\":\"https://example.com/first\"}",
                lines[0]);
        assertEquals(
                "{\"schema_version\":\"1\",\"type\":\"result\",\"command\":\"places.search\",\"position\":1,"
                        + "\"bucket\":\"results\",\"id\":\"place-second\",\"title\":\"Second Place\","
                        + "\"address\":\"2 Main St, Philadelphia PA\",\"rating_value\":\"4\",\"rating_count\":\"7\"}",
                lines[1]);
        assertEquals(
                "{\"schema_version\":\"1\",\"type\":\"result\",\"command\":\"places.search\",\"position\":2,"
                        + "\"bucket\":\"results\"}",
                lines[2],
                "an entry with no usable members keeps its frame, never a placeholder");
        assertEquals(
                "{\"schema_version\":\"1\",\"type\":\"result\",\"command\":\"places.search\",\"position\":0,"
                        + "\"bucket\":\"cities\",\"id\":\"city-philadelphia\",\"title\":\"Philadelphia\","
                        + "\"address\":\"Pennsylvania, United States\"}",
                lines[3],
                "the city entry counts positions inside its own bucket");
        assertEquals(
                "{\"schema_version\":\"1\",\"type\":\"result\",\"command\":\"places.search\",\"position\":0,"
                        + "\"bucket\":\"addresses\",\"id\":\"addr-market\",\"title\":\"1200 Market St\"}",
                lines[4],
                "the address entry counts positions inside its own bucket");
        assertEquals(
                "{\"schema_version\":\"1\",\"type\":\"summary\",\"command\":\"places.search\",\"result_count\":5,"
                        + "\"page\":1,\"upstream_offset\":0,\"http_status\":200,\"request_id\":\"req-7f3a2b\","
                        + "\"api_version\":\"2024-08-01\"}",
                lines[5]);
    }

    @Test
    void jsonProjectionCarriesEachEntryBucketBesideItsPosition() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        presenter.present(
                success(),
                PlaceSearchRequest.builder().query("three word query").build(),
                machine(OutputMode.JSON),
                new OutputStreamResultWriter(stdout),
                diagnostic -> {});
        String envelope = stdout.toString(UTF_8);

        assertTrue(envelope.contains("\"result_count\":5"), () -> envelope);
        assertTrue(envelope.contains("\"bucket\":\"cities\""), "the projection exposes the mixed buckets: " + envelope);
        assertTrue(
                envelope.contains("0.1000000000000000000001"), "the spot decimal must survive at exact scale");
    }

    private static OutputRequest humanMode() {
        return new OutputRequest(false, OutputMode.HUMAN, false, false, false);
    }

    private static OutputRequest machine(OutputMode mode) {
        return new OutputRequest(true, mode, false, false, false);
    }

    private String render(Outcome<PlaceSearchResult> outcome, PlaceSearchRequest request, OutputRequest output) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        presenter.present(outcome, request, output, new OutputStreamResultWriter(stdout), diagnostic -> {});
        return stdout.toString(UTF_8);
    }

    private static Outcome.Success<PlaceSearchResult> success() {
        return new Outcome.Success<>(
                new PlaceSearchResult(
                        200,
                        new UpstreamPayload(fixture("full-results.json")),
                        RateLimitSnapshot.empty(),
                        null,
                        "req-7f3a2b",
                        "2024-08-01"));
    }

    private static Outcome.Success<PlaceSearchResult> emptySuccess() {
        return new Outcome.Success<>(
                new PlaceSearchResult(
                        200, new UpstreamPayload(fixture("zero-results.json")), RateLimitSnapshot.empty(), null, null, null));
    }

    private static byte[] fixture(String name) {
        String resource = "/fixtures/brave/places/" + name;
        try (InputStream bytes = PlacesSearchPresenterImplTest.class.getResourceAsStream(resource)) {
            if (bytes == null) {
                throw new IllegalStateException("missing fixture " + resource);
            }
            return bytes.readAllBytes();
        } catch (IOException missing) {
            throw new UncheckedIOException(missing);
        }
    }
}

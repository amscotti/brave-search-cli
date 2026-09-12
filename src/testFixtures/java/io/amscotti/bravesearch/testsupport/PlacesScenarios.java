package io.amscotti.bravesearch.testsupport;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;

/**
 * The places search vertical-slice scenarios, run against a whole CLI process and a
 * scripted loopback server. Each scenario takes the command prefix that launches the
 * places search command — the installed JVM launcher plus the group and subcommand
 * tokens, or the native binary plus the same tokens — so both executables must show
 * identical evidence: numbered LF-only human output over every response bucket, one
 * schema-valid JSON success envelope whose places members stay exact, JSONL result
 * records — each labeled with its own response bucket and its position inside that
 * bucket — followed by one summary record, raw stdout byte-identical to the served
 * body, the authentication failure's exit status, usage rejections that predate any
 * network dispatch — the anchor rules included: the coordinate pair, its conflict
 * with the place name, both ranges, the geoloc spelling and its component ranges,
 * the radius rule, the count budget, and the header-safety boundary of the place
 * name — the explore and broad-global query-less spellings that dispatch happily,
 * the loopback test-key routing with the exact anchor wire form, the ranking-bias
 * note a requested radius appends, a downstream pipe close, and the Api-Version pin.
 *
 * <p>The harness plumbing and the endpoint-generic scenario bodies live once in {@link
 * SearchScenarios}; this class carries the places fixture bodies and the
 * places-specific evidence: the mode headings, the per-entry buckets of the records,
 * and the single-request wire path of a non-paginated endpoint.
 */
public final class PlacesScenarios extends SearchScenarios {

    /** The served three-place success body across two buckets, with exact-scale decimals. */
    private static final byte[] PLACES_RESULTS = """
            {
              "type": "places",
              "query": {
                "original": "three word query"
              },
              "results": [
                {
                  "id": "place-first",
                  "title": "First Place",
                  "address": "1 Main St, Philadelphia PA",
                  "rating": {
                    "rating_value": 4.5,
                    "rating_count": 1212
                  },
                  "distance": "1.2 km",
                  "phone": "+1 215 555 0100",
                  "website": "https://example.com/first",
                  "unknown_result_field": {
                    "score": 0.125
                  }
                },
                {
                  "id": "place-second",
                  "title": "Second Place",
                  "address": "2 Main St, Philadelphia PA",
                  "rating": {
                    "rating_value": 4,
                    "rating_count": 7
                  }
                }
              ],
              "cities": [
                {
                  "id": "city-philadelphia",
                  "title": "Philadelphia",
                  "address": "Pennsylvania, United States"
                }
              ],
              "countries": null,
              "regions": [],
              "mixed": [
                {
                  "unknown_ordering_hint": true
                }
              ],
              "unknown_future_block": {
                "cost": 1.10,
                "long": 0.1000000000000000000001
              }
            }
            """
            .getBytes(UTF_8);

    /** The exact human document the three-place body renders, every line LF-terminated. */
    private static final String EXPECTED_HUMAN = """
            Places for: three word query

             1  First Place
                1 Main St, Philadelphia PA
                +1 215 555 0100
                https://example.com/first
                Rating: 4.5 (1212 reviews)
                Distance: 1.2 km

             2  Second Place
                2 Main St, Philadelphia PA
                Rating: 4 (7 reviews)

             3  Philadelphia
                Pennsylvania, United States
            3 places.
            quota: 0 of 1 remaining (window resets in 1s)
            """;

    private static final String SANDBOX_PREFIX = "brave-places-process-";

    private PlacesScenarios() {}

    /** A 200 fixture exchange renders the numbered human listing, LF-only, without any ANSI byte. */
    public static void humanHappyPathRendersNumberedLfLinesWithoutAnsi(List<String> placesCommand) throws Exception {
        humanHappyPathRendersNumberedLfLinesWithoutAnsi(placesCommand, fixtureServer(), EXPECTED_HUMAN, SANDBOX_PREFIX);
    }

    /** JSON mode emits exactly one LF-terminated success envelope that validates and stays lossless. */
    public static void jsonSuccessEnvelopeIsOneValidatedLfLine(List<String> placesCommand) throws Exception {
        jsonSuccessEnvelopeIsOneValidatedLfLine(
                placesCommand,
                fixtureServer(),
                "places.search",
                "First Place",
                PlacesScenarios::assertPlacesEnvelopeMembers,
                SANDBOX_PREFIX);
    }

    /**
     * JSONL mode emits one validated record per logical place and exactly one summary
     * record after them, each record labeled with its own response bucket and its
     * position inside that bucket's array.
     */
    public static void jsonlEmitsValidatedResultRecordsThenSummary(List<String> placesCommand) throws Exception {
        String testKey = freshToken();
        SchemaCatalog schemas = new SchemaCatalog();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = fixtureServer().start()) {
            ProcessHarness.ProcessResult result = run(
                    placesCommand, server, childEnvironment(sandbox.directory, testKey), "--output", "jsonl", "three word query");

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            String[] lines = new String(result.stdout(), UTF_8).split("\n", -1);
            assertEquals(5, lines.length, "three result records, one summary record, one terminator");

            JsonNode first = READER.readTree(lines[0]);
            assertEquals("result", first.path("type").asText());
            assertEquals("places.search", first.path("command").asText());
            assertEquals("results", first.path("bucket").asText());
            assertEquals(0, first.path("position").asInt());
            assertEquals("place-first", first.path("id").asText());
            assertEquals("4.5", first.path("rating_value").asText());
            JsonNode second = READER.readTree(lines[1]);
            assertEquals("results", second.path("bucket").asText());
            assertEquals(1, second.path("position").asInt());
            JsonNode city = READER.readTree(lines[2]);
            assertEquals("cities", city.path("bucket").asText(), "the mixed buckets stay labeled per entry");
            assertEquals(0, city.path("position").asInt(), "the position counts inside the entry's own bucket");
            assertEquals("Philadelphia", city.path("title").asText());

            for (int index = 0; index < 4; index++) {
                final int record = index;
                final String line = lines[record];
                final String schema = record < 3 ? "places-result.schema.json" : "places-summary.schema.json";
                assertTrue(
                        schemas.validateText(schema, line).isEmpty(),
                        () -> "record " + record + " must satisfy " + schema + ": " + line);
                assertTrue(
                        schemas.validateText("jsonl-record.schema.json", line).isEmpty(),
                        () -> "record " + record + " must satisfy jsonl-record.schema.json: " + line);
            }
            JsonNode summary = READER.readTree(lines[3]);
            assertEquals(3, summary.path("result_count").asInt());
            assertEquals(1, summary.path("page").asInt());
            assertEquals(0, summary.path("upstream_offset").asInt());
            assertEquals(200, summary.path("http_status").asInt());
            assertEquals(REQUEST_ID, summary.path("request_id").asText());
            assertEquals(RESPONSE_API_VERSION, summary.path("api_version").asText());
            assertEquals("", new String(result.stderr(), UTF_8), () -> describe(result, testKey));
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /** Raw mode writes the served body bytes to stdout byte-identically, adding and removing nothing. */
    public static void rawOutputIsTheServedBodyBytesExactly(List<String> placesCommand) throws Exception {
        rawOutputIsTheServedBodyBytesExactly(placesCommand, fixtureServer(), PLACES_RESULTS, SANDBOX_PREFIX);
    }

    /** A 401 keeps the authentication exit: one diagnostic line in human mode, one failure envelope in JSON mode. */
    public static void authenticationFailureExitsFourInHumanAndJsonModes(List<String> placesCommand) throws Exception {
        authenticationFailureExitsFourInHumanAndJsonModes(placesCommand, "places.search", SANDBOX_PREFIX);
    }

    /**
     * Every anchor, geoloc, radius, count, and header-safety rule of the places grammar
     * is rejected with the usage exit before the server can observe any request, and
     * the shared spellings the places endpoint leaves undocumented are refused.
     */
    public static void usageFailureHappensBeforeAnyNetworkDispatch(List<String> placesCommand) throws Exception {
        usageFailureHappensBeforeAnyNetworkDispatch(
                placesCommand, fixtureServer(), "latitude and longitude must be supplied together", SANDBOX_PREFIX,
                "--latitude", "40.69", "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                placesCommand, fixtureServer(), "latitude and longitude must be supplied together", SANDBOX_PREFIX,
                "--longitude", "-74.25", "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                placesCommand, fixtureServer(), "latitude and longitude cannot be combined with location", SANDBOX_PREFIX,
                "--latitude", "40.69", "--longitude", "-74.25", "--location", "Philadelphia", "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                placesCommand, fixtureServer(), "latitude must be between -90 and 90", SANDBOX_PREFIX,
                "--latitude", "91", "--longitude", "0", "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                placesCommand, fixtureServer(), "longitude must be between -180 and 180", SANDBOX_PREFIX,
                "--latitude", "0", "--longitude", "181", "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                placesCommand, fixtureServer(), "geoloc must be two decimal coordinates spelled latitudexlongitude",
                SANDBOX_PREFIX, "--geoloc", "40.69", "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                placesCommand, fixtureServer(), "geoloc latitude must be between -90 and 90", SANDBOX_PREFIX,
                "--geoloc", "91x0", "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                placesCommand, fixtureServer(), "geoloc longitude must be between -180 and 180", SANDBOX_PREFIX,
                "--geoloc", "0x181", "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                placesCommand, fixtureServer(), "Invalid value for option '--radius'", SANDBOX_PREFIX,
                "--radius", "NaN", "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                placesCommand, fixtureServer(), "radius must be zero or greater", SANDBOX_PREFIX,
                "--radius", "-1", "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                placesCommand, fixtureServer(), "count must be between 1 and 100", SANDBOX_PREFIX,
                "--count", "101", "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                placesCommand, fixtureServer(), "location must not carry control characters", SANDBOX_PREFIX,
                "--location", "Phil\nadelphia", "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                placesCommand, fixtureServer(), "location must not begin or end with whitespace", SANDBOX_PREFIX,
                "--location", " Philadelphia", "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                placesCommand, fixtureServer(), "--freshness is not accepted by this command", SANDBOX_PREFIX,
                "--freshness", "pd", "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                placesCommand, fixtureServer(), "--page is not accepted by this command", SANDBOX_PREFIX,
                "--page", "2", "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                placesCommand, fixtureServer(), "Unknown option", SANDBOX_PREFIX, "--all-pages", "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                placesCommand, fixtureServer(), "Unknown option", SANDBOX_PREFIX, "--goggle", "https://example.com/g", "q");
    }

    /**
     * The explore spelling — a place-name anchor without any query — dispatches exactly
     * one GET whose wire carries the anchor and no {@code q} at all.
     */
    public static void exploreModeDispatchesTheAnchorWithoutAnyQuery(List<String> placesCommand) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = fixtureServer().start()) {
            ProcessHarness.ProcessResult result =
                    run(placesCommand, server, childEnvironment(sandbox.directory, testKey), "--location", "Philadelphia PA US");

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            assertTrue(
                    new String(result.stdout(), UTF_8).startsWith("Places near: Philadelphia PA US\n"),
                    () -> "the explore heading names the anchor: " + describe(result, testKey));
            List<ScriptedSseServer.RecordedRequest> requests = server.requests();
            assertEquals(1, requests.size(), "a place search is exactly one request");
            assertEquals(
                    "/local/place_search?location=Philadelphia%20PA%20US",
                    requests.getFirst().path(),
                    "the explore request carries the anchor and never a q");
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /**
     * The broad-global spelling — neither query nor anchor — is a valid search that is
     * never rejected locally: it dispatches exactly one GET over the bare endpoint
     * path.
     */
    public static void broadGlobalModeDispatchesWithoutQueryOrAnchor(List<String> placesCommand) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = fixtureServer().start()) {
            ProcessHarness.ProcessResult result = run(placesCommand, server, childEnvironment(sandbox.directory, testKey));

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            assertTrue(
                    new String(result.stdout(), UTF_8).startsWith("Places everywhere\n"),
                    () -> "the broad-global heading names its mode: " + describe(result, testKey));
            List<ScriptedSseServer.RecordedRequest> requests = server.requests();
            assertEquals(1, requests.size());
            assertEquals("/local/place_search", requests.getFirst().path(), "the broad global search carries no parameter at all");
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /**
     * A loopback run authenticates with the test key, never forwards a stored
     * credential, and dispatches exactly one GET whose wire form carries every anchor
     * spelling exactly as documented — the coordinate pair, the geoloc hint as
     * {@code latitudexlongitude} with its given scale, and the radius at its given
     * scale.
     */
    public static void loopbackRunSendsTheTestKeyWithTheExactAnchorWireForm(List<String> placesCommand)
            throws Exception {
        String testKey = freshToken();
        String storedDecoy = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = fixtureServer().start()) {
            java.util.Map<String, String> environment = childEnvironment(sandbox.directory, testKey);
            environment.put("BRAVE_API_KEY", storedDecoy);
            ProcessHarness.ProcessResult result = run(
                    placesCommand,
                    server,
                    environment,
                    "--latitude",
                    "40.69",
                    "--longitude",
                    "-74.25",
                    "--geoloc",
                    "40.690x-74.250",
                    "--radius",
                    "1500.500",
                    "--count",
                    "50",
                    "three word query");

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey, storedDecoy));
            assertTrue(
                    new String(result.stdout(), UTF_8).startsWith("Places for: "),
                    () -> "the exchange must have succeeded: " + describe(result, testKey, storedDecoy));

            List<ScriptedSseServer.RecordedRequest> requests = server.requests();
            assertEquals(1, requests.size(), "a place search is exactly one request, never a walk");
            assertEquals(
                    "/local/place_search?count=50&geoloc=40.690x-74.250&latitude=40.69"
                            + "&longitude=-74.25&q=three%20word%20query&radius=1500.500",
                    requests.getFirst().path(),
                    "every anchor spelling rides the wire exactly as documented");
            assertEquals(
                    Optional.of(testKey),
                    requests.getFirst().firstHeader("X-Subscription-Token"),
                    "the token on the wire must be the loopback test key");
            for (List<String> values : requests.getFirst().headers().values()) {
                for (String value : values) {
                    assertFalse(value.contains(storedDecoy), "the stored credential must never reach a loopback peer");
                }
            }
            assertTokenMaterialAbsent(result, testKey, storedDecoy);
        }
    }

    /** Without the loopback test key the run keeps the local-configuration exit and dispatches nothing. */
    public static void missingLoopbackTestKeyKeepsTheLocalConfigurationExit(List<String> placesCommand)
            throws Exception {
        missingLoopbackTestKeyKeepsTheLocalConfigurationExit(placesCommand, fixtureServer(), SANDBOX_PREFIX);
    }

    /** A requested radius appends the ranking-bias note to the human listing, because it is not a hard boundary. */
    public static void requestedRadiusAppendsTheRankingBiasNote(List<String> placesCommand) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = fixtureServer().start()) {
            ProcessHarness.ProcessResult result =
                    run(placesCommand, server, childEnvironment(sandbox.directory, testKey), "--radius", "1500", "three word query");

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            String stdout = new String(result.stdout(), UTF_8);
            assertTrue(stdout.contains("3 places.\n"), () -> "the listing stands: " + describe(result, testKey));
            assertTrue(
                    stdout.endsWith("Radius biases ranking; it is not a hard boundary.\n"),
                    "the note follows the count line: " + describe(result, testKey));
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /** A downstream consumer closing the pipe early on the single places exchange is silent success: exit 0. */
    public static void downstreamPipeCloseIsSilentZero(List<String> placesCommand) throws Exception {
        downstreamPipeCloseIsSilentZero(placesCommand, oversizedPlacesResults(), SANDBOX_PREFIX);
    }

    /** The Api-Version pin travels verbatim on the wire; an impossible calendar date never leaves the process. */
    public static void apiVersionPinReachesTheWireAndImpossibleDatesFailUsage(List<String> placesCommand)
            throws Exception {
        apiVersionPinReachesTheWireAndImpossibleDatesFailUsage(placesCommand, fixtureServer(), SANDBOX_PREFIX);
    }

    /** The places members of the success envelope: the per-entry buckets of the projection's results. */
    private static void assertPlacesEnvelopeMembers(JsonNode envelope) {
        JsonNode projection = envelope.path("data").path("projection");
        assertEquals("results", projection.path("results").get(0).path("bucket").asText());
        assertEquals("place-first", projection.path("results").get(0).path("id").asText());
        assertEquals(
                "cities", projection.path("results").get(2).path("bucket").asText(), "the projection exposes the mixed buckets");
        assertEquals(
                "city-philadelphia",
                projection.path("results").get(2).path("id").asText(),
                "the city entry carries its id");
        JsonNode meta = envelope.path("meta");
        assertEquals(REQUEST_ID, meta.path("request_id").asText());
        assertEquals(200, meta.path("http_status").asInt());
        assertEquals(RESPONSE_API_VERSION, meta.path("api_version").asText());
    }

    /** A success body far larger than any pipe buffer, so a writer must block mid-body. */
    private static byte[] oversizedPlacesResults() {
        return ("{\"type\":\"places\",\"query\":{\"original\":\"three word query\"},\"results\":[{"
                        + "\"id\":\"place-big\",\"title\":\"Big\",\"address\":\""
                        + "x".repeat(256 * 1024)
                        + "\"}]}")
                .getBytes(UTF_8);
    }

    /** The fixture exchange every success scenario serves: three places across two buckets, quota windows, identifiers. */
    private static ScriptedSseServer.Builder fixtureServer() {
        return ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .header("X-RateLimit-Limit", "1,15")
                .header("X-RateLimit-Policy", "request,minute")
                .header("X-RateLimit-Remaining", "0,14")
                .header("X-RateLimit-Reset", "1,42")
                .header("X-Request-ID", REQUEST_ID)
                .header("Api-Version", RESPONSE_API_VERSION)
                .writeBytes(PLACES_RESULTS);
    }
}

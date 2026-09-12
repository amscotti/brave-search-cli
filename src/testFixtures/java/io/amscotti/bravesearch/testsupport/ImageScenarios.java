package io.amscotti.bravesearch.testsupport;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;

/**
 * The images search vertical-slice scenarios, run against a whole CLI process and a
 * scripted loopback server. Each scenario takes the command prefix that launches the
 * images command — the installed JVM launcher plus the subcommand token, or the native
 * binary plus the same token — so both executables must show identical evidence: numbered
 * LF-only human output with the url/image/thumbnail lines, exactly one schema-valid JSON
 * success envelope whose upstream stays lossless, JSONL result records followed by one
 * summary record each independently schema-valid, raw stdout byte-identical to the served
 * body, the authentication failure's exit status, usage rejections that predate any
 * network dispatch — the images-specific ones included: the count bound of 200, the
 * moderate SafeSearch level the endpoint does not document, the shared spellings images
 * leaves undocumented, and every page spelling, because the endpoint is non-paginated —
 * the loopback test-key routing that dispatches exactly one GET and never forwards a
 * stored credential, and the Api-Version pin.
 *
 * <p>The harness plumbing and the endpoint-generic scenario bodies live once in {@link
 * SearchScenarios}; this class carries the images fixture bodies and the images-specific
 * evidence: the images heading, the image members of the projection and records, the
 * images bucket, and the single-request wire path of a non-paginated endpoint.
 */
public final class ImageScenarios extends SearchScenarios {

    /** The served three-result success body, including unknown blocks and exact-scale decimals. */
    private static final byte[] IMAGES_RESULTS = """
            {
              "type": "images",
              "query": {
                "original": "three word query"
              },
              "results": [
                {
                  "type": "image_result",
                  "title": "First Image",
                  "url": "https://example.com/first",
                  "image": "https://images.example.com/first-full.jpg",
                  "thumbnail": "https://images.example.com/first-thumb.jpg",
                  "dimension": {
                    "width": 1200,
                    "height": 800
                  },
                  "unknown_result_field": {
                    "score": 0.125
                  }
                },
                {
                  "type": "image_result",
                  "title": "Second Image",
                  "url": "https://example.com/second",
                  "image": "https://images.example.com/second-full.jpg"
                },
                {
                  "type": "image_result",
                  "title": 42,
                  "url": "https://example.com/third",
                  "image": "https://images.example.com/third-full.jpg"
                }
              ],
              "extra": {
                "might_be_offensive": false
              },
              "unknown_future_block": {
                "cost": 1.10,
                "long": 0.1000000000000000000001
              }
            }
            """
            .getBytes(UTF_8);

    /** The exact human document the three-result body renders, every line LF-terminated. */
    private static final String EXPECTED_HUMAN = """
            Images results for: three word query

             1  First Image
                https://example.com/first
                https://images.example.com/first-full.jpg
                https://images.example.com/first-thumb.jpg

             2  Second Image
                https://example.com/second
                https://images.example.com/second-full.jpg

             3  (no title)
                https://example.com/third
                https://images.example.com/third-full.jpg
            3 results.
            quota: 0 of 1 remaining (window resets in 1s)
            """;

    private static final String SANDBOX_PREFIX = "brave-images-process-";

    private ImageScenarios() {}

    /** A 200 fixture exchange renders the numbered human listing, LF-only, without any ANSI byte. */
    public static void humanHappyPathRendersNumberedLfLinesWithoutAnsi(List<String> imagesCommand) throws Exception {
        humanHappyPathRendersNumberedLfLinesWithoutAnsi(imagesCommand, fixtureServer(), EXPECTED_HUMAN, SANDBOX_PREFIX);
    }

    /** JSON mode emits exactly one LF-terminated success envelope that validates and stays lossless. */
    public static void jsonSuccessEnvelopeIsOneValidatedLfLine(List<String> imagesCommand) throws Exception {
        jsonSuccessEnvelopeIsOneValidatedLfLine(
                imagesCommand,
                fixtureServer(),
                "images",
                "First Image",
                ImageScenarios::assertImagesEnvelopeMembers,
                SANDBOX_PREFIX);
    }

    /** JSONL mode emits one validated record per logical result and exactly one summary record after them. */
    public static void jsonlEmitsValidatedResultRecordsThenSummary(List<String> imagesCommand) throws Exception {
        jsonlEmitsValidatedResultRecordsThenSummary(
                imagesCommand,
                fixtureServer(),
                "images",
                "images",
                records -> {
                    assertEquals("First Image", records.get(0).path("title").asText());
                    assertEquals(
                            "https://images.example.com/first-full.jpg", records.get(0).path("image").asText());
                    assertEquals(
                            "https://images.example.com/first-thumb.jpg",
                            records.get(0).path("thumbnail").asText());
                    assertTrue(
                            records.get(1).path("thumbnail").isMissingNode(),
                            "an absent upstream member is omitted, never an empty placeholder");
                },
                SANDBOX_PREFIX);
    }

    /** Raw mode writes the served body bytes to stdout byte-identically, adding and removing nothing. */
    public static void rawOutputIsTheServedBodyBytesExactly(List<String> imagesCommand) throws Exception {
        rawOutputIsTheServedBodyBytesExactly(imagesCommand, fixtureServer(), IMAGES_RESULTS, SANDBOX_PREFIX);
    }

    /** A 401 keeps the authentication exit: one diagnostic line in human mode, one failure envelope in JSON mode. */
    public static void authenticationFailureExitsFourInHumanAndJsonModes(List<String> imagesCommand) throws Exception {
        authenticationFailureExitsFourInHumanAndJsonModes(imagesCommand, "images", SANDBOX_PREFIX);
    }

    /**
     * A usage-invalid count, the moderate SafeSearch level the images endpoint does not
     * document, the shared spellings images leaves undocumented, the page spelling, and
     * the goggle family — the images endpoint documents no Goggles — are all rejected
     * with the usage exit before the server can observe any request.
     */
    public static void usageFailureHappensBeforeAnyNetworkDispatch(List<String> imagesCommand) throws Exception {
        usageFailureHappensBeforeAnyNetworkDispatch(
                imagesCommand, fixtureServer(), "Usage:", SANDBOX_PREFIX, "--count", "201", "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                imagesCommand,
                fixtureServer(),
                "safe-search must be off or strict for images",
                SANDBOX_PREFIX,
                "--safe-search",
                "moderate",
                "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                imagesCommand,
                fixtureServer(),
                "--page is not accepted by this command",
                SANDBOX_PREFIX,
                "--page",
                "2",
                "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                imagesCommand,
                fixtureServer(),
                "--ui-lang is not accepted by this command",
                SANDBOX_PREFIX,
                "--ui-lang",
                "en",
                "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                imagesCommand,
                fixtureServer(),
                "--freshness is not accepted by this command",
                SANDBOX_PREFIX,
                "--freshness",
                "pd",
                "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                imagesCommand, fixtureServer(), "Unknown option", SANDBOX_PREFIX, "--all-pages", "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                imagesCommand,
                fixtureServer(),
                "Unknown option",
                SANDBOX_PREFIX,
                "--max-pages",
                "3",
                "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                imagesCommand, fixtureServer(), "Unknown option", SANDBOX_PREFIX, "--goggle", "https://example.com/g", "q");
    }

    /**
     * A loopback run authenticates with the test key, never forwards a stored credential,
     * and dispatches exactly one GET — the single non-paginated exchange the endpoint
     * defines, carrying only the query by default.
     */
    public static void loopbackRunSendsTheTestKeyAndExactlyOneRequest(List<String> imagesCommand) throws Exception {
        String testKey = freshToken();
        String storedDecoy = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = fixtureServer().start()) {
            java.util.Map<String, String> environment = childEnvironment(sandbox.directory, testKey);
            environment.put("BRAVE_API_KEY", storedDecoy);
            ProcessHarness.ProcessResult result = run(imagesCommand, server, environment, "three word query");

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey, storedDecoy));
            assertTrue(
                    new String(result.stdout(), UTF_8).startsWith("Images results for: "),
                    () -> "the exchange must have succeeded: " + describe(result, testKey, storedDecoy));

            List<ScriptedSseServer.RecordedRequest> requests = server.requests();
            assertEquals(1, requests.size(), "an images search is exactly one request, never a walk");
            assertEquals(
                    "/images/search?q=three%20word%20query",
                    requests.getFirst().path(),
                    "the one request carries only the query by default");
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
    public static void missingLoopbackTestKeyKeepsTheLocalConfigurationExit(List<String> imagesCommand)
            throws Exception {
        missingLoopbackTestKeyKeepsTheLocalConfigurationExit(imagesCommand, fixtureServer(), SANDBOX_PREFIX);
    }

    /** A downstream consumer closing the pipe early on the single images exchange is silent success: exit 0. */
    public static void downstreamPipeCloseIsSilentZero(List<String> imagesCommand) throws Exception {
        downstreamPipeCloseIsSilentZero(imagesCommand, oversizedImagesResults(), SANDBOX_PREFIX);
    }

    /** A success body far larger than any pipe buffer, so a writer must block mid-body. */
    private static byte[] oversizedImagesResults() {
        return ("{\"type\":\"images\",\"query\":{\"original\":\"three word query\"},\"results\":[{"
                        + "\"type\":\"image_result\",\"title\":\"Big\",\"url\":\"https://example.com/big\","
                        + "\"image\":\"https://images.example.com/"
                        + "x".repeat(256 * 1024)
                        + ".jpg\"}]}")
                .getBytes(UTF_8);
    }

    /** The Api-Version pin travels verbatim on the wire; an impossible calendar date never leaves the process. */
    public static void apiVersionPinReachesTheWireAndImpossibleDatesFailUsage(List<String> imagesCommand)
            throws Exception {
        apiVersionPinReachesTheWireAndImpossibleDatesFailUsage(imagesCommand, fixtureServer(), SANDBOX_PREFIX);
    }

    /** The images members of the success envelope: the image lines of the projection's results. */
    private static void assertImagesEnvelopeMembers(JsonNode envelope) {
        JsonNode projection = envelope.path("data").path("projection");
        assertEquals("https://images.example.com/first-full.jpg", projection.path("results")
                .get(0)
                .path("image")
                .asText());
        assertTrue(
                projection.path("results").get(1).path("thumbnail").isMissingNode(),
                "an absent upstream member is omitted, never coerced");
        JsonNode meta = envelope.path("meta");
        assertEquals(REQUEST_ID, meta.path("request_id").asText());
        assertEquals(200, meta.path("http_status").asInt());
        assertEquals(RESPONSE_API_VERSION, meta.path("api_version").asText());
    }

    /** The fixture exchange every success scenario serves: three results, two aligned quota windows, identifiers. */
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
                .writeBytes(IMAGES_RESULTS);
    }
}

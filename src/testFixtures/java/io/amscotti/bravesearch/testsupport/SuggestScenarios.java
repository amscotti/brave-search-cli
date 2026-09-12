package io.amscotti.bravesearch.testsupport;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;

/**
 * The suggest vertical-slice scenarios, run against a whole CLI process and a scripted
 * loopback server. Each scenario takes the command prefix that launches the suggest
 * command — the installed JVM launcher plus the subcommand token, or the native binary
 * plus the same token — so both executables must show identical evidence: numbered
 * LF-only human output with the rich members indented, one schema-valid JSON success
 * envelope whose suggest members stay exact, JSONL result records followed by one
 * summary record each independently schema-valid, raw stdout byte-identical to the
 * served body, the authentication failure's exit status, usage rejections that predate
 * any network dispatch — the suggest-specific ones included: the count bound of 20, the
 * {@code --lang} spelling that exists nowhere in this CLI, and every shared spelling the
 * suggest endpoint leaves undocumented — the loopback test-key routing that dispatches
 * exactly one GET whose language option rides the suggest wire name {@code lang} and
 * never {@code search_lang}, and the Api-Version pin.
 *
 * <p>The harness plumbing and the endpoint-generic scenario bodies live once in {@link
 * SearchScenarios}; this class carries the suggest fixture bodies and the
 * suggest-specific evidence: the suggestions heading, the completion members of the
 * projection and records, the suggest bucket, and the language-alias wire mapping of a
 * non-paginated endpoint.
 */
public final class SuggestScenarios extends SearchScenarios {

    /** The served three-suggestion success body, including unknown blocks and exact-scale decimals. */
    private static final byte[] SUGGEST_RESULTS = """
            {
              "type": "suggest",
              "query": {
                "original": "three word query"
              },
              "results": [
                {
                  "query": "three word query one",
                  "type": "query"
                },
                {
                  "query": "three word query two",
                  "type": "entity",
                  "is_entity": true,
                  "title": "Enriched Title",
                  "description": "Enriched description text.",
                  "img": "https://images.example.com/enriched.png"
                },
                {
                  "query": "three word query three",
                  "unknown_result_field": {
                    "score": 0.125
                  }
                }
              ],
              "unknown_future_block": {
                "cost": 1.10,
                "long": 0.1000000000000000000001
              }
            }
            """
            .getBytes(UTF_8);

    /** The exact human document the three-suggestion body renders, every line LF-terminated. */
    private static final String EXPECTED_HUMAN = """
            Suggestions for: three word query

             1  three word query one

             2  three word query two
                Enriched Title
                Enriched description text.
                https://images.example.com/enriched.png

             3  three word query three
            3 suggestions.
            quota: 0 of 1 remaining (window resets in 1s)
            """;

    private static final String SANDBOX_PREFIX = "brave-suggest-process-";

    private SuggestScenarios() {}

    /** A 200 fixture exchange renders the numbered human listing, LF-only, without any ANSI byte. */
    public static void humanHappyPathRendersNumberedLfLinesWithoutAnsi(List<String> suggestCommand) throws Exception {
        humanHappyPathRendersNumberedLfLinesWithoutAnsi(suggestCommand, fixtureServer(), EXPECTED_HUMAN, SANDBOX_PREFIX);
    }

    /**
     * JSON mode emits exactly one LF-terminated success envelope that validates and stays
     * lossless, carrying the suggest members: each result's completion as {@code query}
     * and its kind as {@code suggestion_type}.
     */
    public static void jsonEnvelopeIsOneValidatedLfLine(List<String> suggestCommand) throws Exception {
        String testKey = freshToken();
        SchemaCatalog schemas = new SchemaCatalog();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = fixtureServer().start()) {
            ProcessHarness.ProcessResult result = run(
                    suggestCommand, server, childEnvironment(sandbox.directory, testKey), "--output", "json", "three word query");

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            String stdout = new String(result.stdout(), UTF_8);
            assertTrue(
                    stdout.indexOf('\n') == stdout.length() - 1,
                    () -> "the envelope must be exactly one LF-terminated line: " + describe(result, testKey));
            assertTrue(
                    schemas.validateText("envelope-success.schema.json", stdout).isEmpty(),
                    () -> "the envelope must satisfy envelope-success.schema.json: " + describe(result, testKey));

            JsonNode envelope = READER.readTree(result.stdout());
            assertEquals("suggest", envelope.path("command").asText());
            JsonNode projection = envelope.path("data").path("projection");
            assertEquals(3, projection.path("result_count").asInt());
            assertEquals(1, projection.path("page").asInt());
            assertEquals(0, projection.path("upstream_offset").asInt());
            assertEquals("three word query one", projection.path("results").get(0).path("query").asText());
            assertEquals("query", projection.path("results").get(0).path("suggestion_type").asText());
            assertEquals(
                    "https://images.example.com/enriched.png",
                    projection.path("results").get(1).path("img").asText());
            assertTrue(
                    projection.path("results").get(0).path("title").isMissingNode(),
                    "a plain suggestion carries no enriched title, never an empty placeholder");
            assertTrue(
                    stdout.contains("0.1000000000000000000001"),
                    "the spot decimal must be on stdout byte-for-byte");
            assertEquals(REQUEST_ID, envelope.path("meta").path("request_id").asText());
            assertEquals(RESPONSE_API_VERSION, envelope.path("meta").path("api_version").asText());
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /** JSONL mode emits one validated record per suggestion and exactly one summary record after them. */
    public static void jsonlEmitsValidatedResultRecordsThenSummary(List<String> suggestCommand) throws Exception {
        jsonlEmitsValidatedResultRecordsThenSummary(
                suggestCommand,
                fixtureServer(),
                "suggest",
                "suggest",
                records -> {
                    assertEquals("three word query one", records.get(0).path("query").asText());
                    assertEquals("query", records.get(0).path("suggestion_type").asText());
                    assertEquals("entity", records.get(1).path("suggestion_type").asText());
                    assertEquals("Enriched Title", records.get(1).path("title").asText());
                    assertTrue(
                            records.get(2).path("suggestion_type").isMissingNode(),
                            "an absent kind is omitted, never an empty placeholder");
                },
                SANDBOX_PREFIX);
    }

    /** Raw mode writes the served body bytes to stdout byte-identically, adding and removing nothing. */
    public static void rawOutputIsTheServedBodyBytesExactly(List<String> suggestCommand) throws Exception {
        rawOutputIsTheServedBodyBytesExactly(suggestCommand, fixtureServer(), SUGGEST_RESULTS, SANDBOX_PREFIX);
    }

    /** A 401 keeps the authentication exit: one diagnostic line in human mode, one failure envelope in JSON mode. */
    public static void authenticationFailureExitsFourInHumanAndJsonModes(List<String> suggestCommand)
            throws Exception {
        authenticationFailureExitsFourInHumanAndJsonModes(suggestCommand, "suggest", SANDBOX_PREFIX);
    }

    /**
     * A usage-invalid count, the {@code --lang} spelling that exists nowhere in this CLI,
     * the shared spellings the suggest endpoint leaves undocumented, and the page-walk
     * family — the suggest endpoint documents no pagination — are all rejected with the
     * usage exit before the server can observe any request.
     */
    public static void usageFailureHappensBeforeAnyNetworkDispatch(List<String> suggestCommand) throws Exception {
        usageFailureHappensBeforeAnyNetworkDispatch(
                suggestCommand, fixtureServer(), "Usage:", SANDBOX_PREFIX, "--count", "21", "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                suggestCommand, fixtureServer(), "Unknown option", SANDBOX_PREFIX, "--lang", "de", "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                suggestCommand,
                fixtureServer(),
                "--safe-search is not accepted by this command",
                SANDBOX_PREFIX,
                "--safe-search",
                "moderate",
                "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                suggestCommand,
                fixtureServer(),
                "--freshness is not accepted by this command",
                SANDBOX_PREFIX,
                "--freshness",
                "pd",
                "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                suggestCommand,
                fixtureServer(),
                "--page is not accepted by this command",
                SANDBOX_PREFIX,
                "--page",
                "2",
                "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                suggestCommand, fixtureServer(), "Unknown option", SANDBOX_PREFIX, "--all-pages", "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                suggestCommand, fixtureServer(), "Unknown option", SANDBOX_PREFIX, "--goggle", "https://example.com/g", "q");
    }

    /**
     * A loopback run authenticates with the test key, never forwards a stored credential,
     * and dispatches exactly one GET: the bare invocation carries only the query, and the
     * shared {@code --search-lang} alias rides the suggest wire name {@code lang} — the
     * string {@code search_lang} never appears on the request line.
     */
    public static void loopbackRunSendsTheTestKeyWithTheLanguageAliasOnTheSuggestWireName(List<String> suggestCommand)
            throws Exception {
        String testKey = freshToken();
        String storedDecoy = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = fixtureServer().start()) {
            java.util.Map<String, String> environment = childEnvironment(sandbox.directory, testKey);
            environment.put("BRAVE_API_KEY", storedDecoy);
            ProcessHarness.ProcessResult result =
                    run(suggestCommand, server, environment, "--search-lang", "de", "three word query");

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey, storedDecoy));
            assertTrue(
                    new String(result.stdout(), UTF_8).startsWith("Suggestions for: "),
                    () -> "the exchange must have succeeded: " + describe(result, testKey, storedDecoy));

            List<ScriptedSseServer.RecordedRequest> requests = server.requests();
            assertEquals(1, requests.size(), "a suggest lookup is exactly one request, never a walk");
            assertEquals(
                    "/suggest/search?lang=de&q=three%20word%20query",
                    requests.getFirst().path(),
                    "the language alias rides the suggest wire name, never the search one");
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
    public static void missingLoopbackTestKeyKeepsTheLocalConfigurationExit(List<String> suggestCommand)
            throws Exception {
        missingLoopbackTestKeyKeepsTheLocalConfigurationExit(suggestCommand, fixtureServer(), SANDBOX_PREFIX);
    }

    /**
     * A downstream consumer closing the pipe early is silent success: the child exits
     * exactly 0 and writes no diagnostic while still writing the oversized raw body.
     */
    public static void downstreamPipeCloseIsSilentZero(List<String> suggestCommand) throws Exception {
        byte[] oversized =
                ("{\"results\":[{\"query\":\"" + "x".repeat(256 * 1024) + "\"}]}").getBytes(UTF_8);
        downstreamPipeCloseIsSilentZero(suggestCommand, oversized, SANDBOX_PREFIX);
    }

    /** A 200 body that is not readable JSON keeps exit 8 with the malformed failure document of each mode. */
    public static void garbageSuccessBodyExitsEight(List<String> suggestCommand) throws Exception {
        garbageSuccessBodyExitsEight(suggestCommand, "suggest", SANDBOX_PREFIX);
    }

    /** The Api-Version pin travels verbatim on the wire; an impossible calendar date never leaves the process. */
    public static void apiVersionPinReachesTheWireAndImpossibleDatesFailUsage(List<String> suggestCommand)
            throws Exception {
        apiVersionPinReachesTheWireAndImpossibleDatesFailUsage(suggestCommand, fixtureServer(), SANDBOX_PREFIX);
    }

    /** The fixture exchange every success scenario serves: three suggestions, two aligned quota windows, identifiers. */
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
                .writeBytes(SUGGEST_RESULTS);
    }
}

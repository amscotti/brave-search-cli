package io.amscotti.bravesearch.testsupport;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;

/**
 * The spellcheck vertical-slice scenarios, run against a whole CLI process and a
 * scripted loopback server. Each scenario takes the command prefix that launches the
 * spellcheck command — the installed JVM launcher plus the subcommand token, or the
 * native binary plus the same token — so both executables must show identical evidence:
 * the numbered human correction listing and the empty case's single {@code No
 * corrections.} line, one schema-valid JSON success envelope whose correction members
 * stay exact, the JSONL stream of one result record per correction followed by one
 * summary record each independently schema-valid, raw stdout byte-identical to the
 * served body, the authentication failure's exit status, usage rejections that predate
 * any network dispatch — the spellcheck-specific ones included: {@code --count}, which
 * the spellcheck endpoint does not document, the suggest-only {@code --rich}, and the
 * {@code --lang} spelling that exists nowhere in this CLI — the loopback test-key
 * routing that dispatches exactly one GET whose language option rides the spellcheck
 * wire name {@code lang} and never {@code search_lang}, and the Api-Version pin.
 *
 * <p>The harness plumbing and the endpoint-generic scenario bodies live once in {@link
 * SearchScenarios}; this class carries the spellcheck fixture bodies and the
 * spellcheck-specific evidence: the corrections heading, the corrected-query members of
 * the projection and records, the spellcheck bucket, and the language-alias wire mapping
 * of a non-paginated endpoint.
 */
public final class SpellcheckScenarios extends SearchScenarios {

    /** The served one-correction success body, including unknown blocks and exact-scale decimals. */
    private static final byte[] SPELLCHECK_RESULTS = """
            {
              "type": "spellcheck",
              "query": {
                "original": "three word query"
              },
              "results": [
                {
                  "query": "three word corrected query"
                }
              ],
              "unknown_future_block": {
                "cost": 1.10,
                "long": 0.1000000000000000000001
              }
            }
            """
            .getBytes(UTF_8);

    /** The exact human document the one-correction body renders, every line LF-terminated. */
    private static final String EXPECTED_HUMAN = """
            Spellcheck results for: three word query

             1  three word corrected query
            1 correction.
            quota: 0 of 1 remaining (window resets in 1s)
            """;

    /** The exact human document a zero-correction body renders: the answer is the clean query. */
    private static final String EXPECTED_EMPTY_HUMAN = "No corrections.\n";

    private static final String SANDBOX_PREFIX = "brave-spellcheck-process-";

    private SpellcheckScenarios() {}

    /** A 200 fixture exchange renders the numbered human correction listing, LF-only, without any ANSI byte. */
    public static void humanHappyPathRendersNumberedLfLinesWithoutAnsi(List<String> spellcheckCommand)
            throws Exception {
        humanHappyPathRendersNumberedLfLinesWithoutAnsi(
                spellcheckCommand, fixtureServer(), EXPECTED_HUMAN, SANDBOX_PREFIX);
    }

    /** A zero-correction exchange renders the single {@code No corrections.} line, LF-terminated. */
    public static void anEmptyCorrectionListRendersTheSingleNoCorrectionsLine(List<String> spellcheckCommand)
            throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = emptyFixtureServer().start()) {
            ProcessHarness.ProcessResult result =
                    run(spellcheckCommand, server, childEnvironment(sandbox.directory, testKey), "three word query");

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            assertEquals(EXPECTED_EMPTY_HUMAN, new String(result.stdout(), UTF_8), () -> describe(result, testKey));
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /**
     * JSON mode emits exactly one LF-terminated success envelope that validates and stays
     * lossless, carrying the spellcheck members: the correction as the result's {@code
     * query} member on the constant first page.
     */
    public static void jsonEnvelopeIsOneValidatedLfLine(List<String> spellcheckCommand) throws Exception {
        String testKey = freshToken();
        SchemaCatalog schemas = new SchemaCatalog();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = fixtureServer().start()) {
            ProcessHarness.ProcessResult result = run(
                    spellcheckCommand,
                    server,
                    childEnvironment(sandbox.directory, testKey),
                    "--output",
                    "json",
                    "three word query");

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            String stdout = new String(result.stdout(), UTF_8);
            assertTrue(
                    stdout.indexOf('\n') == stdout.length() - 1,
                    () -> "the envelope must be exactly one LF-terminated line: " + describe(result, testKey));
            assertTrue(
                    schemas.validateText("envelope-success.schema.json", stdout).isEmpty(),
                    () -> "the envelope must satisfy envelope-success.schema.json: " + describe(result, testKey));

            JsonNode envelope = READER.readTree(result.stdout());
            assertEquals("spellcheck", envelope.path("command").asText());
            JsonNode projection = envelope.path("data").path("projection");
            assertEquals(1, projection.path("result_count").asInt());
            assertEquals(1, projection.path("page").asInt());
            assertEquals(0, projection.path("upstream_offset").asInt());
            assertEquals(
                    "three word corrected query", projection.path("results").get(0).path("query").asText());
            assertTrue(
                    stdout.contains("0.1000000000000000000001"),
                    "the spot decimal must be on stdout byte-for-byte");
            assertEquals(REQUEST_ID, envelope.path("meta").path("request_id").asText());
            assertEquals(RESPONSE_API_VERSION, envelope.path("meta").path("api_version").asText());
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /** JSONL mode emits one validated record per correction and exactly one summary record after it. */
    public static void jsonlEmitsValidatedResultRecordsThenSummary(List<String> spellcheckCommand) throws Exception {
        String testKey = freshToken();
        SchemaCatalog schemas = new SchemaCatalog();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = fixtureServer().start()) {
            ProcessHarness.ProcessResult result = run(
                    spellcheckCommand,
                    server,
                    childEnvironment(sandbox.directory, testKey),
                    "--output",
                    "jsonl",
                    "three word query");

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            String stdout = new String(result.stdout(), UTF_8);
            assertFalse(stdout.contains("\r"), "JSONL lines must be LF-terminated only");
            String[] lines = stdout.split("\n", -1);
            assertEquals(
                    3,
                    lines.length,
                    () -> "one result record, one summary record, one terminator: " + describe(result, testKey));

            assertTrue(
                    schemas.validateText("jsonl-record.schema.json", lines[0]).isEmpty(),
                    () -> "result record must satisfy jsonl-record.schema.json: " + lines[0]);
            JsonNode record = READER.readTree(lines[0]);
            assertEquals("result", record.path("type").asText());
            assertEquals("spellcheck", record.path("command").asText());
            assertEquals("spellcheck", record.path("bucket").asText());
            assertEquals(0, record.path("position").asInt());
            assertEquals("three word corrected query", record.path("query").asText());

            assertTrue(
                    schemas.validateText("jsonl-record.schema.json", lines[1]).isEmpty(),
                    () -> "summary record must satisfy jsonl-record.schema.json: " + lines[1]);
            JsonNode summary = READER.readTree(lines[1]);
            assertEquals("summary", summary.path("type").asText());
            assertEquals("spellcheck", summary.path("command").asText());
            assertEquals(1, summary.path("result_count").asInt());
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
    public static void rawOutputIsTheServedBodyBytesExactly(List<String> spellcheckCommand) throws Exception {
        rawOutputIsTheServedBodyBytesExactly(spellcheckCommand, fixtureServer(), SPELLCHECK_RESULTS, SANDBOX_PREFIX);
    }

    /** A 401 keeps the authentication exit: one diagnostic line in human mode, one failure envelope in JSON mode. */
    public static void authenticationFailureExitsFourInHumanAndJsonModes(List<String> spellcheckCommand)
            throws Exception {
        authenticationFailureExitsFourInHumanAndJsonModes(spellcheckCommand, "spellcheck", SANDBOX_PREFIX);
    }

    /**
     * The count the spellcheck endpoint does not document, the suggest-only rich flag,
     * the {@code --lang} spelling that exists nowhere in this CLI, the shared spellings
     * the spellcheck endpoint leaves undocumented, and the page-walk family are all
     * rejected with the usage exit before the server can observe any request.
     */
    public static void usageFailureHappensBeforeAnyNetworkDispatch(List<String> spellcheckCommand) throws Exception {
        usageFailureHappensBeforeAnyNetworkDispatch(
                spellcheckCommand,
                fixtureServer(),
                "--count is not accepted by this command",
                SANDBOX_PREFIX,
                "--count",
                "5",
                "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                spellcheckCommand, fixtureServer(), "Unknown option", SANDBOX_PREFIX, "--rich", "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                spellcheckCommand, fixtureServer(), "Unknown option", SANDBOX_PREFIX, "--lang", "de", "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                spellcheckCommand,
                fixtureServer(),
                "--safe-search is not accepted by this command",
                SANDBOX_PREFIX,
                "--safe-search",
                "moderate",
                "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                spellcheckCommand,
                fixtureServer(),
                "--page is not accepted by this command",
                SANDBOX_PREFIX,
                "--page",
                "2",
                "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                spellcheckCommand, fixtureServer(), "Unknown option", SANDBOX_PREFIX, "--all-pages", "three word query");
    }

    /**
     * A loopback run authenticates with the test key, never forwards a stored credential,
     * and dispatches exactly one GET: the bare invocation carries only the query, and the
     * shared {@code --search-lang} alias rides the spellcheck wire name {@code lang} —
     * the string {@code search_lang} never appears on the request line.
     */
    public static void loopbackRunSendsTheTestKeyWithTheLanguageAliasOnTheSpellcheckWireName(
            List<String> spellcheckCommand) throws Exception {
        String testKey = freshToken();
        String storedDecoy = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = fixtureServer().start()) {
            java.util.Map<String, String> environment = childEnvironment(sandbox.directory, testKey);
            environment.put("BRAVE_API_KEY", storedDecoy);
            ProcessHarness.ProcessResult result =
                    run(spellcheckCommand, server, environment, "--search-lang", "de", "three word query");

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey, storedDecoy));
            assertTrue(
                    new String(result.stdout(), UTF_8).startsWith("Spellcheck results for: "),
                    () -> "the exchange must have succeeded: " + describe(result, testKey, storedDecoy));

            List<ScriptedSseServer.RecordedRequest> requests = server.requests();
            assertEquals(1, requests.size(), "a spellcheck lookup is exactly one request, never a walk");
            assertEquals(
                    "/spellcheck/search?lang=de&q=three%20word%20query",
                    requests.getFirst().path(),
                    "the language alias rides the spellcheck wire name, never the search one");
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
    public static void missingLoopbackTestKeyKeepsTheLocalConfigurationExit(List<String> spellcheckCommand)
            throws Exception {
        missingLoopbackTestKeyKeepsTheLocalConfigurationExit(spellcheckCommand, fixtureServer(), SANDBOX_PREFIX);
    }

    /**
     * A downstream consumer closing the pipe early is silent success: the child exits
     * exactly 0 and writes no diagnostic while still writing the oversized raw body.
     */
    public static void downstreamPipeCloseIsSilentZero(List<String> spellcheckCommand) throws Exception {
        byte[] oversized =
                ("{\"results\":[{\"query\":\"" + "x".repeat(256 * 1024) + "\"}]}").getBytes(UTF_8);
        downstreamPipeCloseIsSilentZero(spellcheckCommand, oversized, SANDBOX_PREFIX);
    }

    /** A 200 body that is not readable JSON keeps exit 8 with the malformed failure document of each mode. */
    public static void garbageSuccessBodyExitsEight(List<String> spellcheckCommand) throws Exception {
        garbageSuccessBodyExitsEight(spellcheckCommand, "spellcheck", SANDBOX_PREFIX);
    }

    /** The Api-Version pin travels verbatim on the wire; an impossible calendar date never leaves the process. */
    public static void apiVersionPinReachesTheWireAndImpossibleDatesFailUsage(List<String> spellcheckCommand)
            throws Exception {
        apiVersionPinReachesTheWireAndImpossibleDatesFailUsage(spellcheckCommand, fixtureServer(), SANDBOX_PREFIX);
    }

    /** The fixture exchange every success scenario serves: one correction, two aligned quota windows, identifiers. */
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
                .writeBytes(SPELLCHECK_RESULTS);
    }

    /** The fixture exchange whose correction list is empty: the clean-query answer. */
    private static ScriptedSseServer.Builder emptyFixtureServer() {
        return ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .writeBytes(
                        "{\"type\":\"spellcheck\",\"query\":{\"original\":\"three word query\"},\"results\":[]}"
                                .getBytes(UTF_8));
    }
}

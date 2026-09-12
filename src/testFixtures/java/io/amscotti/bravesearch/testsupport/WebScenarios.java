package io.amscotti.bravesearch.testsupport;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/**
 * The web search vertical-slice scenarios, run against a whole CLI process and a scripted
 * loopback server. Each scenario takes the command prefix that launches the web command — the
 * installed JVM launcher plus the subcommand token, or the native binary plus the same token —
 * so both executables must show identical evidence: numbered LF-only human output, exactly one
 * schema-valid JSON success envelope whose upstream stays lossless down to the spot decimal,
 * JSONL result records followed by one summary record each independently schema-valid, raw
 * stdout byte-identical to the served body, the authentication failure's exit status with one
 * clean diagnostic in human mode and one failure envelope in JSON mode, usage rejections that
 * predate any network dispatch, the loopback test-key routing that never forwards a stored
 * credential, the Api-Version pin that travels verbatim while impossible calendar dates
 * never leave the process, the local-configuration failure that names itself on stderr in
 * every mode, and the downstream pipe close that ends the run as a silent zero.
 *
 * <p>The harness plumbing and the endpoint-generic scenario bodies live once in {@link
 * SearchScenarios}; this class carries the web fixture bodies and the web-specific evidence:
 * the web heading, the rate-limit and usage metadata of the success envelope, and the walk
 * pages whose continuation flags steer the web walk.
 */
public final class WebScenarios extends SearchScenarios {

    /** The served three-result success body, including unknown blocks and exact-scale decimals. */
    private static final byte[] WEB_RESULTS = """
            {
              "query": {
                "original": "three word query"
              },
              "web": {
                "results": [
                  {
                    "title": "First Title",
                    "url": "https://example.com/first",
                    "description": "First description text.",
                    "unknown_result_field": {
                      "score": 0.125
                    }
                  },
                  {
                    "title": "Second Title",
                    "url": "https://example.com/second",
                    "unknown_age": "2026-08-02"
                  },
                  {
                    "title": 42,
                    "url": "https://example.com/third",
                    "description": "Third description."
                  }
                ]
              },
              "unknown_future_block": {
                "cost": 1.10,
                "long": 0.1000000000000000000001
              }
            }
            """
            .getBytes(UTF_8);

    /** The first page of a walk: two results and a continuation flag that demands page two. */
    private static final byte[] WALK_PAGE_ONE =
            ("{\"query\":{\"original\":\"three word query\",\"more_results_available\":true},"
                            + "\"web\":{\"results\":["
                            + "{\"title\":\"First Title\",\"url\":\"https://example.com/first\"},"
                            + "{\"title\":\"Second Title\",\"url\":\"https://example.com/second\"}]}}")
                    .getBytes(UTF_8);

    /** The last page of a walk: one fresh result and a continuation flag that stops it. */
    private static final byte[] WALK_PAGE_LAST =
            ("{\"query\":{\"original\":\"three word query\",\"more_results_available\":false},"
                            + "\"web\":{\"results\":["
                            + "{\"title\":\"Third Title\",\"url\":\"https://example.com/third\"}]}}")
                    .getBytes(UTF_8);

    /** The exact human document the three-result body renders, every line LF-terminated. */
    private static final String EXPECTED_HUMAN = """
            Web results for: three word query

             1  First Title
                https://example.com/first
                First description text.

             2  Second Title
                https://example.com/second

             3  (no title)
                https://example.com/third
                Third description.
            3 results.
            quota: 0 of 1 remaining (window resets in 1s)
            """;

    private static final String SANDBOX_PREFIX = "brave-web-process-";

    private WebScenarios() {}

    /** A 200 fixture exchange renders the numbered human listing, LF-only, without any ANSI byte. */
    public static void humanHappyPathRendersNumberedLfLinesWithoutAnsi(List<String> webCommand) throws Exception {
        humanHappyPathRendersNumberedLfLinesWithoutAnsi(webCommand, fixtureServer(), EXPECTED_HUMAN, SANDBOX_PREFIX);
    }

    /** JSON mode emits exactly one LF-terminated success envelope that validates and stays lossless. */
    public static void jsonSuccessEnvelopeIsOneValidatedLfLine(List<String> webCommand) throws Exception {
        jsonSuccessEnvelopeIsOneValidatedLfLine(
                webCommand,
                fixtureServer(),
                "web",
                "First Title",
                WebScenarios::assertWebEnvelopeMembers,
                SANDBOX_PREFIX);
    }

    /** JSONL mode emits one validated record per logical result and exactly one summary record after them. */
    public static void jsonlEmitsValidatedResultRecordsThenSummary(List<String> webCommand) throws Exception {
        jsonlEmitsValidatedResultRecordsThenSummary(
                webCommand,
                fixtureServer(),
                "web",
                "web",
                records -> assertEquals("Second Title", records.get(1).path("title").asText()),
                SANDBOX_PREFIX);
    }

    /** Raw mode writes the served body bytes to stdout byte-identically, adding and removing nothing. */
    public static void rawOutputIsTheServedBodyBytesExactly(List<String> webCommand) throws Exception {
        rawOutputIsTheServedBodyBytesExactly(webCommand, fixtureServer(), WEB_RESULTS, SANDBOX_PREFIX);
    }

    /** A 401 keeps the authentication exit: one diagnostic line in human mode, one failure envelope in JSON mode. */
    public static void authenticationFailureExitsFourInHumanAndJsonModes(List<String> webCommand) throws Exception {
        authenticationFailureExitsFourInHumanAndJsonModes(webCommand, "web", SANDBOX_PREFIX);
    }

    /** A 429 ends the jsonl stream in one error record carrying the observed rate-limit windows. */
    public static void rateLimitedJsonlErrorCarriesTheObservedWindows(List<String> webCommand) throws Exception {
        rateLimitedJsonlErrorCarriesTheObservedWindows(webCommand, SANDBOX_PREFIX);
    }

    /** A usage-invalid count is rejected with the usage exit before the server can observe any request. */
    public static void usageFailureHappensBeforeAnyNetworkDispatch(List<String> webCommand) throws Exception {
        usageFailureHappensBeforeAnyNetworkDispatch(
                webCommand, fixtureServer(), "Usage:", SANDBOX_PREFIX, "--count", "21", "three word query");
    }

    /** A loopback run authenticates with the test key and never forwards a stored credential. */
    public static void loopbackRunsSendTheTestKeyAndNeverStoredCredentials(List<String> webCommand) throws Exception {
        loopbackRunsSendTheTestKeyAndNeverStoredCredentials(
                webCommand, fixtureServer(), "Web results for: ", SANDBOX_PREFIX);
    }

    /** Without the loopback test key the run keeps the local-configuration exit and dispatches nothing. */
    public static void missingLoopbackTestKeyKeepsTheLocalConfigurationExit(List<String> webCommand) throws Exception {
        missingLoopbackTestKeyKeepsTheLocalConfigurationExit(webCommand, fixtureServer(), SANDBOX_PREFIX);

        // a local precondition failure explains itself on stderr in EVERY mode, jsonl
        // included: no exchange happened, so no stream record exists to carry it
        String storedDecoy = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = fixtureServer().start()) {
            Map<String, String> environment = childEnvironment(sandbox.directory, null);
            environment.put("BRAVE_API_KEY", storedDecoy);
            ProcessHarness.ProcessResult jsonl =
                    run(webCommand, server, environment, "--output", "jsonl", "three word query");
            assertEquals(3, jsonl.exitStatus(), () -> describe(jsonl, storedDecoy));
            assertEquals("", new String(jsonl.stdout(), UTF_8), () -> describe(jsonl, storedDecoy));
            List<String> jsonlDiagnostics = new String(jsonl.stderr(), UTF_8).lines().toList();
            assertEquals(
                    1,
                    jsonlDiagnostics.size(),
                    () -> "jsonl still names a local precondition failure on stderr: " + describe(jsonl, storedDecoy));
            assertTrue(
                    jsonlDiagnostics.getFirst().contains("BRAVE_SEARCH_TEST_KEY"),
                    () -> "the jsonl diagnostic must name the missing variable: " + describe(jsonl, storedDecoy));
            assertTokenMaterialAbsent(jsonl, storedDecoy);
        }
    }

    /** The Api-Version pin travels verbatim on the wire; an impossible calendar date never leaves the process. */
    public static void apiVersionPinReachesTheWireAndImpossibleDatesFailUsage(List<String> webCommand)
            throws Exception {
        apiVersionPinReachesTheWireAndImpossibleDatesFailUsage(webCommand, fixtureServer(), SANDBOX_PREFIX);
    }

    /**
     * A downstream consumer closing the pipe early is silent success: the child exits exactly
     * 0, writes no diagnostic, and produces no error banner. The served body exceeds any pipe
     * buffer, so the child is still writing when the consumer closes after a few bytes — the
     * observed status is the child's own, not the consumer's.
     */
    public static void downstreamPipeCloseIsSilentZero(List<String> webCommand) throws Exception {
        downstreamPipeCloseIsSilentZero(webCommand, oversizedWebResults(), SANDBOX_PREFIX);
    }

    /** A success body far larger than any pipe buffer, so a writer must block mid-body. */
    private static byte[] oversizedWebResults() {
        return ("{\"web\":{\"results\":[{\"title\":\"Big\",\"description\":\""
                        + "x".repeat(256 * 1024)
                        + "\"}]}}")
                .getBytes(UTF_8);
    }

    /**
     * The page-one records of a paged JSONL walk stand, and a later page's server failure ends
     * the stream in one counted error record: page one streams its two result records first,
     * then the error record reports the failed walk's page counts, and the process keeps the
     * upstream failure status. jsonl owns its failure explanation, so stderr stays empty.
     */
    public static void pagedJsonlKeepsPageOneRecordsBeforeTheLaterPageError(List<String> webCommand) throws Exception {
        pagedJsonlKeepsPageOneRecordsBeforeTheLaterPageError(
                webCommand, WALK_PAGE_ONE, "First Title", "Second Title", SANDBOX_PREFIX);
    }

    /**
     * A downstream consumer closing the pipe during a paged JSONL walk — the head-of-stream
     * reader that leaves after the first page — is silent success: the child exits exactly 0,
     * writes no diagnostic, and produces no stack trace. The walk's second page is held back
     * until the consumer closed, so the broken pipe provably strikes the mid-walk record
     * writer, not the terminal summary.
     */
    public static void pagedJsonlBrokenPipeMidWalkIsSilentZero(List<String> webCommand) throws Exception {
        pagedJsonlBrokenPipeMidWalkIsSilentZero(webCommand, WALK_PAGE_ONE, WALK_PAGE_LAST, SANDBOX_PREFIX);
    }

    /**
     * A SIGINT that lands while a paged walk sits in its rate-limit pacing wait renders the
     * walk's transport-failure document — jsonl's counted error record, human's one stderr
     * diagnostic — while the process itself exits by the conventional interrupt status 130.
     */
    public static void sigintDuringPacedWalkExits130(List<String> webCommand) throws Exception {
        sigintDuringPacedWalkExits130(webCommand, WALK_PAGE_ONE, "web", SANDBOX_PREFIX);
    }

    /**
     * A SIGTERM that lands while a paged walk sits in its rate-limit pacing wait renders the
     * same transport-failure document while the process itself exits by the conventional
     * termination status 143.
     */
    public static void sigtermDuringPacedWalkExits143(List<String> webCommand) throws Exception {
        sigtermDuringPacedWalkExits143(webCommand, WALK_PAGE_ONE, "web", SANDBOX_PREFIX);
    }

    /**
     * A SIGINT that lands while the single web exchange sits parked on a stalled response
     * body renders the cancelled exchange's transport-failure document — human's one stderr
     * diagnostic, json's failure envelope — while the process exits by the conventional
     * interrupt status 130.
     */
    public static void sigintDuringBlockedBodyExits130(List<String> webCommand) throws Exception {
        sigintDuringBlockedBodyExits130(
                webCommand, "web", "{\"web\":{\"results\":[{\"title\":\"partial\"".getBytes(UTF_8), SANDBOX_PREFIX);
    }

    /**
     * A SIGTERM that lands while the single web exchange sits parked on a stalled response
     * body keeps the same contract with the conventional termination status 143.
     */
    public static void sigtermDuringBlockedBodyExits143(List<String> webCommand) throws Exception {
        sigtermDuringBlockedBodyExits143(
                webCommand, "web", "{\"web\":{\"results\":[{\"title\":\"partial\"".getBytes(UTF_8), SANDBOX_PREFIX);
    }

    /**
     * A SIGINT that lands after the single web exchange completed and printed its listing
     * changes nothing: the completed run keeps its success status 0.
     */
    public static void signalAfterCompletionKeepsZero(List<String> webCommand) throws Exception {
        signalAfterCompletionKeepsZero(webCommand, fixtureServer(), SANDBOX_PREFIX, "3 results.");
    }

    /**
     * An adversarial web body — cursor-wipe CSI, forged OSC-8 hyperlink, carriage return in
     * the title — never carries its controls into the human listing while the machine
     * documents stay lossless and raw stays byte-exact.
     */
    public static void upstreamTerminalControlsNeverReachHumanOutput(List<String> webCommand) throws Exception {
        upstreamTerminalControlsNeverReachHumanOutput(webCommand, ADVERSARIAL_RESULTS, SANDBOX_PREFIX);
    }

    /** The adversarial wire body: JSON-escaped terminal controls inside every text member. */
    private static final byte[] ADVERSARIAL_RESULTS =
            ("{\"web\":{\"results\":[{"
                            + "\"title\":\"inno\\u001b[2Jcent\\u001b]8;;https://evil.example\\u001b\\\\link\\u001b]8;;\\u001b\\\\ title\","
                            + "\"url\":\"https://example.com/\\u001b[1mstyled\","
                            + "\"description\":\"de\\rscript\\u0007ion \\u001b[?25lhidden\"}]}}")
                    .getBytes(UTF_8);

    /** The web members of the success envelope: the served quota windows and usage metadata. */
    private static void assertWebEnvelopeMembers(JsonNode envelope) {
        JsonNode meta = envelope.path("meta");
        assertEquals(REQUEST_ID, meta.path("request_id").asText());
        assertEquals(200, meta.path("http_status").asInt());
        assertEquals(RESPONSE_API_VERSION, meta.path("api_version").asText());
        assertEquals(2, meta.path("rate_limits").size());
        assertWindow(meta.path("rate_limits").get(0), "request", 1, 0, 1000);
        assertWindow(meta.path("rate_limits").get(1), "minute", 15, 14, 42000);
        JsonNode usage = meta.path("usage");
        assertTrue(usage.path("requests").isNull(), "no usage counters were served");
        assertTrue(usage.path("total_cost").isNull(), "no usage costs were served");
        assertEquals(
                REQUEST_ID,
                usage.path("unknown").path("x-request-id").asText(),
                "the identifier header stays preserved as the one unknown usage field");
        assertTrue(meta.path("warnings").isEmpty(), "the served rate-limit headers carry no advisory notes");
        JsonNode upstream = envelope.path("data").path("upstream");
        assertEquals(
                0,
                upstream.path("unknown_future_block")
                        .path("cost")
                        .decimalValue()
                        .compareTo(new java.math.BigDecimal("1.10")),
                "the cost must keep its numeric value through the whole process");
        assertEquals(
                0.125,
                upstream.path("web")
                        .path("results")
                        .get(0)
                        .path("unknown_result_field")
                        .path("score")
                        .asDouble());
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
                .writeBytes(WEB_RESULTS);
    }
}

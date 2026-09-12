package io.amscotti.bravesearch.testsupport;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * The news search vertical-slice scenarios, run against a whole CLI process and a scripted
 * loopback server. Each scenario takes the command prefix that launches the news command —
 * the installed JVM launcher plus the subcommand token, or the native binary plus the same
 * token — so both executables must show identical evidence: numbered LF-only human output
 * with the freshness age lines, exactly one schema-valid JSON success envelope whose
 * upstream stays lossless, JSONL result records followed by one summary record each
 * independently schema-valid, raw stdout byte-identical to the served body, the
 * authentication failure's exit status, usage rejections that predate any network dispatch,
 * the loopback test-key routing that never forwards a stored credential, the Api-Version
 * pin, and the news walk — which no upstream continuation field can stop — walking exactly
 * its page budget with overlap deduplication, later-page partial failures, the mid-walk
 * broken pipe ending as a silent zero, and the paced-walk interrupt ending as 130.
 *
 * <p>The harness plumbing and the endpoint-generic scenario bodies live once in {@link
 * SearchScenarios}; this class carries the news fixture bodies and the news-specific
 * evidence: the news heading, the age members of the projection and records, the news
 * bucket, and the walk pages whose overlaps prove cross-page deduplication.
 */
public final class NewsScenarios extends SearchScenarios {

    /** The served three-result success body, including unknown blocks and exact-scale decimals. */
    private static final byte[] NEWS_RESULTS = """
            {
              "query": {
                "original": "three word query"
              },
              "news": {
                "results": [
                  {
                    "title": "First Headline",
                    "url": "https://example.com/first",
                    "description": "First description text.",
                    "age": "2 hours ago",
                    "unknown_result_field": {
                      "score": 0.125
                    }
                  },
                  {
                    "title": "Second Headline",
                    "url": "https://example.com/second",
                    "age": "2026-08-30T12:00:00Z"
                  },
                  {
                    "title": 42,
                    "url": "https://example.com/third",
                    "description": "Third description.",
                    "age": 7
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

    /** The first page of a walk: two results, one of which the next page serves again. */
    private static final byte[] WALK_PAGE_ONE =
            ("{\"query\":{\"original\":\"three word query\"},"
                            + "\"news\":{\"results\":["
                            + "{\"title\":\"First Headline\",\"url\":\"https://example.com/first\",\"age\":\"2 hours ago\"},"
                            + "{\"title\":\"Overlap Headline\",\"url\":\"https://example.com/shared\",\"age\":\"1 hour ago\"}]}}")
                    .getBytes(UTF_8);

    /** A later page of a walk: the overlap again plus one fresh result. */
    private static final byte[] WALK_PAGE_LATER =
            ("{\"query\":{\"original\":\"three word query\"},"
                            + "\"news\":{\"results\":["
                            + "{\"title\":\"Shared Again\",\"url\":\"https://example.com/shared\",\"age\":\"33 minutes ago\"},"
                            + "{\"title\":\"Later Page\",\"url\":\"https://example.com/later\"}]}}")
                    .getBytes(UTF_8);

    /** The exact human document the three-result body renders, every line LF-terminated. */
    private static final String EXPECTED_HUMAN = """
            News results for: three word query

             1  First Headline
                https://example.com/first
                First description text.
                2 hours ago

             2  Second Headline
                https://example.com/second
                2026-08-30T12:00:00Z

             3  (no title)
                https://example.com/third
                Third description.
            3 results.
            quota: 0 of 1 remaining (window resets in 1s)
            """;

    private static final String SANDBOX_PREFIX = "brave-news-process-";

    private NewsScenarios() {}

    /** A 200 fixture exchange renders the numbered human listing, LF-only, without any ANSI byte. */
    public static void humanHappyPathRendersNumberedLfLinesWithoutAnsi(List<String> newsCommand) throws Exception {
        humanHappyPathRendersNumberedLfLinesWithoutAnsi(newsCommand, fixtureServer(), EXPECTED_HUMAN, SANDBOX_PREFIX);
    }

    /** JSON mode emits exactly one LF-terminated success envelope that validates and stays lossless. */
    public static void jsonSuccessEnvelopeIsOneValidatedLfLine(List<String> newsCommand) throws Exception {
        jsonSuccessEnvelopeIsOneValidatedLfLine(
                newsCommand,
                fixtureServer(),
                "news",
                "First Headline",
                NewsScenarios::assertNewsEnvelopeMembers,
                SANDBOX_PREFIX);
    }

    /** JSONL mode emits one validated record per logical result and exactly one summary record after them. */
    public static void jsonlEmitsValidatedResultRecordsThenSummary(List<String> newsCommand) throws Exception {
        jsonlEmitsValidatedResultRecordsThenSummary(
                newsCommand,
                fixtureServer(),
                "news",
                "news",
                records -> {
                    assertEquals("First Headline", records.get(0).path("title").asText());
                    assertEquals("2 hours ago", records.get(0).path("age").asText());
                    assertEquals("2026-08-30T12:00:00Z", records.get(1).path("age").asText());
                },
                SANDBOX_PREFIX);
    }

    /** Raw mode writes the served body bytes to stdout byte-identically, adding and removing nothing. */
    public static void rawOutputIsTheServedBodyBytesExactly(List<String> newsCommand) throws Exception {
        rawOutputIsTheServedBodyBytesExactly(newsCommand, fixtureServer(), NEWS_RESULTS, SANDBOX_PREFIX);
    }

    /** A 401 keeps the authentication exit: one diagnostic line in human mode, one failure envelope in JSON mode. */
    public static void authenticationFailureExitsFourInHumanAndJsonModes(List<String> newsCommand) throws Exception {
        authenticationFailureExitsFourInHumanAndJsonModes(newsCommand, "news", SANDBOX_PREFIX);
    }

    /**
     * A usage-invalid count and a web-only option spelling are both rejected with the usage
     * exit before the server can observe any request — the wider grammar's spellings are
     * unknown options of the news command.
     */
    public static void usageFailureHappensBeforeAnyNetworkDispatch(List<String> newsCommand) throws Exception {
        usageFailureHappensBeforeAnyNetworkDispatch(
                newsCommand, fixtureServer(), "Usage:", SANDBOX_PREFIX, "--count", "51", "three word query");
        usageFailureHappensBeforeAnyNetworkDispatch(
                newsCommand, fixtureServer(), "Unknown option", SANDBOX_PREFIX, "--text-decorations", "three word query");
    }

    /** A loopback run authenticates with the test key and never forwards a stored credential. */
    public static void loopbackRunsSendTheTestKeyAndNeverStoredCredentials(List<String> newsCommand) throws Exception {
        loopbackRunsSendTheTestKeyAndNeverStoredCredentials(
                newsCommand, fixtureServer(), "News results for: ", SANDBOX_PREFIX);
    }

    /** Without the loopback test key the run keeps the local-configuration exit and dispatches nothing. */
    public static void missingLoopbackTestKeyKeepsTheLocalConfigurationExit(List<String> newsCommand) throws Exception {
        missingLoopbackTestKeyKeepsTheLocalConfigurationExit(newsCommand, fixtureServer(), SANDBOX_PREFIX);
    }

    /**
     * The news walk has no upstream continuation field, so {@code --all-pages} walks through
     * the user-facing page 10: exactly ten requests — never an eleventh — with the zero-based
     * offsets of pages one through ten on the wire, and the summary reports the full budget.
     */
    public static void allPagesWalksExactlyTheTenDocumentedPages(List<String> newsCommand) throws Exception {
        allPagesWalksExactlyTheTenDocumentedPages(newsCommand, WALK_PAGE_ONE, SANDBOX_PREFIX);
    }

    /** {@code --max-pages} bounds the walk: exactly the budget's requests, no more. */
    public static void maxPagesBoundsTheWalkToItsBudget(List<String> newsCommand) throws Exception {
        maxPagesBoundsTheWalkToItsBudget(newsCommand, WALK_PAGE_ONE, SANDBOX_PREFIX);
    }

    /**
     * The walk deduplicates overlapping results across pages by the exact URL string: the
     * shared result of the second page is one duplicate removed, first-seen order survives,
     * and the summary counts the retained results.
     */
    public static void pagedWalkDeduplicatesOverlappingResultsAcrossPages(List<String> newsCommand) throws Exception {
        pagedWalkDeduplicatesOverlappingResultsAcrossPages(newsCommand, WALK_PAGE_ONE, WALK_PAGE_LATER, SANDBOX_PREFIX);
    }

    /**
     * The page-one records of a paged JSONL walk stand, and a later page's server failure ends
     * the stream in one counted error record: page one streams its two result records first,
     * then the error record reports the failed walk's page counts, and the process keeps the
     * upstream failure status. jsonl owns its failure explanation, so stderr stays empty.
     */
    public static void pagedJsonlKeepsPageOneRecordsBeforeTheLaterPageError(List<String> newsCommand) throws Exception {
        pagedJsonlKeepsPageOneRecordsBeforeTheLaterPageError(
                newsCommand, WALK_PAGE_ONE, "First Headline", "Overlap Headline", SANDBOX_PREFIX);
    }

    /**
     * A downstream consumer closing the pipe during a paged JSONL walk — the head-of-stream
     * reader that leaves after the first page — is silent success: the child exits exactly 0,
     * writes no diagnostic, and produces no stack trace. The walk's second page is held back
     * until the consumer closed, so the broken pipe provably strikes the mid-walk record
     * writer, not the terminal summary.
     */
    public static void pagedJsonlBrokenPipeMidWalkIsSilentZero(List<String> newsCommand) throws Exception {
        pagedJsonlBrokenPipeMidWalkIsSilentZero(newsCommand, WALK_PAGE_ONE, WALK_PAGE_LATER, SANDBOX_PREFIX);
    }

    /**
     * A SIGINT that lands while a paced news walk sits in its rate-limit pacing wait renders
     * the walk's transport-failure document — jsonl's counted error record, human's one stderr
     * diagnostic — while the process itself exits by the conventional interrupt status 130.
     */
    public static void sigintDuringPacedWalkExits130(List<String> newsCommand) throws Exception {
        sigintDuringPacedWalkExits130(newsCommand, WALK_PAGE_ONE, "news", SANDBOX_PREFIX);
    }

    /** The Api-Version pin travels verbatim on the wire; an impossible calendar date never leaves the process. */
    public static void apiVersionPinReachesTheWireAndImpossibleDatesFailUsage(List<String> newsCommand)
            throws Exception {
        apiVersionPinReachesTheWireAndImpossibleDatesFailUsage(newsCommand, fixtureServer(), SANDBOX_PREFIX);
    }

    /** The news members of the success envelope: the age lines of the projection's results. */
    private static void assertNewsEnvelopeMembers(JsonNode envelope) {
        JsonNode projection = envelope.path("data").path("projection");
        assertEquals("2 hours ago", projection.path("results").get(0).path("age").asText());
        assertTrue(
                projection.path("results").get(2).path("age").isMissingNode(),
                "a non-textual upstream age member is omitted, never coerced");
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
                .writeBytes(NEWS_RESULTS);
    }
}

package io.amscotti.bravesearch.testsupport;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

/**
 * The blocking answers scenarios, run against a whole CLI process and a scripted loopback
 * server. Each scenario takes the command prefix that launches the answers command — the
 * installed JVM launcher plus the subcommand token, or the native binary plus the same
 * token — so both executables must show identical evidence: the blocking human document
 * with its trailing usage-and-cost line, one schema-valid JSON success envelope whose
 * projection carries the answer and the citations while its {@code data.upstream} keeps
 * the lossless chat-completions document, raw stdout byte-identical to the served body,
 * the exact nested POST body bytes of both the minimal and the full blocking request —
 * no search control ever flat at the top level — the authentication failure's exit
 * status, every local usage rejection that predates any network dispatch, the typed
 * local failure that answers a streaming invocation today, the loopback test-key routing,
 * the Api-Version pin, and the silent early termination on a closed downstream pipe.
 */
public final class AnswersScenarios extends SearchScenarios {

    /** The question every standard scenario asks. */
    private static final String QUESTION = "what is the brave search api";

    /** The served blocking success document, the documented OpenAI chat-completions shape. */
    private static final byte[] BLOCKING_ANSWER = """
            {"id":"ans_01","object":"chat.completion","created":1756579200,"model":"brave",
            "choices":[{"index":0,"message":{"role":"assistant","content":"Brave Search is an independent index with its own crawler."},"finish_reason":"stop"}],
            "usage":{"prompt_tokens":900,"completion_tokens":120,"total_tokens":1020}}
            """
            .getBytes(UTF_8);

    /** The served blocking document that carries citations, the tolerant carriage this CLI renders. */
    private static final byte[] BLOCKING_ANSWER_CITATIONS = """
            {"id":"ans_02","object":"chat.completion","created":1756579200,"model":"brave",
            "choices":[{"index":0,"message":{"role":"assistant","content":"Grounded answer with citations."},"finish_reason":"stop"}],
            "citations":[{"start_index":0,"end_index":12,"number":1,"url":"https://search.brave.com/","favicon":"https://search.brave.com/favicon.ico","snippet":"an independent index"},{"number":2,"url":"https://brave.com"}],
            "usage":{"prompt_tokens":900,"completion_tokens":120,"total_tokens":1020}}
            """
            .getBytes(UTF_8);

    /** The exact human document the standard fixture renders, every line LF-terminated. */
    private static final String EXPECTED_HUMAN = """
            Answer for: what is the brave search api
            Brave Search is an independent index with its own crawler.

            Usage: requests 1, queries 2, tokens 900 in / 120 out, total cost 0.0042
            """;

    /** The exact body bytes of the minimal blocking request: one user message and the stream flag. */
    private static final String MINIMAL_BODY =
            "{\"messages\":[{\"content\":\"" + QUESTION + "\",\"role\":\"user\"}],\"stream\":false}";

    /** The exact body bytes of the full blocking request every member can shape. */
    private static final String FULL_BODY = "{\"max_completion_tokens\":1024,"
            + "\"messages\":[{\"content\":\"" + QUESTION + "\",\"role\":\"user\"}],"
            + "\"model\":\"future-model-x\","
            + "\"seed\":42,"
            + "\"stream\":false,"
            + "\"web_search_options\":{\"country\":\"US\",\"enable_citations\":false,\"enable_entities\":false,"
            + "\"language\":\"en\",\"safesearch\":\"strict\"}}";

    private static final String SANDBOX_PREFIX = "brave-answers-process-";

    private AnswersScenarios() {}

    /** A 200 fixture exchange renders the answer with its trailing usage line, LF-only, without any ANSI byte. */
    public static void humanHappyPathRendersTheAnswerAndUsageLineWithoutAnsi(List<String> answersCommand) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = fixtureServer().start()) {
            ProcessHarness.ProcessResult result =
                    run(answersCommand, server, childEnvironment(sandbox.directory, testKey), "--no-stream", QUESTION);

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            assertEquals(EXPECTED_HUMAN, new String(result.stdout(), UTF_8), () -> describe(result, testKey));
            assertFalse(new String(result.stdout(), UTF_8).contains("\r"), "human output must use LF endings only");
            assertFalse(
                    new String(result.stdout(), UTF_8).contains("\u001b"), "human output must carry no ANSI escapes");
            assertEquals("", new String(result.stderr(), UTF_8), () -> describe(result, testKey));
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /**
     * JSON mode emits exactly one LF-terminated success envelope that validates, keeps the
     * lossless document in {@code data.upstream}, the answer and citation projection in
     * {@code data.projection}, and the header-observed usage counters in {@code meta.usage}.
     */
    public static void jsonEnvelopeIsOneValidatedLfLine(List<String> answersCommand) throws Exception {
        String testKey = freshToken();
        SchemaCatalog schemas = new SchemaCatalog();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = fixtureServer(BLOCKING_ANSWER_CITATIONS).start()) {
            ProcessHarness.ProcessResult result = run(
                    answersCommand, server, childEnvironment(sandbox.directory, testKey), "--output", "json", "--no-stream", QUESTION);

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            String stdout = new String(result.stdout(), UTF_8);
            assertTrue(
                    stdout.indexOf('\n') == stdout.length() - 1,
                    () -> "the envelope must be exactly one LF-terminated line: " + describe(result, testKey));
            assertTrue(
                    schemas.validateText("envelope-success.schema.json", stdout).isEmpty(),
                    () -> "the envelope must satisfy envelope-success.schema.json: " + describe(result, testKey));

            JsonNode envelope = READER.readTree(result.stdout());
            assertEquals("answers", envelope.path("command").asText());
            JsonNode projection = envelope.path("data").path("projection");
            assertEquals("Grounded answer with citations.", projection.path("answer").asText());
            assertEquals(2, projection.path("citation_count").asInt());
            assertTrue(
                    schemas
                            .validateText(
                                    "answers-blocking.schema.json", projection.toString())
                            .isEmpty(),
                    () -> "the projection must satisfy answers-blocking.schema.json: " + projection);
            for (JsonNode citation : projection.path("citations")) {
                assertTrue(
                        schemas.validateText("answers-blocking-citation.schema.json", citation.toString()).isEmpty(),
                        () -> "each citation must satisfy answers-blocking-citation.schema.json: " + citation);
            }
            JsonNode upstream = envelope.path("data").path("upstream");
            assertEquals("ans_02", upstream.path("id").asText(), "the lossless document rides data.upstream");
            assertEquals(1020, upstream.path("usage").path("total_tokens").asInt());
            JsonNode meta = envelope.path("meta");
            assertEquals(REQUEST_ID, meta.path("request_id").asText());
            assertEquals(RESPONSE_API_VERSION, meta.path("api_version").asText());
            assertEquals(1L, meta.path("usage").path("requests").asLong());
            assertEquals(2L, meta.path("usage").path("queries").asLong());
            assertEquals("0.0042", meta.path("usage").path("total_cost").asText());
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /** The tolerant citations carriage renders in the human document between the answer and the usage line. */
    public static void blockingCitationsRenderInTheHumanDocument(List<String> answersCommand) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = fixtureServer(BLOCKING_ANSWER_CITATIONS).start()) {
            ProcessHarness.ProcessResult result =
                    run(answersCommand, server, childEnvironment(sandbox.directory, testKey), "--no-stream", QUESTION);

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            assertEquals(
                    """
                    Answer for: what is the brave search api
                    Grounded answer with citations.

                    Citations:
                    1. https://search.brave.com/
                       an independent index
                    2. https://brave.com

                    Usage: requests 1, queries 2, tokens 900 in / 120 out, total cost 0.0042
                    """,
                    new String(result.stdout(), UTF_8),
                    () -> describe(result, testKey));
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /** Raw mode writes the served body bytes to stdout byte-identically, adding and removing nothing. */
    public static void rawOutputIsTheServedBodyBytesExactly(List<String> answersCommand) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = fixtureServer().start()) {
            ProcessHarness.ProcessResult result = run(
                    answersCommand, server, childEnvironment(sandbox.directory, testKey), "--output", "raw", "--no-stream", QUESTION);

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            assertArrayEquals(BLOCKING_ANSWER, result.stdout(), "raw stdout must be byte-identical to the served body");
            assertEquals("", new String(result.stderr(), UTF_8), () -> describe(result, testKey));
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /**
     * The minimal blocking request POSTs exactly one user message with the stream flag
     * false and no model member — Brave selects its own — while the full blocking request
     * pins every member's exact nested bytes, and no search control ever rides the top
     * level.
     */
    public static void theBlockingRequestBodyTravelsNestedAndExact(List<String> answersCommand) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = fixtureServer().start()) {
            ProcessHarness.ProcessResult minimal =
                    run(answersCommand, server, childEnvironment(sandbox.directory, testKey), "--no-stream", QUESTION);
            assertEquals(0, minimal.exitStatus(), () -> describe(minimal, testKey));
            ScriptedSseServer.RecordedRequest recordedMinimal = server.requests().getFirst();
            assertEquals("POST", recordedMinimal.method());
            assertEquals("/chat/completions", recordedMinimal.path());
            assertEquals(
                    "application/json",
                    recordedMinimal.firstHeader("Content-Type").orElseThrow(),
                    "the POST body rides the JSON content type");
            assertEquals(MINIMAL_BODY, new String(recordedMinimal.body(), UTF_8), "the minimal body is exact");

            ProcessHarness.ProcessResult full = run(
                    answersCommand,
                    server,
                    childEnvironment(sandbox.directory, testKey),
                    "--no-stream",
                    "--model",
                    "future-model-x",
                    "--max-completion-tokens",
                    "1024",
                    "--seed",
                    "42",
                    "--country",
                    "US",
                    "--language",
                    "en",
                    "--safe-search",
                    "strict",
                    "--no-citations",
                    "--no-entities",
                    QUESTION);
            assertEquals(0, full.exitStatus(), () -> describe(full, testKey));
            String servedBody = new String(server.requests().get(1).body(), UTF_8);
            assertEquals(FULL_BODY, servedBody, "the full body is exact and nested");
            JsonNode served = READER.readTree(servedBody);
            List<String> topLevel = new ArrayList<>();
            served.properties().forEach(field -> topLevel.add(field.getKey()));
            assertEquals(
                    List.of("max_completion_tokens", "messages", "model", "seed", "stream", "web_search_options"),
                    topLevel.stream().sorted().toList(),
                    "the top level carries exactly the chat members plus the one nested options container");
            assertTrue(served.path("web_search_options").has("country"), "the search controls ride nested");
            assertTokenMaterialAbsent(minimal, testKey);
            assertTokenMaterialAbsent(full, testKey);
        }
    }

    /** A 401 keeps the authentication exit: one diagnostic line in human mode, one failure envelope in JSON mode. */
    public static void authenticationFailureExitsFourInHumanAndJsonModes(List<String> answersCommand) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = ScriptedSseServer.builder()
                        .statusCode(401)
                        .header("Content-Type", "application/json")
                        .writeBytes(UNAUTHORIZED_ERROR)
                        .start()) {
            ProcessHarness.ProcessResult human =
                    run(answersCommand, server, childEnvironment(sandbox.directory, testKey), "--no-stream", QUESTION);
            assertEquals(4, human.exitStatus(), () -> describe(human, testKey));
            assertEquals("", new String(human.stdout(), UTF_8), () -> describe(human, testKey));
            List<String> diagnostics = new String(human.stderr(), UTF_8).lines().toList();
            assertEquals(1, diagnostics.size(), () -> "exactly one diagnostic line: " + describe(human, testKey));
            assertTrue(
                    diagnostics.getFirst().startsWith("answers: "),
                    () -> "the diagnostic names the command: " + describe(human, testKey));
            assertTokenMaterialAbsent(human, testKey);

            ProcessHarness.ProcessResult json = run(
                    answersCommand,
                    server,
                    childEnvironment(sandbox.directory, testKey),
                    "--output",
                    "json",
                    "--no-stream",
                    QUESTION);
            assertEquals(4, json.exitStatus(), () -> describe(json, testKey));
            String stdout = new String(json.stdout(), UTF_8);
            assertTrue(
                    new SchemaCatalog().validateText("envelope-error.schema.json", stdout).isEmpty(),
                    () -> "the failure envelope must satisfy envelope-error.schema.json: " + describe(json, testKey));
            JsonNode envelope = READER.readTree(json.stdout());
            assertFalse(envelope.path("ok").asBoolean());
            assertEquals("AUTHENTICATION_FAILED", envelope.path("error").path("code").asText());
            assertEquals(401, envelope.path("meta").path("http_status").asInt());
            assertTokenMaterialAbsent(json, testKey);
        }
    }

    /**
     * Every local usage rejection predates any dispatch: each research member without the
     * research flag, research and enabled citations, entities, and research thinking on a
     * blocking request, the JSONL channel on a blocking request, the research bounds, and
     * the shared spellings this endpoint leaves undocumented.
     */
    public static void usageFailuresHappenBeforeAnyNetworkDispatch(List<String> answersCommand) throws Exception {
        ScriptedSseServer.Builder fixture = fixtureServer();
        usageFailureHappensBeforeAnyNetworkDispatch(
                answersCommand, fixture, "requires --research", SANDBOX_PREFIX, "--research-seconds", "60", QUESTION);
        usageFailureHappensBeforeAnyNetworkDispatch(
                answersCommand, fixture, "requires --research", SANDBOX_PREFIX, "--research-thinking", QUESTION);
        usageFailureHappensBeforeAnyNetworkDispatch(
                answersCommand, fixture, "--research requires --stream", SANDBOX_PREFIX, "--no-stream", "--research", QUESTION);
        usageFailureHappensBeforeAnyNetworkDispatch(
                answersCommand, fixture, "--citations requires --stream", SANDBOX_PREFIX, "--no-stream", "--citations", QUESTION);
        usageFailureHappensBeforeAnyNetworkDispatch(
                answersCommand, fixture, "--entities requires --stream", SANDBOX_PREFIX, "--no-stream", "--entities", QUESTION);
        usageFailureHappensBeforeAnyNetworkDispatch(
                answersCommand,
                fixture,
                "jsonl",
                SANDBOX_PREFIX,
                "--output",
                "jsonl",
                "--no-stream",
                QUESTION);
        usageFailureHappensBeforeAnyNetworkDispatch(
                answersCommand, fixture, "between 1024 and 16384", SANDBOX_PREFIX, "--research", "--research-tokens-per-query", "16385", QUESTION);
        usageFailureHappensBeforeAnyNetworkDispatch(
                answersCommand, fixture, "is not accepted by this command", SANDBOX_PREFIX, "--search-lang", "en", QUESTION);
        usageFailureHappensBeforeAnyNetworkDispatch(
                answersCommand, fixture, "Usage:", SANDBOX_PREFIX, "--freshness", "pw", QUESTION);
    }

    /**
     * A streamed answer reaches the consumer before the server completes its script: the
     * server withholds its remaining events behind a latch, and only the child's already
     * written output — a human text run, the first JSONL delta record, or the first raw
     * bytes — opens that latch. Human, JSONL, and raw each prove the same precedence in
     * their own channel.
     */
    public static void streamedOutputPrecedesServerCompletionInEveryMode(List<String> answersCommand)
            throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX)) {
            Map<String, String> environment = childEnvironment(sandbox.directory, testKey);
            readBeforeCompletion(
                    answersCommand, environment, "human", "Brave Search is an independent index.");
            readBeforeCompletion(
                    answersCommand,
                    environment,
                    "jsonl",
                    "\"type\":\"answer_delta\",\"command\":\"answers\",\"sequence\":0,\"text\":\"Brave Search is an independent index.\"");
            readBeforeCompletion(answersCommand, environment, "raw", "data: {\"choices\"");
        }
    }

    /**
     * The full happy stream, contract by contract: the human document with its appended
     * citations, entities, and exact usage line; the JSONL record sequence — two sequenced
     * deltas, the citation, the entity, the research progress record, the preserved unknown
     * tag, and the terminal summary whose usage is decimal-exact — every line
     * schema-validated; and raw stdout byte-identical to the served decoded SSE bytes.
     */
    public static void theFullHappyStreamMatchesEveryModeContract(List<String> answersCommand) throws Exception {
        String testKey = freshToken();
        SchemaCatalog schemas = new SchemaCatalog();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = streamFixture().start()) {
            Map<String, String> environment = childEnvironment(sandbox.directory, testKey);

            ProcessHarness.ProcessResult human =
                    run(answersCommand, server, environment, "--output", "human", QUESTION);
            assertEquals(0, human.exitStatus(), () -> describe(human, testKey));
            assertEquals(EXPECTED_STREAM_HUMAN, new String(human.stdout(), UTF_8), () -> describe(human, testKey));
            assertEquals(
                    "answers: research queries: {\"queries\":[\"brave api\"]}\n"
                            + "answers: upstream event <weird>\n",
                    new String(human.stderr(), UTF_8),
                    () -> "research progress and unknown tags are the human stream's stderr lines: "
                            + describe(human, testKey));

            ProcessHarness.ProcessResult jsonl =
                    run(answersCommand, server, environment, "--output", "jsonl", QUESTION);
            assertEquals(0, jsonl.exitStatus(), () -> describe(jsonl, testKey));
            String[] lines = new String(jsonl.stdout(), UTF_8).split("\n", -1);
            assertEquals(
                    7,
                    lines.length - 1,
                    "two deltas, the citation, the entity, the research record, the preserved tag, and the summary: "
                            + describe(jsonl, testKey));
            for (String line : lines) {
                if (!line.isEmpty()) {
                    assertTrue(
                            schemas.validateText("jsonl-record.schema.json", line).isEmpty(),
                            () -> "every record validates: " + line);
                }
            }
            assertEquals(
                    "{\"schema_version\":\"1\",\"type\":\"answer_delta\",\"command\":\"answers\",\"sequence\":0,"
                            + "\"text\":\"Brave Search is an independent index.\"}",
                    lines[0]);
            assertEquals(
                    "{\"schema_version\":\"1\",\"type\":\"answer_delta\",\"command\":\"answers\",\"sequence\":1,"
                            + "\"text\":\" With citations.\"}",
                    lines[1]);
            assertTrue(lines[2].contains("\"type\":\"citation\"") && lines[2].contains("https://brave.com"), lines[2]);
            assertTrue(lines[3].contains("\"type\":\"entity\"") && lines[3].contains("Brave Search"), lines[3]);
            assertTrue(lines[4].contains("\"type\":\"research_progress\"") && lines[4].contains("\"tag\":\"queries\""), lines[4]);
            assertTrue(lines[5].contains("\"type\":\"upstream_event\"") && lines[5].contains("\"tag\":\"weird\""), lines[5]);
            JsonNode summary = READER.readTree(lines[6]);
            assertEquals("summary", summary.path("type").asText());
            assertEquals(2, summary.path("deltas_emitted").asInt());
            assertEquals(1, summary.path("citations_seen").asInt());
            assertEquals(1, summary.path("entities_seen").asInt());
            assertEquals("0.0042", summary.path("usage").path("total_cost").asText());
            assertTrue(!summary.path("cost_unknown").asBoolean());
            assertEquals("", new String(jsonl.stderr(), UTF_8), () -> describe(jsonl, testKey));

            ProcessHarness.ProcessResult raw =
                    run(answersCommand, server, environment, "--output", "raw", QUESTION);
            assertEquals(0, raw.exitStatus(), () -> describe(raw, testKey));
            assertArrayEquals(STREAM_BODY, raw.stdout(), "raw stdout is the served SSE bytes exactly");
            assertEquals("", new String(raw.stderr(), UTF_8), () -> describe(raw, testKey));
            assertTokenMaterialAbsent(human, testKey);
            assertTokenMaterialAbsent(jsonl, testKey);
            assertTokenMaterialAbsent(raw, testKey);
        }
    }

    /**
     * Buffered JSON streaming emits exactly one LF-terminated envelope that satisfies both
     * envelope schemas, carries the decoded event array in {@code data.upstream}, the
     * answer and the citation and entity projections, the observed usage at decimal scale,
     * and the jsonl-is-preferable advisory in {@code meta.warnings}.
     */
    public static void bufferedJsonStreamsOneEnvelopeWithTheAdvisory(List<String> answersCommand) throws Exception {
        String testKey = freshToken();
        SchemaCatalog schemas = new SchemaCatalog();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = streamFixture().start()) {
            ProcessHarness.ProcessResult result = run(
                    answersCommand,
                    server,
                    childEnvironment(sandbox.directory, testKey),
                    "--output",
                    "json",
                    QUESTION);

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            String stdout = new String(result.stdout(), UTF_8);
            assertTrue(stdout.indexOf('\n') == stdout.length() - 1, () -> describe(result, testKey));
            assertTrue(
                    schemas.validateText("envelope-success.schema.json", stdout).isEmpty(),
                    () -> "the streaming envelope satisfies envelope-success.schema.json: " + describe(result, testKey));
            assertTrue(
                    schemas.validateText("envelope-success-streaming.schema.json", stdout).isEmpty(),
                    () -> "the streaming envelope satisfies envelope-success-streaming.schema.json: "
                            + describe(result, testKey));
            JsonNode envelope = READER.readTree(stdout);
            JsonNode projection = envelope.path("data").path("projection");
            assertEquals(
                    "Brave Search is an independent index. With citations.", projection.path("answer").asText());
            assertEquals(1, projection.path("citation_count").asInt());
            assertEquals("https://brave.com", projection.path("citations").get(0).path("url").asText());
            assertEquals(1, projection.path("entity_count").asInt());
            JsonNode upstream = envelope.path("data").path("upstream");
            assertTrue(upstream.isArray() && upstream.size() >= 7, () -> describe(result, testKey));
            assertEquals("0.0042", envelope.path("meta").path("usage").path("total_cost").asText());
            JsonNode warnings = envelope.path("meta").path("warnings");
            assertEquals(1, warnings.size());
            assertTrue(warnings.get(0).asText().contains("jsonl is preferable"), warnings.get(0).asText());
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /**
     * A research stream carries the whole nested research family with citations and
     * entities — the combination this CLI forwards rather than rejects — and its progress
     * events arrive as research_progress records; the pinned idle override proves the
     * deadline wiring end to end without waiting out the documented default.
     */
    public static void researchStreamsProgressRecordsAndNestedWireOptions(List<String> answersCommand)
            throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = streamFixture().start()) {
            ProcessHarness.ProcessResult result = run(
                    answersCommand,
                    server,
                    childEnvironment(sandbox.directory, testKey),
                    "--output",
                    "jsonl",
                    "--research",
                    "--research-thinking",
                    "--research-tokens-per-query",
                    "4096",
                    "--research-queries",
                    "10",
                    "--research-iterations",
                    "3",
                    "--research-seconds",
                    "60",
                    "--research-results-per-query",
                    "30",
                    "--citations",
                    "--entities",
                    QUESTION);

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            String stdout = new String(result.stdout(), UTF_8);
            assertTrue(stdout.contains("\"type\":\"research_progress\""), () -> describe(result, testKey));
            JsonNode served = READER.readTree(server.requests().getFirst().body());
            JsonNode options = served.path("web_search_options");
            assertTrue(served.path("stream").asBoolean(), "research rides a stream");
            assertEquals(true, options.path("enable_research").asBoolean(), "the research flag rides nested");
            assertEquals(true, options.path("enable_citations").asBoolean(), "citations ride nested beside research");
            assertEquals(true, options.path("enable_entities").asBoolean(), "entities ride nested beside research");
            assertEquals(true, options.path("research_allow_thinking").asBoolean());
            assertEquals(4096, options.path("research_maximum_tokens_per_query").asInt());
            assertEquals(10, options.path("research_maximum_queries").asInt());
            assertEquals(3, options.path("research_maximum_iterations").asInt());
            assertEquals(60, options.path("research_maximum_seconds").asInt());
            assertEquals(30, options.path("research_maximum_results_per_query").asInt());
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /**
     * A real {@code kill -INT} mid-stream latches the interruption first: the process exits
     * 130, the JSONL channel closes with its interruption error record carrying the partial
     * counts, and the human channel writes one diagnostic naming the interruption — never a
     * fabricated completion.
     */
    public static void interruptingMidStreamExitsOneThirtyWithPartialCounts(List<String> answersCommand)
            throws Exception {
        String testKey = freshToken();
        CountDownLatch withheld = new CountDownLatch(1);
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = ScriptedSseServer.builder()
                        .statusCode(200)
                        .header("Content-Type", "text/event-stream")
                        .writeBytes(sseDelta("partial answer "))
                        .flush()
                        .writeBytes(sseDelta("continues"))
                        .stallUntil(withheld)
                        .heartbeatUntilClosed("hold")
                        .start()) {
            Map<String, String> environment = childEnvironment(sandbox.directory, testKey);

            ProcessHarness.Session jsonlChild = start(answersCommand, server, environment, "--output", "jsonl", QUESTION);
            byte[] seen = readUntilContains(jsonlChild.stdout(), "\"sequence\":0".getBytes(UTF_8), PROBE_DEADLINE);
            jsonlChild.signal("INT");
            assertTrue(jsonlChild.awaitExit(PROBE_DEADLINE), "the interrupted child terminates promptly");
            assertEquals(130, jsonlChild.exitValue(), () -> "stderr=<" + new String(jsonlChild.stderr(), UTF_8) + ">");
            byte[] rest = drainAfterExit(jsonlChild);
            String records = new String(concat(seen, rest), UTF_8);
            String[] lines = records.split("\n", -1);
            String terminal = lines[lines.length - 2];
            JsonNode error = READER.readTree(terminal);
            assertEquals("error", error.path("type").asText());
            assertEquals("INTERRUPTED", error.path("code").asText());
            assertTrue(error.path("deltas_emitted").asInt() >= 1, terminal);
            assertTrue(error.path("cost_unknown").asBoolean(), terminal);
            jsonlChild.close();

            ProcessHarness.Session humanChild = start(answersCommand, server, environment, QUESTION);
            readUntilContains(humanChild.stdout(), "partial answer ".getBytes(UTF_8), PROBE_DEADLINE);
            humanChild.signal("INT");
            assertTrue(humanChild.awaitExit(PROBE_DEADLINE));
            assertEquals(130, humanChild.exitValue());
            List<String> diagnostics = new String(humanChild.stderr(), UTF_8).lines().toList();
            assertEquals(1, diagnostics.size(), () -> "one interruption diagnostic: " + diagnostics);
            assertTrue(diagnostics.getFirst().contains("interrupted"), () -> diagnostics.toString());
            drainAfterExit(humanChild);
            humanChild.close();
        } finally {
            withheld.countDown();
        }
    }

    /**
     * A real {@code kill -TERM} mid-stream latches the termination first: the process exits
     * 143 on both runtimes, the JSONL channel closes with its termination error record
     * carrying the partial counts, and the human channel writes one diagnostic naming the
     * termination — never a fabricated completion.
     */
    public static void terminatingMidStreamExitsOneFortyThreeWithTheTerminalRecord(List<String> answersCommand)
            throws Exception {
        String testKey = freshToken();
        CountDownLatch withheld = new CountDownLatch(1);
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = ScriptedSseServer.builder()
                        .statusCode(200)
                        .header("Content-Type", "text/event-stream")
                        .writeBytes(sseDelta("partial answer "))
                        .flush()
                        .writeBytes(sseDelta("continues"))
                        .stallUntil(withheld)
                        .heartbeatUntilClosed("hold")
                        .start()) {
            Map<String, String> environment = childEnvironment(sandbox.directory, testKey);

            ProcessHarness.Session jsonlChild = start(answersCommand, server, environment, "--output", "jsonl", QUESTION);
            byte[] seen = readUntilContains(jsonlChild.stdout(), "\"sequence\":0".getBytes(UTF_8), PROBE_DEADLINE);
            jsonlChild.signal("TERM");
            assertTrue(jsonlChild.awaitExit(PROBE_DEADLINE), "the terminated child terminates promptly");
            assertEquals(143, jsonlChild.exitValue(), () -> "stderr=<" + new String(jsonlChild.stderr(), UTF_8) + ">");
            byte[] rest = drainAfterExit(jsonlChild);
            String records = new String(concat(seen, rest), UTF_8);
            String[] lines = records.split("\n", -1);
            String terminal = lines[lines.length - 2];
            JsonNode error = READER.readTree(terminal);
            assertEquals("error", error.path("type").asText());
            assertEquals("TERMINATED", error.path("code").asText());
            assertTrue(error.path("deltas_emitted").asInt() >= 1, terminal);
            assertEquals("", new String(jsonlChild.stderr(), UTF_8), "jsonl owns its failure explanation on stdout");
            jsonlChild.close();

            ProcessHarness.Session humanChild = start(answersCommand, server, environment, QUESTION);
            readUntilContains(humanChild.stdout(), "partial answer ".getBytes(UTF_8), PROBE_DEADLINE);
            humanChild.signal("TERM");
            assertTrue(humanChild.awaitExit(PROBE_DEADLINE));
            assertEquals(143, humanChild.exitValue());
            List<String> diagnostics = new String(humanChild.stderr(), UTF_8).lines().toList();
            assertEquals(1, diagnostics.size(), () -> "one termination diagnostic: " + diagnostics);
            assertTrue(diagnostics.getFirst().contains("terminated"), () -> diagnostics.toString());
            drainAfterExit(humanChild);
            humanChild.close();
        } finally {
            withheld.countDown();
        }
    }

    /**
     * A consumer closing the stdout pipe mid-stream is silent success in every streaming
     * mode: the child latches the broken pipe, cancels its exchange — the server observes
     * the disconnect — and exits 0 with no diagnostic.
     */
    public static void closingTheDownstreamPipeMidStreamIsSilentZero(List<String> answersCommand) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = ScriptedSseServer.builder()
                        .statusCode(200)
                        .header("Content-Type", "text/event-stream")
                        .writeBytesUntilClosed(STREAM_BODY)
                        .start()) {
            Map<String, String> environment = childEnvironment(sandbox.directory, testKey);
            pipeCloseIsSilentZero(answersCommand, server, environment, "--output", "raw", QUESTION);
            pipeCloseIsSilentZero(answersCommand, server, environment, "--output", "jsonl", QUESTION);
            pipeCloseIsSilentZero(answersCommand, server, environment, QUESTION);
        }
    }

    /** The pinned idle deadline cuts a stalled stream: exit 6 and one diagnostic line. */
    public static void anIdleStreamIsCutByItsDeadline(List<String> answersCommand) throws Exception {
        String testKey = freshToken();
        CountDownLatch stall = new CountDownLatch(1);
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = ScriptedSseServer.builder()
                        .statusCode(200)
                        .header("Content-Type", "text/event-stream")
                        .writeBytes(sseDelta("one event"))
                        .flush()
                        .stallUntil(stall)
                        .start()) {
            ProcessHarness.ProcessResult result = run(
                    answersCommand,
                    server,
                    childEnvironment(sandbox.directory, testKey),
                    "--idle-timeout",
                    "250ms",
                    QUESTION);

            assertEquals(6, result.exitStatus(), () -> describe(result, testKey));
            List<String> diagnostics = new String(result.stderr(), UTF_8).lines().toList();
            assertEquals(1, diagnostics.size(), () -> describe(result, testKey));
            assertTrue(diagnostics.getFirst().contains("idle"), () -> describe(result, testKey));
            assertTrue(
                    new String(result.stdout(), UTF_8).startsWith("Answer for: "),
                    () -> "the already streamed text stays: " + describe(result, testKey));
            assertTokenMaterialAbsent(result, testKey);
        } finally {
            stall.countDown();
        }
    }

    /** Heartbeats reset the idle window but never the wall: a heartbeating stream still exits 6. */
    public static void aWallDeadlineCutsAHeartbeatingStream(List<String> answersCommand) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = ScriptedSseServer.builder()
                        .statusCode(200)
                        .header("Content-Type", "text/event-stream")
                        .writeBytes(sseDelta("event"))
                        .heartbeatUntilClosed("tick")
                        .start()) {
            ProcessHarness.ProcessResult result = run(
                    answersCommand,
                    server,
                    childEnvironment(sandbox.directory, testKey),
                    "--stream-timeout",
                    "500ms",
                    QUESTION);

            assertEquals(6, result.exitStatus(), () -> describe(result, testKey));
            List<String> diagnostics = new String(result.stderr(), UTF_8).lines().toList();
            assertEquals(1, diagnostics.size(), () -> describe(result, testKey));
            assertTrue(diagnostics.getFirst().contains("wall-clock"), () -> describe(result, testKey));
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /**
     * A body that breaks before its terminator is the transport failure with the
     * cost-unknown marker: the JSONL error record carries {@code cost_unknown:true}, and
     * the human diagnostic names the break and the unknown cost — never a zero cost.
     */
    public static void anAbruptEndWithoutUsageIsATransportFailureWithUnknownCost(List<String> answersCommand)
            throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = ScriptedSseServer.builder()
                        .statusCode(200)
                        .header("Content-Type", "text/event-stream")
                        .writeBytes(sseDelta("partial"))
                        .abruptClose()
                        .start()) {
            Map<String, String> environment = childEnvironment(sandbox.directory, testKey);

            ProcessHarness.ProcessResult jsonl = run(
                    answersCommand, server, environment, "--output", "jsonl", QUESTION);
            assertEquals(6, jsonl.exitStatus(), () -> describe(jsonl, testKey));
            String[] lines = new String(jsonl.stdout(), UTF_8).split("\n", -1);
            JsonNode error = READER.readTree(lines[lines.length - 2]);
            assertEquals("error", error.path("type").asText());
            assertEquals("TRANSPORT_ERROR", error.path("code").asText());
            assertTrue(error.path("cost_unknown").asBoolean(), () -> describe(jsonl, testKey));

            ProcessHarness.ProcessResult human = run(answersCommand, server, environment, QUESTION);
            assertEquals(6, human.exitStatus(), () -> describe(human, testKey));
            List<String> diagnostics = new String(human.stderr(), UTF_8).lines().toList();
            assertEquals(1, diagnostics.size(), () -> describe(human, testKey));
            assertTrue(
                    diagnostics.getFirst().contains("cost unknown"),
                    () -> "the diagnostic states the unknown cost: " + describe(human, testKey));
            assertTokenMaterialAbsent(human, testKey);
            assertTokenMaterialAbsent(jsonl, testKey);
        }
    }

    /** A stream-open authentication failure keeps exit 4 and each mode's failure document. */
    public static void authenticationFailureAtStreamOpenExitsFour(List<String> answersCommand) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = ScriptedSseServer.builder()
                        .statusCode(401)
                        .header("Content-Type", "application/json")
                        .writeBytes(UNAUTHORIZED_ERROR)
                        .start()) {
            Map<String, String> environment = childEnvironment(sandbox.directory, testKey);

            ProcessHarness.ProcessResult human = run(answersCommand, server, environment, QUESTION);
            assertEquals(4, human.exitStatus(), () -> describe(human, testKey));
            assertEquals("", new String(human.stdout(), UTF_8), () -> describe(human, testKey));
            assertEquals(
                    1,
                    new String(human.stderr(), UTF_8).lines().count(),
                    () -> describe(human, testKey));

            ProcessHarness.ProcessResult json = run(
                    answersCommand, server, environment, "--output", "json", QUESTION);
            assertEquals(4, json.exitStatus(), () -> describe(json, testKey));
            assertTrue(
                    new SchemaCatalog()
                                    .validateText("envelope-error.schema.json", new String(json.stdout(), UTF_8))
                                    .isEmpty(),
                    () -> describe(json, testKey));
            assertTokenMaterialAbsent(json, testKey);
        }
    }

    /** A malformed usage payload and an unfinished tag at the terminator both keep exit 8. */
    public static void malformedDecodeFailuresKeepExitEight(List<String> answersCommand) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer malformedUsage = ScriptedSseServer.builder()
                        .statusCode(200)
                        .header("Content-Type", "text/event-stream")
                        .writeBytes(sseDelta("<usage>not json</usage>"))
                        .writeBytes(DONE_MARKER)
                        .start();
                ScriptedSseServer unfinishedTag = ScriptedSseServer.builder()
                        .statusCode(200)
                        .header("Content-Type", "text/event-stream")
                        .writeBytes(sseDelta("<citation>{\"url\":\"https://brave.com\""))
                        .writeBytes(DONE_MARKER)
                        .start()) {
            for (ScriptedSseServer server : List.of(malformedUsage, unfinishedTag)) {
                ProcessHarness.ProcessResult human =
                        run(answersCommand, server, childEnvironment(sandbox.directory, testKey), QUESTION);
                assertEquals(8, human.exitStatus(), () -> describe(human, testKey));
                assertEquals(
                        1,
                        new String(human.stderr(), UTF_8).lines().count(),
                        () -> describe(human, testKey));

                ProcessHarness.ProcessResult jsonl = run(
                        answersCommand,
                        server,
                        childEnvironment(sandbox.directory, testKey),
                        "--output",
                        "jsonl",
                        QUESTION);
                assertEquals(8, jsonl.exitStatus(), () -> describe(jsonl, testKey));
                String[] lines = new String(jsonl.stdout(), UTF_8).split("\n", -1);
                JsonNode error = READER.readTree(lines[lines.length - 2]);
                assertEquals("MALFORMED_RESPONSE", error.path("code").asText());
                assertTokenMaterialAbsent(jsonl, testKey);
            }
        }
    }

    /** The blocking total deadline never applies to a stream that outlives it. */
    public static void theBlockingTotalDeadlineNeverCutsAStream(List<String> answersCommand) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = ScriptedSseServer.builder()
                        .statusCode(200)
                        .header("Content-Type", "text/event-stream")
                        .delay(java.time.Duration.ofMillis(1500))
                        .writeBytes(STREAM_BODY)
                        .start()) {
            ProcessHarness.ProcessResult result = run(
                    answersCommand,
                    server,
                    childEnvironment(sandbox.directory, testKey),
                    "--timeout",
                    "150ms",
                    QUESTION);

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            assertTrue(
                    new String(result.stdout(), UTF_8).contains("independent index"),
                    () -> describe(result, testKey));
        }
    }

    /** A consumer that reads slower than the stream still receives every byte of it. */
    public static void aSlowConsumerStillReceivesTheWholeStream(List<String> answersCommand) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = streamFixture().start()) {
            List<String> full = new ArrayList<>(answersCommand);
            full.add("--base-url");
            full.add(server.baseUrl().toString());
            full.addAll(List.of("--output", "raw", QUESTION));
            try (ProcessHarness.Session session =
                    new ProcessHarness().start(full, childEnvironment(sandbox.directory, testKey), SCRUBBED)) {
                java.io.ByteArrayOutputStream received = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[7];
                int read;
                while (received.size() < STREAM_BODY.length
                        && (read = session.stdout().read(buffer)) >= 0) {
                    received.write(buffer, 0, read);
                    Thread.sleep(5);
                }
                assertTrue(
                        session.awaitExit(PROBE_DEADLINE),
                        () -> "the child completes once the slow consumer caught up");
                assertEquals(0, session.exitValue());
                assertArrayEquals(STREAM_BODY, received.toByteArray(), "slow reading loses no stream byte");
            }
        }
    }

    /**
     * A loopback run authenticates with the test key, never forwards a stored credential,
     * and dispatches exactly one POST whose body is the exact nested blocking form.
     */
    public static void loopbackRunsSendTheTestKeyOnTheBlockingPost(List<String> answersCommand) throws Exception {
        String testKey = freshToken();
        String storedDecoy = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = fixtureServer().start()) {
            Map<String, String> environment = childEnvironment(sandbox.directory, testKey);
            environment.put("BRAVE_API_KEY", storedDecoy);
            ProcessHarness.ProcessResult result =
                    run(answersCommand, server, environment, "--no-stream", QUESTION);

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey, storedDecoy));
            assertTrue(
                    new String(result.stdout(), UTF_8).startsWith("Answer for: "),
                    () -> "the exchange must have succeeded: " + describe(result, testKey, storedDecoy));

            List<ScriptedSseServer.RecordedRequest> requests = server.requests();
            assertEquals(1, requests.size(), "an answers lookup is exactly one request");
            assertEquals(
                    Optional.of(testKey),
                    requests.getFirst().firstHeader("X-Subscription-Token"),
                    "the token on the wire must be the loopback test key");
            for (List<String> values : requests.getFirst().headers().values()) {
                for (String value : values) {
                    assertFalse(value.contains(storedDecoy), "the stored credential must never reach a loopback peer");
                }
            }
            assertEquals(MINIMAL_BODY, new String(requests.getFirst().body(), UTF_8));
            assertTokenMaterialAbsent(result, testKey, storedDecoy);
        }
    }

    /** The Api-Version pin travels verbatim on the wire; an impossible calendar date never leaves the process. */
    public static void apiVersionPinReachesTheWireAndImpossibleDatesFailUsage(List<String> answersCommand)
            throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = fixtureServer().start()) {
            ProcessHarness.ProcessResult pinned = run(
                    answersCommand,
                    server,
                    childEnvironment(sandbox.directory, testKey),
                    "--api-version",
                    RESPONSE_API_VERSION,
                    "--no-stream",
                    QUESTION);
            assertEquals(0, pinned.exitStatus(), () -> describe(pinned, testKey));
            assertEquals(
                    Optional.of(RESPONSE_API_VERSION),
                    server.requests().getFirst().firstHeader("Api-Version"),
                    "the pinned version must travel on the wire exactly as spelled");
            assertTokenMaterialAbsent(pinned, testKey);

            int requestsBefore = server.requests().size();
            ProcessHarness.ProcessResult impossible = run(
                    answersCommand,
                    server,
                    childEnvironment(sandbox.directory, testKey),
                    "--api-version",
                    "2026-13-99",
                    "--no-stream",
                    QUESTION);
            assertEquals(2, impossible.exitStatus(), () -> describe(impossible, testKey));
            assertTrue(
                    new String(impossible.stderr(), UTF_8).contains("Usage:"),
                    () -> "an impossible date is a usage failure: " + describe(impossible, testKey));
            assertEquals(requestsBefore, server.requests().size(), "an impossible date must never be dispatched anywhere");
            assertTokenMaterialAbsent(impossible, testKey);
        }
    }

    /** A downstream consumer closing the pipe early is silent success: exit exactly 0, no diagnostic. */
    public static void downstreamPipeCloseIsSilentZero(List<String> answersCommand) throws Exception {
        String testKey = freshToken();
        byte[] oversized = oversizedAnswerBody();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = ScriptedSseServer.builder()
                        .statusCode(200)
                        .header("Content-Type", "application/json")
                        .writeBytes(oversized)
                        .start()) {
            List<String> full = new ArrayList<>(answersCommand);
            full.add("--base-url");
            full.add(server.baseUrl().toString());
            full.addAll(List.of("--output", "raw", "--no-stream", QUESTION));
            try (ProcessHarness.Session session =
                    new ProcessHarness().start(full, childEnvironment(sandbox.directory, testKey), SCRUBBED)) {
                java.io.InputStream childStdout = session.stdout();
                byte[] opening = new byte[16];
                int held = 0;
                while (held < opening.length) {
                    int chunk = childStdout.read(opening, held, opening.length - held);
                    if (chunk < 0) {
                        break;
                    }
                    held += chunk;
                }
                childStdout.close();
                assertTrue(
                        session.awaitExit(java.time.Duration.ofSeconds(60)),
                        "the child must terminate once the consumer closed the pipe");
                assertEquals(
                        0,
                        session.exitValue(),
                        () -> "a closed downstream pipe is successful early termination, stderr=<"
                                + new String(session.stderr(), UTF_8) + ">");
                assertEquals("", new String(session.stderr(), UTF_8), "early termination stays silent");
            }
        }
    }

    /**
     * A consumer that departs before reading a single byte — the heading write is the first
     * write to strike the closed pipe — is silent success: the child exits exactly 0 and
     * writes no diagnostic. The server withholds its whole script behind a delay, so the
     * child is parked awaiting headers while the consumer closes the pipe.
     */
    public static void aConsumerClosingBeforeTheFirstReadExitsZeroSilently(List<String> answersCommand)
            throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = ScriptedSseServer.builder()
                        .statusCode(200)
                        .header("Content-Type", "text/event-stream")
                        .delay(java.time.Duration.ofMillis(750))
                        .writeBytes(STREAM_BODY)
                        .start()) {
            List<String> full = new ArrayList<>(answersCommand);
            full.add("--base-url");
            full.add(server.baseUrl().toString());
            full.add(QUESTION);
            try (ProcessHarness.Session session =
                    new ProcessHarness().start(full, childEnvironment(sandbox.directory, testKey), SCRUBBED)) {
                assertTrue(server.awaitFirstRequest(PROBE_DEADLINE), "the child must be parked awaiting headers");
                session.stdout().close();
                assertTrue(
                        session.awaitExit(java.time.Duration.ofSeconds(60)),
                        () -> "the child must terminate once the consumer closed the pipe before any byte"
                                + ", stderr=<" + new String(session.stderr(), UTF_8) + ">");
                assertEquals(
                        0,
                        session.exitValue(),
                        () -> "the heading's broken pipe is silent success, stderr=<"
                                + new String(session.stderr(), UTF_8) + ">");
                assertEquals("", new String(session.stderr(), UTF_8), "early termination stays silent");
            }
        }
    }

    /** The exact human document the streamed fixture renders, every line LF-terminated. */
    private static final String EXPECTED_STREAM_HUMAN = """
            Answer for: what is the brave search api
            Brave Search is an independent index. With citations.

            Citations:
            1. https://brave.com
               a browser

            Entities:
            - Brave Search (https://search.brave.com)

            Usage: requests 1, queries 2, tokens 900 in / 120 out, total cost 0.0042
            """;

    /** The terminal marker of the served event stream. */
    private static final byte[] DONE_MARKER = "data: [DONE]\n\n".getBytes(UTF_8);

    /** The served streaming fixture: role, two text runs, citation, entity, research, unknown tag, usage, stop. */
    private static final byte[] STREAM_BODY = join(
            sseDelta(null),
            sseDelta("Brave Search is an independent index."),
            sseDelta(" With citations."),
            sseDelta("<citation>{\"number\":1,\"url\":\"https://brave.com\",\"snippet\":\"a browser\"}</citation>"),
            sseDelta("<entity>{\"name\":\"Brave Search\",\"url\":\"https://search.brave.com\"}</entity>"),
            sseDelta("<queries>{\"queries\":[\"brave api\"]}</queries>"),
            sseDelta("<weird>{not json}</weird>"),
            sseDelta("<usage>{\"requests\":1,\"queries\":2,\"tokens_in\":900,\"tokens_out\":120,\"total_cost\":\"0.0042\"}</usage>"),
            sseDeltaStop(),
            DONE_MARKER);

    /** One SSE data line carrying a content delta; a null content carries the opening role. */
    private static byte[] sseDelta(String content) {
        String delta = content == null
                ? "{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"}}]}"
                : "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + jsonEscaped(content) + "\"}}]}";
        return ("data: " + delta + "\n\n").getBytes(UTF_8);
    }

    /** Escapes exactly the two characters a hand-built JSON string line needs: quote and backslash. */
    private static String jsonEscaped(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (char current : text.toCharArray()) {
            switch (current) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                default -> escaped.append(current);
            }
        }
        return escaped.toString();
    }

    /** The finish chunk that carries no content, only the stop marker of the choice. */
    private static byte[] sseDeltaStop() {
        return ("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n")
                .getBytes(UTF_8);
    }

    private static byte[] join(byte[]... chunks) {
        int total = 0;
        for (byte[] chunk : chunks) {
            total += chunk.length;
        }
        byte[] joined = new byte[total];
        int at = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, joined, at, chunk.length);
            at += chunk.length;
        }
        return joined;
    }

    /**
     * An SSE block that carries {@code id}, {@code retry}, and {@code event} lines around an
     * unknown tag preserves them on the JSONL {@code upstream_event} record as
     * {@code sse_id}, {@code sse_retry_ms}, and {@code event_name}; a block without them
     * omits every one of the three members.
     */
    public static void upstreamEventsPreserveTheSseEnvelopeMetadata(List<String> answersCommand) throws Exception {
        String testKey = freshToken();
        byte[] enveloped = ("id: evt-9\nretry: 500\nevent: weather-alert\n"
                + "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"<weather>{}</weather>\"}}]}\n\n")
                .getBytes(UTF_8);
        byte[] plain = sseDelta("<plain>{}</plain>");
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer envelopedServer = streamFixtureScript(enveloped).start();
                ScriptedSseServer plainServer = streamFixtureScript(plain).start()) {
            SchemaCatalog schemas = new SchemaCatalog();

            ProcessHarness.ProcessResult envelopedResult =
                    run(answersCommand, envelopedServer, childEnvironment(sandbox.directory, testKey), "--output", "jsonl", QUESTION);
            assertEquals(0, envelopedResult.exitStatus(), () -> describe(envelopedResult, testKey));
            String[] lines = new String(envelopedResult.stdout(), UTF_8).split("\n", -1);
            assertTrue(
                    schemas.validateText("jsonl-record.schema.json", lines[0]).isEmpty(),
                    () -> "the envelope-carrying record still validates: " + lines[0]);
            assertTrue(
                    lines[0].contains("\"type\":\"upstream_event\"")
                            && lines[0].contains("\"sse_id\":\"evt-9\"")
                            && lines[0].contains("\"sse_retry_ms\":500")
                            && lines[0].contains("\"event_name\":\"weather-alert\""),
                    () -> "the record carries the SSE envelope: " + lines[0]);
            assertTokenMaterialAbsent(envelopedResult, testKey);

            ProcessHarness.ProcessResult plainResult =
                    run(answersCommand, plainServer, childEnvironment(sandbox.directory, testKey), "--output", "jsonl", QUESTION);
            assertEquals(0, plainResult.exitStatus(), () -> describe(plainResult, testKey));
            String[] plainLines = new String(plainResult.stdout(), UTF_8).split("\n", -1);
            assertTrue(plainLines[0].contains("\"type\":\"upstream_event\""), plainLines[0]);
            assertFalse(
                    plainLines[0].contains("sse_id") || plainLines[0].contains("sse_retry_ms")
                            || plainLines[0].contains("event_name"),
                    () -> "a block without envelope members records none: " + plainLines[0]);
            assertTokenMaterialAbsent(plainResult, testKey);
        }
    }

    /** The streaming fixture exchange every streaming success scenario serves. */
    private static ScriptedSseServer.Builder streamFixture() {
        return ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "text/event-stream")
                .header("X-Request-ID", REQUEST_ID)
                .header("Api-Version", RESPONSE_API_VERSION)
                .writeBytes(STREAM_BODY);
    }

    /** A streaming fixture that serves the given event bytes and then the terminal marker. */
    private static ScriptedSseServer.Builder streamFixtureScript(byte[] events) {
        return ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "text/event-stream")
                .header("X-Request-ID", REQUEST_ID)
                .header("Api-Version", RESPONSE_API_VERSION)
                .writeBytes(events)
                .writeBytes(DONE_MARKER);
    }

    /**
     * The incrementality witness: the server withholds everything after its first delta
     * behind a latch, and the child must show the expected stdout bytes — optionally the
     * two fragments in order — before the test releases that latch and the script can end.
     */
    private static void readBeforeCompletion(
            List<String> answersCommand, Map<String, String> environment, String outputMode, String expected)
            throws Exception {
        CountDownLatch withheld = new CountDownLatch(1);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "text/event-stream")
                .writeBytes(sseDelta("Brave Search is an independent index."))
                .flush()
                .stallUntil(withheld)
                .writeBytes(sseDelta(" With citations."))
                .writeBytes(DONE_MARKER)
                .start()) {
            List<String> full = new ArrayList<>(answersCommand);
            full.add("--base-url");
            full.add(server.baseUrl().toString());
            full.add("--output");
            full.add(outputMode);
            full.add(QUESTION);
            try (ProcessHarness.Session session = new ProcessHarness().start(full, environment, SCRUBBED)) {
                final byte[] witnessed =
                        readUntilContains(session.stdout(), expected.getBytes(UTF_8), PROBE_DEADLINE);
                assertTrue(
                        server.awaitFirstRequest(PROBE_DEADLINE), "the exchange opened before the output arrived");
                withheld.countDown();
                assertTrue(session.awaitExit(PROBE_DEADLINE), "the child completes once the script ends");
                assertEquals(0, session.exitValue(), () -> "stdout so far=<" + new String(witnessed, UTF_8) + ">");
            }
        } finally {
            withheld.countDown();
        }
    }

    /** Starts one streaming child against the scripted server for interactive scenarios. */
    private static ProcessHarness.Session start(
            List<String> answersCommand, ScriptedSseServer server, Map<String, String> environment, String... arguments)
            throws IOException {
        List<String> full = new ArrayList<>(answersCommand);
        full.add("--base-url");
        full.add(server.baseUrl().toString());
        full.addAll(List.of(arguments));
        return new ProcessHarness().start(full, environment, SCRUBBED);
    }

    /** One streaming run whose consumer leaves mid-stream: silent zero, promptly. */
    private static void pipeCloseIsSilentZero(
            List<String> answersCommand,
            ScriptedSseServer server,
            Map<String, String> environment,
            String... arguments)
            throws Exception {
        try (ProcessHarness.Session session = start(answersCommand, server, environment, arguments)) {
            byte[] opening = new byte[16];
            int held = 0;
            while (held < opening.length) {
                int chunk = session.stdout().read(opening, held, opening.length - held);
                if (chunk < 0) {
                    break;
                }
                held += chunk;
            }
            session.stdout().close();
            assertTrue(
                    session.awaitExit(PROBE_DEADLINE),
                    "the child terminates once the consumer closed the pipe");
            assertEquals(
                    0,
                    session.exitValue(),
                    () -> "a closed downstream pipe is successful early termination, stderr=<"
                            + new String(session.stderr(), UTF_8) + ">");
            assertEquals("", new String(session.stderr(), UTF_8), "early termination stays silent");
        }
        assertTrue(server.awaitConnectionClosed(PROBE_DEADLINE), "the server observed the cancelled exchange");
    }

    /** An answer body far beyond any pipe buffer, so the raw writer is still writing when a consumer leaves. */
    private static byte[] oversizedAnswerBody() {
        return ("{\"choices\":[{\"message\":{\"content\":\"" + "x".repeat(96 * 1024) + "\"}}]}").getBytes(UTF_8);
    }

    /** The fixture exchange every success scenario serves, with the Answers usage headers. */
    private static ScriptedSseServer.Builder fixtureServer() {
        return fixtureServer(BLOCKING_ANSWER);
    }

    private static ScriptedSseServer.Builder fixtureServer(byte[] body) {
        return ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .header("X-RateLimit-Limit", "1,15")
                .header("X-RateLimit-Policy", "request,minute")
                .header("X-RateLimit-Remaining", "0,14")
                .header("X-RateLimit-Reset", "1,42")
                .header("X-Request-ID", REQUEST_ID)
                .header("Api-Version", RESPONSE_API_VERSION)
                .header("X-Request-Requests", "1")
                .header("X-Request-Queries", "2")
                .header("X-Request-Tokens-In", "900")
                .header("X-Request-Tokens-Out", "120")
                .header("X-Request-Total-Cost", "0.0042")
                .writeBytes(body);
    }
}

package io.amscotti.bravesearch.testsupport;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The LLM context vertical-slice scenarios, run against a whole CLI process and a scripted
 * loopback server. Each scenario takes the command prefix that launches the context command
 * — the installed JVM launcher plus the subcommand token, or the native binary plus the same
 * token — so both executables must show identical evidence: the LF-only human context
 * document with its trailing count line, exactly one schema-valid JSON success envelope
 * whose projection carries the two stable counts and whose upstream stays lossless down to
 * the spot decimal, JSONL's single context result record followed by one summary record,
 * raw stdout byte-identical to the served body, the authentication failure's exit status,
 * usage rejections that predate any network dispatch, the loopback test-key routing that
 * never forwards a stored credential and dispatches exactly one non-paginated request, and
 * the full option set reaching the wire under its documented spellings.
 *
 * <p>Every child runs with a scrubbed credential environment over a private temporary home,
 * and every scenario asserts recursively that its unique token material appears nowhere in
 * the captured streams: the tokens are alphanumeric, so byte-level absence over stdout and
 * stderr is absence at every nesting depth of any rendered document.
 */
public final class ContextScenarios {

    /**
     * Reads captured machine documents at exact decimal scale: the scenario's own reader must
     * be at least as lossless as the documents it judges, or a double parse would invent the
     * very fidelity loss these scenarios deny.
     */
    private static final ObjectMapper READER =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    /** The served two-passage context body, including the sources map and exact-scale decimals. */
    private static final byte[] CONTEXT_BODY = """
            {
              "query": {
                "original": "three word query"
              },
              "grounding": {
                "generic": [
                  {
                    "text": "First context passage about the query subject."
                  },
                  {
                    "text": "Second passage, déjà vu, with an emoji ☕."
                  }
                ]
              },
              "sources": {
                "https://example.com/first": {
                  "title": "First Source"
                },
                "https://example.org/second": {
                  "title": "Second Source"
                }
              },
              "unknown_future_block": {
                "cost": 1.10,
                "long": 0.1000000000000000000001
              }
            }
            """
            .getBytes(UTF_8);

    /** The served structured upstream error body of an authentication failure. */
    private static final byte[] UNAUTHORIZED_ERROR =
            "{\"error\":{\"code\":\"unauthorized\",\"detail\":\"recovery hint\"}}\n".getBytes(UTF_8);

    /** The exact human document the two-passage body renders, every line LF-terminated. */
    private static final String EXPECTED_HUMAN = """
            Context for: three word query

            First context passage about the query subject.

            Second passage, déjà vu, with an emoji ☕.
            2 snippets across 2 sources.
            """;

    /** The exact encoded query of the full-option request under the documented wire names. */
    private static final String FULL_WIRE_QUERY = "context_threshold_mode=balanced&count=7&country=DE&enable_local=false"
            + "&enable_source_metadata=true&freshness=2026-01-01to2026-02-28"
            + "&maximum_number_of_snippets=100&maximum_number_of_snippets_per_url=50"
            + "&maximum_number_of_tokens=4096&maximum_number_of_tokens_per_url=2048"
            + "&maximum_number_of_urls=10&q=hello%20world&safesearch=strict&search_lang=de";

    /** The request identifier the fixture response offers through its header. */
    private static final String REQUEST_ID = "req-context-7f3a";

    /** The API version the fixture response reports through its header. */
    private static final String RESPONSE_API_VERSION = "2026-08-30";

    /** Inherited variables scrubbed from every child so ambient credentials cannot interfere. */
    private static final Set<String> SCRUBBED =
            Set.of(
                    "BRAVE_API_KEY",
                    "BRAVE_SEARCH_API_KEY",
                    "BRAVE_SEARCH_TEST_KEY",
                    "XDG_CONFIG_HOME",
                    "JAVA_OPTS",
                    "NO_COLOR",
                    "CLICOLOR",
                    "CLICOLOR_FORCE",
                    "COLUMNS",
                    "TERM");

    /** How long a scenario child may take to start, answer, and react. */
    private static final java.time.Duration PROBE_DEADLINE = java.time.Duration.ofSeconds(30);

    private ContextScenarios() {}

    /** A 200 fixture exchange renders the human context document, LF-only, without any ANSI byte. */
    public static void humanHappyPathRendersTheContextDocumentLfOnlyWithoutAnsi(List<String> contextCommand)
            throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create();
                ScriptedSseServer server = contextFixtureServer()) {
            ProcessHarness.ProcessResult result =
                    run(contextCommand, server, childEnvironment(sandbox.directory, testKey), "three word query");

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            assertEquals(EXPECTED_HUMAN, new String(result.stdout(), UTF_8), () -> describe(result, testKey));
            assertFalse(new String(result.stdout(), UTF_8).contains("\r"), "human output must use LF endings only");
            assertFalse(
                    new String(result.stdout(), UTF_8).contains("\u001b"), "human output must carry no ANSI escapes");
            assertEquals("", new String(result.stderr(), UTF_8), () -> describe(result, testKey));
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /** JSON mode emits exactly one LF-terminated success envelope that validates and stays lossless. */
    public static void jsonEnvelopeIsOneValidatedLfLineWithCountsAndLosslessUpstream(List<String> contextCommand)
            throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create();
                ScriptedSseServer server = contextFixtureServer()) {
            ProcessHarness.ProcessResult result = run(
                    contextCommand, server, childEnvironment(sandbox.directory, testKey), "--output", "json", "three word query");

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            String stdout = new String(result.stdout(), UTF_8);
            assertTrue(
                    stdout.indexOf('\n') == stdout.length() - 1,
                    () -> "the envelope must be exactly one LF-terminated line: " + describe(result, testKey));
            assertTrue(
                    new SchemaCatalog().validateText("envelope-success.schema.json", stdout).isEmpty(),
                    () -> "the envelope must satisfy envelope-success.schema.json: " + describe(result, testKey));

            JsonNode envelope = READER.readTree(result.stdout());
            assertEquals("1", envelope.path("schema_version").asText());
            assertTrue(envelope.path("ok").asBoolean());
            assertEquals("context", envelope.path("command").asText());
            JsonNode projection = envelope.path("data").path("projection");
            assertEquals(2, projection.path("snippet_count").asInt());
            assertEquals(2, projection.path("source_count").asInt());
            JsonNode upstream = envelope.path("data").path("upstream");
            assertTrue(
                    stdout.contains("0.1000000000000000000001"),
                    "the spot decimal must be on stdout byte-for-byte");
            assertTrue(stdout.contains("\"cost\":1.10"), "the trailing-zero cost must be on stdout byte-for-byte");
            assertEquals(
                    "0.1000000000000000000001",
                    upstream.path("unknown_future_block").path("long").asText(),
                    "the spot decimal must survive the whole process at exact scale");
            assertEquals(
                    2, upstream.path("sources").size(), "the sources map stays part of the lossless tree");
            JsonNode meta = envelope.path("meta");
            assertEquals(REQUEST_ID, meta.path("request_id").asText());
            assertEquals(200, meta.path("http_status").asInt());
            assertEquals(RESPONSE_API_VERSION, meta.path("api_version").asText());
            assertTrue(meta.path("warnings").isEmpty(), "the served headers carry no advisory notes");
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /** JSONL mode emits exactly one context result record and then the summary record, both validated. */
    public static void jsonlEmitsOneContextRecordThenTheSummary(List<String> contextCommand) throws Exception {
        String testKey = freshToken();
        SchemaCatalog schemas = new SchemaCatalog();
        try (Sandbox sandbox = Sandbox.create();
                ScriptedSseServer server = contextFixtureServer()) {
            ProcessHarness.ProcessResult result = run(
                    contextCommand, server, childEnvironment(sandbox.directory, testKey), "--output", "jsonl", "three word query");

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            String stdout = new String(result.stdout(), UTF_8);
            assertFalse(stdout.contains("\r"), "JSONL lines must be LF-terminated only");
            String[] lines = stdout.split("\n", -1);
            assertEquals(
                    3,
                    lines.length,
                    () -> "one context record, one summary record, one terminator: " + describe(result, testKey));

            assertTrue(
                    schemas.validateText("jsonl-record.schema.json", lines[0]).isEmpty(),
                    () -> "the context record must satisfy jsonl-record.schema.json: " + lines[0]);
            JsonNode record = READER.readTree(lines[0]);
            assertEquals("result", record.path("type").asText());
            assertEquals("context", record.path("command").asText());
            assertEquals("context", record.path("bucket").asText());
            assertTrue(record.path("content").asText().startsWith("First context passage"));

            assertTrue(
                    schemas.validateText("jsonl-record.schema.json", lines[1]).isEmpty(),
                    () -> "the summary record must satisfy jsonl-record.schema.json: " + lines[1]);
            JsonNode summary = READER.readTree(lines[1]);
            assertEquals("summary", summary.path("type").asText());
            assertEquals("context", summary.path("command").asText());
            assertEquals(2, summary.path("snippet_count").asInt());
            assertEquals(2, summary.path("source_count").asInt());
            assertEquals(200, summary.path("http_status").asInt());
            assertEquals(REQUEST_ID, summary.path("request_id").asText());
            assertEquals(RESPONSE_API_VERSION, summary.path("api_version").asText());
            assertEquals("", new String(result.stderr(), UTF_8), () -> describe(result, testKey));
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /** Raw mode writes the served body bytes to stdout byte-identically, adding and removing nothing. */
    public static void rawOutputIsTheServedBodyBytesExactly(List<String> contextCommand) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create();
                ScriptedSseServer server = contextFixtureServer()) {
            ProcessHarness.ProcessResult result = run(
                    contextCommand, server, childEnvironment(sandbox.directory, testKey), "--output", "raw", "three word query");

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            assertArrayEquals(CONTEXT_BODY, result.stdout(), "raw stdout must be byte-identical to the served body");
            assertEquals("", new String(result.stderr(), UTF_8), () -> describe(result, testKey));
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /** A 401 keeps the authentication exit: one diagnostic line in human mode, one failure envelope in JSON mode. */
    public static void authenticationFailureExitsFourInHumanAndJsonModes(List<String> contextCommand) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create();
                ScriptedSseServer server = ScriptedSseServer.builder()
                        .statusCode(401)
                        .header("Content-Type", "application/json")
                        .writeBytes(UNAUTHORIZED_ERROR)
                        .start()) {
            ProcessHarness.ProcessResult human =
                    run(contextCommand, server, childEnvironment(sandbox.directory, testKey), "three word query");
            assertEquals(4, human.exitStatus(), () -> describe(human, testKey));
            assertEquals("", new String(human.stdout(), UTF_8), () -> describe(human, testKey));
            List<String> diagnostics = new String(human.stderr(), UTF_8).lines().toList();
            assertEquals(1, diagnostics.size(), () -> "exactly one diagnostic line: " + describe(human, testKey));
            assertFalse(diagnostics.getFirst().contains("Exception"), "no raw stack trace may reach stderr");
            assertTokenMaterialAbsent(human, testKey);

            ProcessHarness.ProcessResult json = run(
                    contextCommand, server, childEnvironment(sandbox.directory, testKey), "--output", "json", "three word query");
            assertEquals(4, json.exitStatus(), () -> describe(json, testKey));
            String stdout = new String(json.stdout(), UTF_8);
            assertTrue(
                    new SchemaCatalog().validateText("envelope-error.schema.json", stdout).isEmpty(),
                    () -> "the failure envelope must satisfy envelope-error.schema.json: " + describe(json, testKey));
            JsonNode envelope = READER.readTree(json.stdout());
            assertFalse(envelope.path("ok").asBoolean());
            assertEquals("AUTHENTICATION_FAILED", envelope.path("error").path("code").asText());
            assertEquals("unauthorized", envelope.path("error").path("upstream_code").asText());
            assertEquals(401, envelope.path("meta").path("http_status").asInt());
            assertTokenMaterialAbsent(json, testKey);
        }
    }

    /** A usage-invalid budget is rejected with the usage exit before the server can observe any request. */
    public static void usageFailureHappensBeforeAnyNetworkDispatch(List<String> contextCommand) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create();
                ScriptedSseServer server = contextFixtureServer()) {
            ProcessHarness.ProcessResult result = run(
                    contextCommand, server, childEnvironment(sandbox.directory, testKey), "--max-tokens", "1023", "three word query");

            assertEquals(2, result.exitStatus(), () -> describe(result, testKey));
            String stderr = new String(result.stderr(), UTF_8);
            assertTrue(stderr.contains("Usage:"), () -> "usage must appear on stderr: " + describe(result, testKey));
            assertEquals("", new String(result.stdout(), UTF_8), () -> describe(result, testKey));
            assertEquals(0, server.requests().size(), "an invalid request must never be dispatched");
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /**
     * A loopback run authenticates with the test key, never forwards a stored credential, and
     * dispatches exactly one request — the single non-paginated exchange the endpoint defines.
     */
    public static void loopbackRunSendsTheTestKeyAndExactlyOneRequest(List<String> contextCommand) throws Exception {
        String testKey = freshToken();
        String storedDecoy = freshToken();
        try (Sandbox sandbox = Sandbox.create();
                ScriptedSseServer server = contextFixtureServer()) {
            Map<String, String> environment = childEnvironment(sandbox.directory, testKey);
            environment.put("BRAVE_API_KEY", storedDecoy);
            ProcessHarness.ProcessResult result =
                    run(contextCommand, server, environment, "three word query");

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey, storedDecoy));
            assertTrue(
                    new String(result.stdout(), UTF_8).startsWith("Context for: "),
                    () -> "the exchange must have succeeded: " + describe(result, testKey, storedDecoy));

            List<ScriptedSseServer.RecordedRequest> requests = server.requests();
            assertEquals(1, requests.size(), "a context retrieval is exactly one request, never a walk");
            assertEquals(
                    "/llm/context?q=three%20word%20query",
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

    /**
     * The full option set reaches the wire under its documented parameter spellings and the
     * seven documented location headers — and no timezone header travels, because the
     * context endpoint documents none.
     */
    public static void fullOptionsReachTheWireUnderTheirDocumentedNames(List<String> contextCommand) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create();
                ScriptedSseServer server = contextFixtureServer()) {
            ProcessHarness.ProcessResult result =
                    run(
                            contextCommand,
                            server,
                            childEnvironment(sandbox.directory, testKey),
                            "--country",
                            "DE",
                            "--search-lang",
                            "de",
                            "--safe-search",
                            "strict",
                            "--freshness",
                            "2026-01-01to2026-02-28",
                            "--count",
                            "7",
                            "--max-urls",
                            "10",
                            "--max-tokens",
                            "4096",
                            "--max-snippets",
                            "100",
                            "--max-tokens-per-url",
                            "2048",
                            "--max-snippets-per-url",
                            "50",
                            "--threshold",
                            "balanced",
                            "--source-metadata",
                            "--local",
                            "off",
                            "--loc-lat",
                            "47.6062",
                            "--loc-long",
                            "-122.3321",
                            "--loc-city",
                            "Seattle",
                            "--loc-state",
                            "WA",
                            "--loc-state-name",
                            "Washington",
                            "--loc-country",
                            "US",
                            "--loc-postal-code",
                            "98101",
                            "hello world");

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            ScriptedSseServer.RecordedRequest request = server.requests().getFirst();
            assertEquals("/llm/context?" + FULL_WIRE_QUERY, request.path(), "the wire query is the documented spelling");
            assertEquals(Optional.of("Seattle"), request.firstHeader("X-Loc-City"));
            assertEquals(Optional.of("US"), request.firstHeader("X-Loc-Country"));
            assertEquals(Optional.of("47.6062"), request.firstHeader("X-Loc-Lat"));
            assertEquals(Optional.of("-122.3321"), request.firstHeader("X-Loc-Long"));
            assertEquals(Optional.of("98101"), request.firstHeader("X-Loc-Postal-Code"));
            assertEquals(Optional.of("WA"), request.firstHeader("X-Loc-State"));
            assertEquals(Optional.of("Washington"), request.firstHeader("X-Loc-State-Name"));
            assertTrue(
                    request.headers().keySet().stream().noneMatch(name -> name.toLowerCase().contains("timezone")),
                    "no timezone header may travel on a context exchange");
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /**
     * A SIGINT that lands while the single context exchange sits parked on a stalled
     * response body ends the run by the conventional interrupt status 130: the human mode
     * renders exactly one stderr diagnostic naming the cancelled exchange, and the json
     * mode renders exactly one failure envelope carrying the transport error code.
     */
    public static void sigintDuringBlockedBodyExits130(List<String> contextCommand) throws Exception {
        blockedBodySignalExit(contextCommand, "INT", 130);
    }

    /**
     * A SIGTERM that lands while the single context exchange sits parked on a stalled
     * response body keeps the same contract with the conventional termination status 143.
     */
    public static void sigtermDuringBlockedBodyExits143(List<String> contextCommand) throws Exception {
        blockedBodySignalExit(contextCommand, "TERM", 143);
    }

    /**
     * A signal that lands after the context exchange completed and printed its document
     * changes nothing: the completed run keeps its success status 0.
     */
    public static void signalAfterCompletionKeepsZero(List<String> contextCommand) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create();
                ScriptedSseServer server = contextFixtureServer()) {
            List<String> human = withBaseUrl(contextCommand, server);
            human.add("three word query");
            try (ProcessHarness.Session session =
                    new ProcessHarness().start(human, childEnvironment(sandbox.directory, testKey), SCRUBBED)) {
                byte[] seen = readUntilContains(
                        session.stdout(), "2 snippets across 2 sources.".getBytes(UTF_8), PROBE_DEADLINE);

                session.signal("INT");

                assertTrue(session.awaitExit(PROBE_DEADLINE), "the completed run must still exit on its own");
                assertEquals(
                        0,
                        session.exitValue(),
                        () -> "a completed run keeps its success status, stderr=<"
                                + new String(session.stderr(), UTF_8)
                                + ">, stdout=<"
                                + new String(seen, UTF_8)
                                + ">");
            }
        }
    }

    /**
     * A downstream consumer closing the pipe early is silent success: the child exits
     * exactly 0, writes no diagnostic, and produces no error banner. The served body
     * exceeds any pipe buffer, so the child is still writing when the consumer closes
     * after a few bytes — the observed status is the child's own, not the consumer's.
     */
    public static void downstreamPipeCloseIsSilentZero(List<String> contextCommand) throws Exception {
        String testKey = freshToken();
        byte[] oversized = oversizedContextBody();
        try (Sandbox sandbox = Sandbox.create();
                ScriptedSseServer server = ScriptedSseServer.builder()
                        .statusCode(200)
                        .header("Content-Type", "application/json")
                        .writeBytes(oversized)
                        .start()) {
            List<String> full = withBaseUrl(contextCommand, server);
            full.addAll(List.of("--output", "raw", "three word query"));
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
     * A 200 body that is not readable JSON renders the malformed failure document of the
     * mode and keeps exit 8: one stderr diagnostic in human mode, one failure envelope
     * carrying the malformed code in json mode.
     */
    public static void garbageSuccessBodyExitsEight(List<String> contextCommand) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create();
                ScriptedSseServer server = ScriptedSseServer.builder()
                        .statusCode(200)
                        .header("Content-Type", "application/json")
                        .writeBytes("gateway exploded <html>".getBytes(UTF_8))
                        .start()) {
            ProcessHarness.ProcessResult human =
                    run(contextCommand, server, childEnvironment(sandbox.directory, testKey), "three word query");
            assertEquals(8, human.exitStatus(), () -> describe(human, testKey));
            assertEquals("", new String(human.stdout(), UTF_8), () -> describe(human, testKey));
            List<String> diagnostics = new String(human.stderr(), UTF_8).lines().toList();
            assertEquals(1, diagnostics.size(), () -> "exactly one diagnostic line: " + describe(human, testKey));
            assertTrue(
                    diagnostics.getFirst().startsWith("context: "),
                    () -> "the diagnostic names the command: " + describe(human, testKey));
            assertTokenMaterialAbsent(human, testKey);

            ProcessHarness.ProcessResult json = run(
                    contextCommand,
                    server,
                    childEnvironment(sandbox.directory, testKey),
                    "--output",
                    "json",
                    "three word query");
            assertEquals(8, json.exitStatus(), () -> describe(json, testKey));
            String stdout = new String(json.stdout(), UTF_8);
            assertTrue(
                    new SchemaCatalog().validateText("envelope-error.schema.json", stdout).isEmpty(),
                    () -> "the failure envelope must satisfy envelope-error.schema.json: " + describe(json, testKey));
            JsonNode envelope = READER.readTree(json.stdout());
            assertFalse(envelope.path("ok").asBoolean());
            assertEquals("MALFORMED_RESPONSE", envelope.path("error").path("code").asText());
            assertTokenMaterialAbsent(json, testKey);
        }
    }

    private static void blockedBodySignalExit(List<String> contextCommand, String signal, int expectedExit)
            throws Exception {
        java.util.concurrent.CountDownLatch neverRelease = new java.util.concurrent.CountDownLatch(1);
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create();
                ScriptedSseServer server = ScriptedSseServer.builder()
                        .statusCode(200)
                        .header("Content-Type", "application/json")
                        .writeBytes("{\"grounding\":{\"generic\":[{\"text\":\"partial".getBytes(UTF_8))
                        .flush()
                        .stallUntil(neverRelease)
                        .start()) {
            List<String> human = withBaseUrl(contextCommand, server);
            human.add("three word query");
            try (ProcessHarness.Session session =
                    new ProcessHarness().start(human, childEnvironment(sandbox.directory, testKey), SCRUBBED)) {
                assertTrue(server.awaitFirstRequest(PROBE_DEADLINE), "the exchange must reach the scripted server");
                Thread.sleep(750);

                session.signal(signal);

                assertTrue(session.awaitExit(PROBE_DEADLINE), "a signalled exchange must exit promptly");
                assertEquals(
                        expectedExit,
                        session.exitValue(),
                        () -> "the latched signal owns the status, stderr=<"
                                + new String(session.stderr(), UTF_8) + ">");
                assertEquals("", new String(drainAfterExit(session), UTF_8), "a buffered mode emits no partial payload");
                List<String> diagnostics = new String(session.stderr(), UTF_8).lines().toList();
                assertEquals(1, diagnostics.size(), () -> "exactly one diagnostic line: " + diagnostics);
                assertTrue(
                        diagnostics.getFirst().startsWith("context: "),
                        () -> "the diagnostic names the command: " + diagnostics);
            }

            List<String> json = withBaseUrl(contextCommand, server);
            json.addAll(List.of("--output", "json", "three word query"));
            int jsonRequestCount = server.requests().size() + 1;
            try (ProcessHarness.Session session =
                    new ProcessHarness().start(json, childEnvironment(sandbox.directory, testKey), SCRUBBED)) {
                assertTrue(server.awaitRequestCount(jsonRequestCount, PROBE_DEADLINE), "the current exchange must reach the scripted server");
                Thread.sleep(750);

                session.signal(signal);

                assertTrue(session.awaitExit(PROBE_DEADLINE), "a signalled exchange must exit promptly");
                assertEquals(
                        expectedExit,
                        session.exitValue(),
                        () -> "the latched signal owns the status, stderr=<"
                                + new String(session.stderr(), UTF_8) + ">");
                String stdout = new String(drainAfterExit(session), UTF_8);
                String[] lines = stdout.split("\n", -1);
                JsonNode envelope = READER.readTree(lines[lines.length - 2]);
                assertFalse(envelope.path("ok").asBoolean(), "the signalled run renders the failure envelope");
                assertEquals("TRANSPORT_ERROR", envelope.path("error").path("code").asText());
                assertEquals(
                        1,
                        new String(session.stderr(), UTF_8).lines().count(),
                        "json renders its envelope on stdout and one diagnostic line on stderr");
            }
        } finally {
            neverRelease.countDown();
        }
    }

    /** A success body far larger than any pipe buffer, so a writer must block mid-body. */
    private static byte[] oversizedContextBody() {
        return ("{\"grounding\":{\"generic\":[{\"text\":\"" + "x".repeat(256 * 1024) + "\"}]}}")
                .getBytes(UTF_8);
    }

    /** The command with the hidden loopback override spelled once. */
    private static List<String> withBaseUrl(List<String> contextCommand, ScriptedSseServer server) {
        List<String> full = new ArrayList<>(contextCommand);
        full.add("--base-url");
        full.add(server.baseUrl().toString());
        return full;
    }

    /** Reads the child's stdout until it contains {@code expected}, returning everything seen. */
    private static byte[] readUntilContains(java.io.InputStream stream, byte[] expected, java.time.Duration timeout)
            throws IOException, InterruptedException {
        java.io.ByteArrayOutputStream seen = new java.io.ByteArrayOutputStream();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            int readable = stream.available();
            if (readable > 0) {
                byte[] buffer = new byte[readable];
                int read = stream.read(buffer, 0, readable);
                if (read > 0) {
                    seen.write(buffer, 0, read);
                    if (contains(seen.toByteArray(), expected)) {
                        return seen.toByteArray();
                    }
                }
            } else {
                Thread.sleep(20);
            }
        }
        throw new IOException(
                "timed out waiting for stdout to contain <" + new String(expected, UTF_8) + ">, saw <" + seen + ">");
    }

    /** Everything still in flight once the child has exited; silence means end of stream here. */
    private static byte[] drainAfterExit(ProcessHarness.Session session) throws IOException, InterruptedException {
        java.io.ByteArrayOutputStream rest = new java.io.ByteArrayOutputStream();
        long hardDeadline = System.nanoTime() + PROBE_DEADLINE.toNanos();
        long quietSince = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < hardDeadline) {
            int readable = session.stdout().available();
            if (readable > 0) {
                byte[] buffer = new byte[readable];
                int read = session.stdout().read(buffer, 0, readable);
                if (read > 0) {
                    rest.write(buffer, 0, read);
                    quietSince = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
                }
            } else if (System.nanoTime() > quietSince) {
                break;
            } else {
                Thread.sleep(20);
            }
        }
        return rest.toByteArray();
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int start = 0; start + needle.length <= haystack.length; start++) {
            for (int i = 0; i < needle.length; i++) {
                if (haystack[start + i] != needle[i]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** The fixture exchange every success scenario serves: two passages, two sources, identifiers. */
    private static ScriptedSseServer contextFixtureServer() throws IOException {
        return ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .header("X-Request-ID", REQUEST_ID)
                .header("Api-Version", RESPONSE_API_VERSION)
                .writeBytes(CONTEXT_BODY)
                .start();
    }

    /**
     * Launches the context command once against the scripted server: the hidden loopback
     * override, then the scenario's own arguments, all inside the given environment.
     */
    private static ProcessHarness.ProcessResult run(
            List<String> contextCommand, ScriptedSseServer server, Map<String, String> environment, String... arguments)
            throws IOException {
        List<String> command = new ArrayList<>(contextCommand);
        command.add("--base-url");
        command.add(server.baseUrl().toString());
        command.addAll(List.of(arguments));
        return new ProcessHarness().launch(command, environment, new byte[0], SCRUBBED);
    }

    /**
     * The child environment of a loopback run: a private home, deterministic no-color settings,
     * and — when the scenario wants an authenticated run — the loopback test key. Every
     * credential variable was scrubbed beforehand, so ambient values cannot survive here.
     */
    private static Map<String, String> childEnvironment(Path sandbox, String testKey) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("HOME", sandbox.toString());
        environment.put("JAVA_OPTS", "-Duser.home=" + sandbox);
        environment.put("NO_COLOR", "1");
        environment.put("TERM", "dumb");
        if (testKey != null) {
            environment.put("BRAVE_SEARCH_TEST_KEY", testKey);
        }
        return environment;
    }

    /**
     * A unique random-looking token per invocation: it is embedded in credential material under
     * test, so any appearance in captured child output is a leak of the token itself. The token
     * is alphanumeric only, so no rendering can split or escape it.
     */
    private static String freshToken() {
        return "BSK" + Long.toUnsignedString(UUID.randomUUID().getMostSignificantBits(), 36)
                + Long.toUnsignedString(UUID.randomUUID().getLeastSignificantBits(), 36);
    }

    /** Byte-level absence over both streams is recursive absence for alphanumeric tokens. */
    private static void assertTokenMaterialAbsent(ProcessHarness.ProcessResult result, String... tokens) {
        String stdout = new String(result.stdout(), UTF_8);
        String stderr = new String(result.stderr(), UTF_8);
        for (String token : tokens) {
            assertFalse(stdout.contains(token), "token material leaked to child stdout");
            assertFalse(stderr.contains(token), "token material leaked to child stderr");
        }
    }

    /** Command, exit status, and streams for reports, with every known token redacted first. */
    private static String describe(ProcessHarness.ProcessResult result, String... secrets) {
        String stdout = new String(result.stdout(), UTF_8);
        String stderr = new String(result.stderr(), UTF_8);
        for (String secret : secrets) {
            stdout = stdout.replace(secret, "<redacted>");
            stderr = stderr.replace(secret, "<redacted>");
        }
        return "exitStatus=" + result.exitStatus() + ", stdout=<" + stdout + ">, stderr=<" + stderr + ">";
    }

    /** A private home directory for one scenario, removed with everything under it on close. */
    private static final class Sandbox implements AutoCloseable {

        private final Path directory;

        private Sandbox(Path directory) {
            this.directory = directory;
        }

        static Sandbox create() throws IOException {
            return new Sandbox(Files.createTempDirectory("brave-context-process-"));
        }

        @Override
        public void close() {
            try (Stream<Path> files = Files.walk(directory)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            } catch (IOException unreadable) {
                // a leftover temporary home cannot affect any other scenario's isolation
            }
        }
    }
}

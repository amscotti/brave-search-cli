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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The harness of the whole-process search scenarios: the child-process plumbing every
 * search vertical's scenarios share — the scrubbed environment over a private temporary
 * home, the per-invocation credential tokens with their recursive absence check, the
 * redacted failure reports, the scripted-server launch, and the stdout probes — plus the
 * scenario bodies whose structure is identical across endpoints, parameterized over the
 * command prefix, the fixture exchange, and the expected text. Endpoint-specific evidence
 * (headings, age fields, bucket names) stays in each endpoint's own scenario class, which
 * extends this harness and adds its assertions.
 *
 * <p>Every child runs with a scrubbed credential environment, and every scenario asserts
 * recursively that its unique token material appears nowhere in the captured streams: the
 * tokens are alphanumeric, so byte-level absence over stdout and stderr is absence at every
 * nesting depth of any rendered document.
 */
public abstract class SearchScenarios {

    /**
     * Reads captured machine documents at exact decimal scale: the scenario's own reader must
     * be at least as lossless as the documents it judges, or a double parse would invent the
     * very fidelity loss these scenarios deny.
     */
    protected static final ObjectMapper READER =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    /** The served structured upstream error body of an authentication failure. */
    protected static final byte[] UNAUTHORIZED_ERROR =
            "{\"error\":{\"code\":\"unauthorized\",\"detail\":\"recovery hint\"}}\n".getBytes(UTF_8);

    /** The served structured upstream error body of a later page's server failure. */
    protected static final byte[] SERVER_ERROR =
            "{\"error\":{\"code\":\"unknown\",\"detail\":\"upstream exploded\"}}\n".getBytes(UTF_8);

    /** The served structured upstream error body of a rate-limited exchange. */
    protected static final byte[] RATE_LIMITED_ERROR =
            "{\"error\":{\"code\":\"rate_limited\",\"detail\":\"quota exhausted\"}}\n".getBytes(UTF_8);

    /** The served non-JSON 200 body of a garbage success exchange. */
    protected static final byte[] GARBAGE_SUCCESS = "gateway exploded <html>".getBytes(UTF_8);

    /** The request identifier the fixture response offers through its header. */
    protected static final String REQUEST_ID = "req-process-7f3a";

    /** The API version the fixture response reports through its header. */
    protected static final String RESPONSE_API_VERSION = "2026-08-30";

    /** Inherited variables scrubbed from every child so ambient credentials cannot interfere. */
    protected static final Set<String> SCRUBBED =
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
    protected static final java.time.Duration PROBE_DEADLINE = java.time.Duration.ofSeconds(30);

    /** A 200 fixture exchange renders the numbered human listing, LF-only, without any ANSI byte. */
    protected static void humanHappyPathRendersNumberedLfLinesWithoutAnsi(
            List<String> command, ScriptedSseServer.Builder fixture, String expectedHuman, String sandboxPrefix)
            throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(sandboxPrefix);
                ScriptedSseServer server = fixture.start()) {
            ProcessHarness.ProcessResult result =
                    run(command, server, childEnvironment(sandbox.directory, testKey), "three word query");

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            assertEquals(expectedHuman, new String(result.stdout(), UTF_8), () -> describe(result, testKey));
            assertFalse(new String(result.stdout(), UTF_8).contains("\r"), "human output must use LF endings only");
            assertFalse(
                    new String(result.stdout(), UTF_8).contains("\u001b"), "human output must carry no ANSI escapes");
            assertEquals("", new String(result.stderr(), UTF_8), () -> describe(result, testKey));
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /**
     * JSON mode emits exactly one LF-terminated success envelope that validates and stays
     * lossless; {@code endpointAssertions} receives the parsed envelope for the endpoint's
     * own projection and metadata evidence.
     */
    protected static void jsonSuccessEnvelopeIsOneValidatedLfLine(
            List<String> command,
            ScriptedSseServer.Builder fixture,
            String commandName,
            String firstResultTitle,
            Consumer<JsonNode> endpointAssertions,
            String sandboxPrefix)
            throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(sandboxPrefix);
                ScriptedSseServer server = fixture.start()) {
            ProcessHarness.ProcessResult result = run(
                    command, server, childEnvironment(sandbox.directory, testKey), "--output", "json", "three word query");

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
            assertEquals(commandName, envelope.path("command").asText());
            JsonNode projection = envelope.path("data").path("projection");
            assertEquals(3, projection.path("result_count").asInt());
            assertEquals(1, projection.path("page").asInt());
            assertEquals(0, projection.path("upstream_offset").asInt());
            assertEquals(3, projection.path("results").size());
            assertEquals(0, projection.path("results").get(0).path("position").asInt());
            assertEquals(firstResultTitle, projection.path("results").get(0).path("title").asText());
            JsonNode upstream = envelope.path("data").path("upstream");
            assertTrue(
                    stdout.contains("0.1000000000000000000001"),
                    "the spot decimal must be on stdout byte-for-byte");
            assertTrue(stdout.contains("\"cost\":1.10"), "the trailing-zero cost must be on stdout byte-for-byte");
            assertEquals(
                    "0.1000000000000000000001",
                    upstream.path("unknown_future_block").path("long").asText(),
                    "the spot decimal must survive the whole process at exact scale");
            endpointAssertions.accept(envelope);
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /**
     * JSONL mode emits one validated record per logical result and exactly one summary record
     * after them; {@code recordAssertions} receives the parsed result records for the
     * endpoint's own member evidence.
     */
    protected static void jsonlEmitsValidatedResultRecordsThenSummary(
            List<String> command,
            ScriptedSseServer.Builder fixture,
            String commandName,
            String bucket,
            Consumer<List<JsonNode>> recordAssertions,
            String sandboxPrefix)
            throws Exception {
        String testKey = freshToken();
        SchemaCatalog schemas = new SchemaCatalog();
        try (Sandbox sandbox = Sandbox.create(sandboxPrefix);
                ScriptedSseServer server = fixture.start()) {
            ProcessHarness.ProcessResult result = run(
                    command, server, childEnvironment(sandbox.directory, testKey), "--output", "jsonl", "three word query");

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            String stdout = new String(result.stdout(), UTF_8);
            assertFalse(stdout.contains("\r"), "JSONL lines must be LF-terminated only");
            String[] lines = stdout.split("\n", -1);
            assertEquals(
                    5,
                    lines.length,
                    () -> "three result records, one summary record, one terminator: " + describe(result, testKey));

            List<JsonNode> records = new ArrayList<>(3);
            for (int position = 0; position < 3; position++) {
                String line = lines[position];
                assertTrue(
                        schemas.validateText("jsonl-record.schema.json", line).isEmpty(),
                        () -> "result record must satisfy jsonl-record.schema.json: " + line);
                JsonNode record = READER.readTree(line);
                assertEquals("result", record.path("type").asText());
                assertEquals(commandName, record.path("command").asText());
                assertEquals(bucket, record.path("bucket").asText());
                assertEquals(position, record.path("position").asInt());
                records.add(record);
            }
            recordAssertions.accept(records);

            assertTrue(
                    schemas.validateText("jsonl-record.schema.json", lines[3]).isEmpty(),
                    () -> "summary record must satisfy jsonl-record.schema.json: " + lines[3]);
            JsonNode summary = READER.readTree(lines[3]);
            assertEquals("summary", summary.path("type").asText());
            assertEquals(commandName, summary.path("command").asText());
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
    protected static void rawOutputIsTheServedBodyBytesExactly(
            List<String> command, ScriptedSseServer.Builder fixture, byte[] servedBody, String sandboxPrefix)
            throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(sandboxPrefix);
                ScriptedSseServer server = fixture.start()) {
            ProcessHarness.ProcessResult result = run(
                    command, server, childEnvironment(sandbox.directory, testKey), "--output", "raw", "three word query");

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            assertEquals(
                    servedBody.length,
                    result.stdout().length,
                    () -> "raw stdout must hold exactly the served body bytes: " + describe(result, testKey));
            assertArrayEquals(servedBody, result.stdout(), "raw stdout must be byte-identical to the served body");
            assertEquals("", new String(result.stderr(), UTF_8), () -> describe(result, testKey));
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /** A 401 keeps the authentication exit: one diagnostic line in human mode, one failure envelope in JSON mode. */
    protected static void authenticationFailureExitsFourInHumanAndJsonModes(
            List<String> command, String commandName, String sandboxPrefix) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(sandboxPrefix);
                ScriptedSseServer server = ScriptedSseServer.builder()
                        .statusCode(401)
                        .header("Content-Type", "application/json")
                        .writeBytes(UNAUTHORIZED_ERROR)
                        .start()) {
            ProcessHarness.ProcessResult human =
                    run(command, server, childEnvironment(sandbox.directory, testKey), "three word query");
            assertEquals(4, human.exitStatus(), () -> describe(human, testKey));
            assertEquals("", new String(human.stdout(), UTF_8), () -> describe(human, testKey));
            List<String> diagnostics = new String(human.stderr(), UTF_8).lines().toList();
            assertEquals(1, diagnostics.size(), () -> "exactly one diagnostic line: " + describe(human, testKey));
            assertFalse(diagnostics.getFirst().contains("Exception"), "no raw stack trace may reach stderr");
            assertTrue(
                    diagnostics.getFirst().startsWith(commandName + ": "),
                    () -> "the diagnostic names the command: " + describe(human, testKey));
            assertTokenMaterialAbsent(human, testKey);

            ProcessHarness.ProcessResult json = run(
                    command, server, childEnvironment(sandbox.directory, testKey), "--output", "json", "three word query");
            assertEquals(4, json.exitStatus(), () -> describe(json, testKey));
            String stdout = new String(json.stdout(), UTF_8);
            assertTrue(
                    stdout.indexOf('\n') == stdout.length() - 1,
                    () -> "the failure envelope must be exactly one LF-terminated line: " + describe(json, testKey));
            assertTrue(
                    new SchemaCatalog().validateText("envelope-error.schema.json", stdout).isEmpty(),
                    () -> "the failure envelope must satisfy envelope-error.schema.json: " + describe(json, testKey));
            JsonNode envelope = READER.readTree(json.stdout());
            assertFalse(envelope.path("ok").asBoolean());
            assertEquals("AUTHENTICATION_FAILED", envelope.path("error").path("code").asText());
            assertFalse(envelope.path("error").path("retryable").asBoolean());
            assertEquals("unauthorized", envelope.path("error").path("upstream_code").asText());
            assertEquals(401, envelope.path("meta").path("http_status").asInt());
            assertTrue(
                    envelope.path("meta").path("rate_limits").isEmpty(),
                    "no rate-limit windows were served, so meta carries the empty list");
            assertTokenMaterialAbsent(json, testKey);
        }
    }

    /**
     * A 429 ends the JSONL stream in one rate-limited error record that carries the
     * observed windows — the same per-window rendering as the envelope's
     * {@code meta.rate_limits} — so a line-oriented consumer can honor the reset without
     * switching channels. jsonl owns its failure explanation, so stderr stays empty.
     */
    protected static void rateLimitedJsonlErrorCarriesTheObservedWindows(
            List<String> command, String sandboxPrefix) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(sandboxPrefix);
                ScriptedSseServer server = ScriptedSseServer.builder()
                        .statusCode(429)
                        .header("Content-Type", "application/json")
                        .header("X-RateLimit-Limit", "1,15")
                        .header("X-RateLimit-Policy", "request,minute")
                        .header("X-RateLimit-Remaining", "0,14")
                        .header("X-RateLimit-Reset", "1,42")
                        .writeBytes(RATE_LIMITED_ERROR)
                        .start()) {
            ProcessHarness.ProcessResult jsonl = run(
                    command, server, childEnvironment(sandbox.directory, testKey), "--output", "jsonl", "three word query");

            assertEquals(5, jsonl.exitStatus(), () -> describe(jsonl, testKey));
            String stdout = new String(jsonl.stdout(), UTF_8);
            assertTrue(
                    stdout.indexOf('\n') == stdout.length() - 1,
                    () -> "the error record must be exactly one LF-terminated line: " + describe(jsonl, testKey));
            assertTrue(
                    new SchemaCatalog().validateText("jsonl-record.schema.json", stdout).isEmpty(),
                    () -> "the error record must satisfy jsonl-record.schema.json: " + describe(jsonl, testKey));
            JsonNode error = READER.readTree(jsonl.stdout());
            assertEquals("error", error.path("type").asText());
            assertEquals("RATE_LIMITED", error.path("code").asText());
            assertTrue(error.path("retryable").asBoolean(), () -> describe(jsonl, testKey));
            JsonNode windows = error.path("rate_limits");
            assertEquals(2, windows.size(), "the observed windows ride the error record: " + describe(jsonl, testKey));
            assertEquals("request", windows.get(0).path("policy").asText());
            assertEquals(0, windows.get(0).path("remaining").asInt());
            assertEquals(1000, windows.get(0).path("reset_ms").asInt());
            assertEquals("minute", windows.get(1).path("policy").asText());
            assertEquals(42000, windows.get(1).path("reset_ms").asInt());
            assertEquals("", new String(jsonl.stderr(), UTF_8), () -> describe(jsonl, testKey));
            assertTokenMaterialAbsent(jsonl, testKey);
        }
    }

    /**
     * One usage-invalid invocation is rejected with the usage exit before the server can
     * observe any request; {@code expectedStderrFragment} names the rejection shape the
     * endpoint's grammar produces.
     */
    protected static void usageFailureHappensBeforeAnyNetworkDispatch(
            List<String> command,
            ScriptedSseServer.Builder fixture,
            String expectedStderrFragment,
            String sandboxPrefix,
            String... arguments)
            throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(sandboxPrefix);
                ScriptedSseServer server = fixture.start()) {
            ProcessHarness.ProcessResult result = run(command, server, childEnvironment(sandbox.directory, testKey), arguments);

            assertEquals(2, result.exitStatus(), () -> describe(result, testKey));
            String stderr = new String(result.stderr(), UTF_8);
            assertTrue(stderr.contains(expectedStderrFragment), () -> "usage must appear on stderr: " + describe(result, testKey));
            assertEquals("", new String(result.stdout(), UTF_8), () -> describe(result, testKey));
            assertEquals(0, server.requests().size(), "an invalid request must never be dispatched");
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /** A loopback run authenticates with the test key and never forwards a stored credential. */
    protected static void loopbackRunsSendTheTestKeyAndNeverStoredCredentials(
            List<String> command, ScriptedSseServer.Builder fixture, String humanHeading, String sandboxPrefix)
            throws Exception {
        String testKey = freshToken();
        String storedDecoy = freshToken();
        try (Sandbox sandbox = Sandbox.create(sandboxPrefix);
                ScriptedSseServer server = fixture.start()) {
            Map<String, String> environment = childEnvironment(sandbox.directory, testKey);
            environment.put("BRAVE_API_KEY", storedDecoy);
            ProcessHarness.ProcessResult result = run(command, server, environment, "three word query");

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey, storedDecoy));
            assertTrue(
                    new String(result.stdout(), UTF_8).startsWith(humanHeading),
                    () -> "the exchange must have succeeded: " + describe(result, testKey, storedDecoy));

            List<ScriptedSseServer.RecordedRequest> requests = server.requests();
            assertEquals(1, requests.size(), "exactly one request may leave the process");
            assertEquals(
                    Optional.of(testKey),
                    requests.getFirst().firstHeader("X-Subscription-Token"),
                    "the token on the wire must be the loopback test key");
            for (ScriptedSseServer.RecordedRequest request : requests) {
                assertFalse(request.path().contains(storedDecoy), "the stored credential must never ride the request line");
                for (List<String> values : request.headers().values()) {
                    for (String value : values) {
                        assertFalse(value.contains(storedDecoy), "the stored credential must never reach a loopback peer");
                    }
                }
            }
            assertTokenMaterialAbsent(result, testKey, storedDecoy);
        }
    }

    /** Without the loopback test key the run keeps the local-configuration exit and dispatches nothing. */
    protected static void missingLoopbackTestKeyKeepsTheLocalConfigurationExit(
            List<String> command, ScriptedSseServer.Builder fixture, String sandboxPrefix) throws Exception {
        String storedDecoy = freshToken();
        try (Sandbox sandbox = Sandbox.create(sandboxPrefix);
                ScriptedSseServer server = fixture.start()) {
            Map<String, String> environment = childEnvironment(sandbox.directory, null);
            environment.put("BRAVE_API_KEY", storedDecoy);
            ProcessHarness.ProcessResult result = run(command, server, environment, "three word query");

            assertEquals(3, result.exitStatus(), () -> describe(result, storedDecoy));
            assertEquals("", new String(result.stdout(), UTF_8), () -> describe(result, storedDecoy));
            List<String> diagnostics = new String(result.stderr(), UTF_8).lines().toList();
            assertEquals(1, diagnostics.size(), () -> "exactly one diagnostic line: " + describe(result, storedDecoy));
            assertTrue(
                    diagnostics.getFirst().contains("BRAVE_SEARCH_TEST_KEY"),
                    () -> "the diagnostic must name the missing variable: " + describe(result, storedDecoy));
            assertEquals(0, server.requests().size(), "an unauthenticated run must never dispatch");
            assertTokenMaterialAbsent(result, storedDecoy);
        }
    }

    /** The Api-Version pin travels verbatim on the wire; an impossible calendar date never leaves the process. */
    protected static void apiVersionPinReachesTheWireAndImpossibleDatesFailUsage(
            List<String> command, ScriptedSseServer.Builder fixture, String sandboxPrefix) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(sandboxPrefix);
                ScriptedSseServer server = fixture.start()) {
            ProcessHarness.ProcessResult pinned = run(
                    command,
                    server,
                    childEnvironment(sandbox.directory, testKey),
                    "--api-version",
                    RESPONSE_API_VERSION,
                    "three word query");
            assertEquals(0, pinned.exitStatus(), () -> describe(pinned, testKey));
            assertEquals(
                    Optional.of(RESPONSE_API_VERSION),
                    server.requests().getFirst().firstHeader("Api-Version"),
                    "the pinned version must travel on the wire exactly as spelled");
            assertTokenMaterialAbsent(pinned, testKey);

            int requestsBefore = server.requests().size();
            ProcessHarness.ProcessResult impossible = run(
                    command, server, childEnvironment(sandbox.directory, testKey), "--api-version", "2026-13-99", "three word query");
            assertEquals(2, impossible.exitStatus(), () -> describe(impossible, testKey));
            assertTrue(
                    new String(impossible.stderr(), UTF_8).contains("Usage:"),
                    () -> "an impossible date is a usage failure: " + describe(impossible, testKey));
            assertEquals(requestsBefore, server.requests().size(), "an impossible date must never be dispatched anywhere");
            assertTokenMaterialAbsent(impossible, testKey);
        }
    }

    /**
     * A 200 body that is not readable JSON renders the malformed failure document of the
     * mode and keeps exit 8: one stderr diagnostic in human mode, one schema-valid failure
     * envelope carrying the malformed code in json mode.
     */
    protected static void garbageSuccessBodyExitsEight(List<String> command, String commandName, String sandboxPrefix)
            throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(sandboxPrefix);
                ScriptedSseServer server = ScriptedSseServer.builder()
                        .statusCode(200)
                        .header("Content-Type", "application/json")
                        .writeBytes(GARBAGE_SUCCESS)
                        .start()) {
            ProcessHarness.ProcessResult human =
                    run(command, server, childEnvironment(sandbox.directory, testKey), "three word query");
            assertEquals(8, human.exitStatus(), () -> describe(human, testKey));
            assertEquals("", new String(human.stdout(), UTF_8), () -> describe(human, testKey));
            List<String> diagnostics = new String(human.stderr(), UTF_8).lines().toList();
            assertEquals(1, diagnostics.size(), () -> "exactly one diagnostic line: " + describe(human, testKey));
            assertTrue(
                    diagnostics.getFirst().startsWith(commandName + ": "),
                    () -> "the diagnostic names the command: " + describe(human, testKey));
            assertFalse(
                    new String(human.stderr(), UTF_8).contains(new String(GARBAGE_SUCCESS, UTF_8)),
                    "the diagnostic quotes no body text");
            assertTokenMaterialAbsent(human, testKey);

            ProcessHarness.ProcessResult json = run(
                    command, server, childEnvironment(sandbox.directory, testKey), "--output", "json", "three word query");
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

    /**
     * Upstream text carrying terminal controls — a cursor-wipe CSI, a forged OSC-8
     * hyperlink, a carriage return — never reaches the human document: every escape
     * sequence vanishes wholly and every lone control becomes a replacement character,
     * while the json envelope keeps the upstream tree lossless and raw stays the exact
     * served bytes.
     */
    protected static void upstreamTerminalControlsNeverReachHumanOutput(
            List<String> command, byte[] adversarialBody, String sandboxPrefix) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(sandboxPrefix);
                ScriptedSseServer server = ScriptedSseServer.builder()
                        .statusCode(200)
                        .header("Content-Type", "application/json")
                        .writeBytes(adversarialBody)
                        .start()) {
            ProcessHarness.ProcessResult human =
                    run(command, server, childEnvironment(sandbox.directory, testKey), "three word query");
            assertEquals(0, human.exitStatus(), () -> describe(human, testKey));
            String humanText = new String(human.stdout(), UTF_8);
            assertFalse(humanText.contains("\u001b"), "no escape byte may reach the human document");
            assertFalse(humanText.contains("\r"), "no carriage return may reach the human document");
            assertFalse(humanText.contains("evil.example"), "a forged hyperlink must vanish wholly");
            assertFalse(humanText.contains("[2J"), "a cursor-wipe sequence must vanish wholly");
            assertTrue(humanText.contains("\ufffd"), "a lone control leaves a replacement character");
            assertTokenMaterialAbsent(human, testKey);

            ProcessHarness.ProcessResult json = run(
                    command, server, childEnvironment(sandbox.directory, testKey), "--output", "json", "three word query");
            assertEquals(0, json.exitStatus(), () -> describe(json, testKey));
            String jsonText = new String(json.stdout(), UTF_8);
            assertTrue(
                    jsonText.contains("evil.example"),
                    "the machine envelope keeps the forged hyperlink text losslessly: " + describe(json, testKey));
            assertTrue(
                    jsonText.contains("\\r"),
                    "the machine envelope keeps the carriage return escaped: " + describe(json, testKey));

            ProcessHarness.ProcessResult raw = run(
                    command, server, childEnvironment(sandbox.directory, testKey), "--output", "raw", "three word query");
            assertEquals(0, raw.exitStatus(), () -> describe(raw, testKey));
            assertArrayEquals(adversarialBody, raw.stdout(), "raw stays the byte-exact served body");
        }
    }

    /**
     * A downstream consumer closing the pipe early is silent success: the child exits exactly
     * 0, writes no diagnostic, and produces no error banner. The served body exceeds any pipe
     * buffer, so the child is still writing when the consumer closes after a few bytes — the
     * observed status is the child's own, not the consumer's.
     */
    protected static void downstreamPipeCloseIsSilentZero(List<String> command, byte[] oversizedBody, String sandboxPrefix)
            throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(sandboxPrefix);
                ScriptedSseServer server = ScriptedSseServer.builder()
                        .statusCode(200)
                        .header("Content-Type", "application/json")
                        .writeBytes(oversizedBody)
                        .start()) {
            List<String> full = new ArrayList<>(command);
            full.add("--base-url");
            full.add(server.baseUrl().toString());
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
     * The page-one records of a paged JSONL walk stand, and a later page's server failure ends
     * the stream in one counted error record: page one streams its two result records first,
     * then the error record reports the failed walk's page counts, and the process keeps the
     * upstream failure status. jsonl owns its failure explanation, so stderr stays empty.
     */
    protected static void pagedJsonlKeepsPageOneRecordsBeforeTheLaterPageError(
            List<String> command,
            byte[] pageOne,
            String firstTitle,
            String secondTitle,
            String sandboxPrefix)
            throws Exception {
        String testKey = freshToken();
        SchemaCatalog schemas = new SchemaCatalog();
        try (Sandbox sandbox = Sandbox.create(sandboxPrefix);
                ScriptedSseServer server = ScriptedSseServer.startSequence(
                        ScriptedSseServer.builder()
                                .statusCode(200)
                                .header("Content-Type", "application/json")
                                .writeBytes(pageOne),
                        ScriptedSseServer.builder()
                                .statusCode(500)
                                .header("Content-Type", "application/json")
                                .writeBytes(SERVER_ERROR))) {
            ProcessHarness.ProcessResult result = run(
                    command,
                    server,
                    childEnvironment(sandbox.directory, testKey),
                    "--all-pages",
                    "--output",
                    "jsonl",
                    "three word query");

            assertEquals(7, result.exitStatus(), () -> "the failed page's kind owns the status: " + describe(result, testKey));
            String stdout = new String(result.stdout(), UTF_8);
            String[] lines = stdout.split("\n", -1);
            assertEquals(
                    4,
                    lines.length,
                    () -> "two result records, one error record, one terminator: " + describe(result, testKey));
            for (int index = 0; index < 3; index++) {
                String line = lines[index];
                String ordinal = "record " + index;
                assertTrue(
                        schemas.validateText("jsonl-record.schema.json", line).isEmpty(),
                        () -> ordinal + " must satisfy jsonl-record.schema.json: " + line);
            }
            JsonNode first = READER.readTree(lines[0]);
            JsonNode second = READER.readTree(lines[1]);
            assertEquals("result", first.path("type").asText());
            assertEquals("result", second.path("type").asText());
            assertEquals(1, first.path("page").asInt(), "the streamed records carry their page provenance");
            assertEquals(1, second.path("page").asInt());
            assertEquals(firstTitle, first.path("title").asText());
            assertEquals(secondTitle, second.path("title").asText());
            JsonNode error = READER.readTree(lines[2]);
            assertEquals("error", error.path("type").asText());
            assertEquals("UPSTREAM_ERROR", error.path("code").asText());
            assertEquals(10, error.path("requested_pages").asInt(), "the error record carries the page budget");
            assertEquals(1, error.path("received_pages").asInt(), "the error record carries the completed pages");
            assertEquals("", new String(result.stderr(), UTF_8), () -> describe(result, testKey));
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /**
     * A downstream consumer closing the pipe during a paged JSONL walk — the head-of-stream
     * reader that leaves after the first page — is silent success: the child exits exactly 0,
     * writes no diagnostic, and produces no stack trace. The walk's second page is held back
     * until the consumer closed, so the broken pipe provably strikes the mid-walk record
     * writer, not the terminal summary.
     */
    protected static void pagedJsonlBrokenPipeMidWalkIsSilentZero(
            List<String> command, byte[] pageOne, byte[] pageTwo, String sandboxPrefix) throws Exception {
        String testKey = freshToken();
        java.util.concurrent.CountDownLatch releaseLastPage = new java.util.concurrent.CountDownLatch(1);
        try (Sandbox sandbox = Sandbox.create(sandboxPrefix);
                ScriptedSseServer server = ScriptedSseServer.startSequence(
                        ScriptedSseServer.builder()
                                .statusCode(200)
                                .header("Content-Type", "application/json")
                                .writeBytes(pageOne),
                        ScriptedSseServer.builder()
                                .statusCode(200)
                                .header("Content-Type", "application/json")
                                .stallUntil(releaseLastPage)
                                .writeBytes(pageTwo))) {
            List<String> full = new ArrayList<>(command);
            full.add("--base-url");
            full.add(server.baseUrl().toString());
            full.addAll(List.of("--all-pages", "--output", "jsonl", "three word query"));
            try (ProcessHarness.Session session =
                    new ProcessHarness().start(full, childEnvironment(sandbox.directory, testKey), SCRUBBED)) {
                byte[] seen = readUntilContains(session.stdout(), "\"page\":1".getBytes(UTF_8), PROBE_DEADLINE);
                assertTrue(seen.length > 0, "the walk streams its page-one records before the consumer leaves");

                session.stdout().close();
                releaseLastPage.countDown();

                assertTrue(
                        session.awaitExit(java.time.Duration.ofSeconds(60)),
                        "the child must terminate once the consumer closed the pipe mid-walk");
                assertEquals(
                        0,
                        session.exitValue(),
                        () -> "a mid-walk closed pipe is successful early termination, stderr=<"
                                + new String(session.stderr(), UTF_8) + ">");
                assertEquals("", new String(session.stderr(), UTF_8), "early termination stays silent");
                assertFalse(
                        new String(session.stderr(), UTF_8).contains("Exception"),
                        "no stack trace token may reach stderr");
            }
        } finally {
            releaseLastPage.countDown();
        }
    }

    /**
     * A SIGINT that lands while a single-request exchange sits parked on a stalled response
     * body ends the run by the conventional interrupt status 130: the human mode renders
     * exactly one stderr diagnostic naming the cancelled exchange and emits no partial
     * document, and the json mode renders exactly one failure envelope carrying the
     * transport error code — the same document a transport failure always renders, with
     * the signal owning the process status.
     */
    protected static void sigintDuringBlockedBodyExits130(
            List<String> command, String commandName, byte[] partialBody, String sandboxPrefix) throws Exception {
        blockedBodySignalExit(command, commandName, partialBody, sandboxPrefix, "INT", 130);
    }

    /**
     * A SIGTERM that lands while a single-request exchange sits parked on a stalled response
     * body ends the run by the conventional termination status 143 with the same failure
     * document the interrupt renders.
     */
    protected static void sigtermDuringBlockedBodyExits143(
            List<String> command, String commandName, byte[] partialBody, String sandboxPrefix) throws Exception {
        java.util.concurrent.CountDownLatch neverRelease = new java.util.concurrent.CountDownLatch(1);
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(sandboxPrefix);
                ScriptedSseServer server = blockedBodyServer(partialBody, neverRelease)) {
            List<String> human = withBaseUrl(command, server);
            human.add("three word query");
            try (ProcessHarness.Session session =
                    new ProcessHarness().start(human, childEnvironment(sandbox.directory, testKey), SCRUBBED)) {
                assertTrue(server.awaitFirstRequest(PROBE_DEADLINE), "the exchange must reach the scripted server");
                Thread.sleep(750);

                session.signal("TERM");

                assertTrue(session.awaitExit(PROBE_DEADLINE), "a signalled exchange must exit promptly");
                assertEquals(
                        143,
                        session.exitValue(),
                        () -> "the latched termination owns the status, stderr=<"
                                + new String(session.stderr(), UTF_8) + ">");
                List<String> diagnostics = new String(session.stderr(), UTF_8).lines().toList();
                assertEquals(1, diagnostics.size(), () -> "exactly one diagnostic line: " + diagnostics);
                assertTrue(
                        diagnostics.getFirst().startsWith(commandName + ": "),
                        () -> "the diagnostic names the command: " + diagnostics);
            }
        } finally {
            neverRelease.countDown();
        }
    }

    private static void blockedBodySignalExit(
            List<String> command,
            String commandName,
            byte[] partialBody,
            String sandboxPrefix,
            String signal,
            int expectedExit)
            throws Exception {
        java.util.concurrent.CountDownLatch neverRelease = new java.util.concurrent.CountDownLatch(1);
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(sandboxPrefix);
                ScriptedSseServer server = blockedBodyServer(partialBody, neverRelease)) {
            List<String> human = withBaseUrl(command, server);
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
                        diagnostics.getFirst().startsWith(commandName + ": "),
                        () -> "the diagnostic names the command: " + diagnostics);
            }

            List<String> json = withBaseUrl(command, server);
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

    /**
     * A signal that lands after the run completed its exchange and printed its document
     * changes nothing: the process keeps the success status 0 of the completed run.
     */
    protected static void signalAfterCompletionKeepsZero(
            List<String> command, ScriptedSseServer.Builder fixture, String sandboxPrefix, String expectedHumanEnd)
            throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(sandboxPrefix);
                ScriptedSseServer server = fixture.start()) {
            List<String> human = withBaseUrl(command, server);
            human.add("three word query");
            try (ProcessHarness.Session session =
                    new ProcessHarness().start(human, childEnvironment(sandbox.directory, testKey), SCRUBBED)) {
                byte[] seen =
                        readUntilContains(session.stdout(), expectedHumanEnd.getBytes(UTF_8), PROBE_DEADLINE);

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

    private static ScriptedSseServer blockedBodyServer(byte[] partialBody, java.util.concurrent.CountDownLatch forever)
            throws IOException {
        return ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .writeBytes(partialBody)
                .flush()
                .stallUntil(forever)
                .start();
    }

    /** The command with the loopback override spelled once. */
    private static List<String> withBaseUrl(List<String> command, ScriptedSseServer server) {
        List<String> full = new ArrayList<>(command);
        full.add("--base-url");
        full.add(server.baseUrl().toString());
        return full;
    }

    /**
     * A SIGINT that lands while a paged walk is paced behind an exhausted rate-limit window
     * — in the pacing wait after page one or, for a child slow to consume the served page,
     * inside the first exchange — renders the walk's transport-failure document, jsonl's
     * counted error record or human's one stderr diagnostic, while the process itself exits
     * by the conventional interrupt status 130.
     */
    protected static void sigintDuringPacedWalkExits130(
            List<String> command, byte[] pacedWalkPage, String commandName, String sandboxPrefix) throws Exception {
        terminationDuringPacedWalkExitsBySignal(command, pacedWalkPage, commandName, sandboxPrefix, "INT", 130);
    }

    /**
     * A SIGTERM that lands in the same paced-walk window renders the same transport-failure
     * document while the process itself exits by the conventional termination status 143.
     */
    protected static void sigtermDuringPacedWalkExits143(
            List<String> command, byte[] pacedWalkPage, String commandName, String sandboxPrefix) throws Exception {
        terminationDuringPacedWalkExitsBySignal(command, pacedWalkPage, commandName, sandboxPrefix, "TERM", 143);
    }

    private static void terminationDuringPacedWalkExitsBySignal(
            List<String> command,
            byte[] pacedWalkPage,
            String commandName,
            String sandboxPrefix,
            String signal,
            int expectedExit)
            throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(sandboxPrefix);
                ScriptedSseServer server = ScriptedSseServer.builder()
                        .statusCode(200)
                        .header("Content-Type", "application/json")
                        .header("X-RateLimit-Limit", "1")
                        .header("X-RateLimit-Policy", "request")
                        .header("X-RateLimit-Remaining", "0")
                        .header("X-RateLimit-Reset", "3600")
                        .writeBytes(pacedWalkPage)
                        .start()) {
            List<String> prefix = new ArrayList<>(command);
            prefix.add("--base-url");
            prefix.add(server.baseUrl().toString());

            List<String> jsonlCommand = new ArrayList<>(prefix);
            jsonlCommand.addAll(List.of("--all-pages", "--output", "jsonl", "three word query"));
            try (ProcessHarness.Session session =
                    new ProcessHarness().start(jsonlCommand, childEnvironment(sandbox.directory, testKey), SCRUBBED)) {
                byte[] seen = readUntilContains(session.stdout(), "\"page\":1".getBytes(UTF_8), PROBE_DEADLINE);

                session.signal(signal);

                assertTrue(session.awaitExit(PROBE_DEADLINE), "a signalled walk must exit promptly");
                assertEquals(
                        expectedExit,
                        session.exitValue(),
                        () -> "the latched signal owns the status, stderr=<" + new String(session.stderr(), UTF_8) + ">");
                assertEquals("", new String(session.stderr(), UTF_8), "jsonl owns its failure explanation on stdout");
                String[] lines = new String(concat(seen, drainAfterExit(session)), UTF_8).split("\n", -1);
                JsonNode error = READER.readTree(lines[lines.length - 2]);
                assertEquals("error", error.path("type").asText(), "the walk ends in its transport-failure record");
                assertEquals("TRANSPORT_ERROR", error.path("code").asText());
                assertEquals(10, error.path("requested_pages").asInt());
                assertEquals(1, error.path("received_pages").asInt(), "the completed page stays countable");
            }

            List<String> humanCommand = new ArrayList<>(prefix);
            humanCommand.addAll(List.of("--all-pages", "three word query"));
            int humanRequestCount = server.requests().size() + 1;
            try (ProcessHarness.Session session =
                    new ProcessHarness().start(humanCommand, childEnvironment(sandbox.directory, testKey), SCRUBBED)) {
                assertTrue(server.awaitRequestCount(humanRequestCount, PROBE_DEADLINE), "the current walk must reach the scripted server");
                // the current child has a live exchange: the signal can land while it
                // consumes page one or while it waits for the rate-limit reset

                session.signal(signal);

                assertTrue(session.awaitExit(PROBE_DEADLINE), "a signalled walk must exit promptly");
                assertEquals(
                        expectedExit,
                        session.exitValue(),
                        () -> "the latched signal owns the status, stderr=<" + new String(session.stderr(), UTF_8) + ">");
                assertEquals("", new String(drainAfterExit(session), UTF_8), "a buffered mode emits no partial payload");
                List<String> diagnostics = new String(session.stderr(), UTF_8).lines().toList();
                assertEquals(1, diagnostics.size(), () -> "exactly one diagnostic line: " + diagnostics);
                assertTrue(
                        diagnostics.getFirst().matches(
                                commandName + ": (pagination was (interrupted while waiting for the rate-limit reset|cancelled)"
                                        + " before page 2 \\(1 of 10 requested pages completed\\)"
                                        + "|upstream exchange cancelled \\(0 of 10 requested pages completed\\))"),
                        () -> "the diagnostic names the stopped walk with its counts: " + diagnostics);
            }
        }
    }

    /**
     * A walk whose endpoint offers no upstream continuation signal walks exactly the
     * documented page budget: exactly ten requests — never an eleventh — with the
     * zero-based offsets of pages one through ten on the wire, and the summary reports the
     * full budget.
     */
    protected static void allPagesWalksExactlyTheTenDocumentedPages(
            List<String> command, byte[] pageOne, String sandboxPrefix) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(sandboxPrefix);
                ScriptedSseServer server = ScriptedSseServer.startSequence(
                        ScriptedSseServer.builder()
                                .statusCode(200)
                                .header("Content-Type", "application/json")
                                .writeBytes(pageOne))) {
            ProcessHarness.ProcessResult result = run(
                    command,
                    server,
                    childEnvironment(sandbox.directory, testKey),
                    "--all-pages",
                    "--output",
                    "jsonl",
                    "three word query");

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            List<ScriptedSseServer.RecordedRequest> requests = server.requests();
            assertEquals(
                    10,
                    requests.size(),
                    () -> "the walk stops at the documented maximum page and never requests another: "
                            + describe(result, testKey));
            for (int page = 1; page <= 10; page++) {
                String path = requests.get(page - 1).path();
                assertTrue(
                        path.contains("offset=" + (page - 1)),
                        "page " + page + " must travel as its zero-based offset: " + path);
            }
            JsonNode summary = READER.readTree(lastNonEmptyLine(result));
            assertEquals(10, summary.path("requested_pages").asInt());
            assertEquals(10, summary.path("received_pages").asInt());
            assertEquals(2, summary.path("result_count").asInt(), "one page's two results survive the repeated pages");
            assertEquals(18, summary.path("duplicates_removed").asInt(), "the nine repeated pages deduplicate away");
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /** {@code --max-pages} bounds the walk: exactly the budget's requests, no more. */
    protected static void maxPagesBoundsTheWalkToItsBudget(List<String> command, byte[] pageOne, String sandboxPrefix)
            throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(sandboxPrefix);
                ScriptedSseServer server = ScriptedSseServer.startSequence(
                        ScriptedSseServer.builder()
                                .statusCode(200)
                                .header("Content-Type", "application/json")
                                .writeBytes(pageOne))) {
            ProcessHarness.ProcessResult result = run(
                    command,
                    server,
                    childEnvironment(sandbox.directory, testKey),
                    "--all-pages",
                    "--max-pages",
                    "3",
                    "--output",
                    "jsonl",
                    "three word query");

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            assertEquals(3, server.requests().size(), "the budget stops the walk, never a body signal");
            JsonNode summary = READER.readTree(lastNonEmptyLine(result));
            assertEquals(3, summary.path("requested_pages").asInt());
            assertEquals(3, summary.path("received_pages").asInt());
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /**
     * The walk deduplicates overlapping results across pages by the exact URL string: the
     * shared result of the second page is one duplicate removed, first-seen order survives,
     * and the summary counts the retained results.
     */
    protected static void pagedWalkDeduplicatesOverlappingResultsAcrossPages(
            List<String> command, byte[] pageOne, byte[] pageLater, String sandboxPrefix) throws Exception {
        String testKey = freshToken();
        SchemaCatalog schemas = new SchemaCatalog();
        try (Sandbox sandbox = Sandbox.create(sandboxPrefix);
                ScriptedSseServer server = ScriptedSseServer.startSequence(
                        ScriptedSseServer.builder()
                                .statusCode(200)
                                .header("Content-Type", "application/json")
                                .writeBytes(pageOne),
                        ScriptedSseServer.builder()
                                .statusCode(200)
                                .header("Content-Type", "application/json")
                                .writeBytes(pageLater))) {
            ProcessHarness.ProcessResult result = run(
                    command,
                    server,
                    childEnvironment(sandbox.directory, testKey),
                    "--all-pages",
                    "--max-pages",
                    "2",
                    "--output",
                    "jsonl",
                    "three word query");

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            String[] lines = new String(result.stdout(), UTF_8).split("\n", -1);
            assertEquals(
                    5,
                    lines.length,
                    () -> "three result records, one summary record, one terminator: " + describe(result, testKey));
            JsonNode first = READER.readTree(lines[0]);
            JsonNode second = READER.readTree(lines[1]);
            JsonNode third = READER.readTree(lines[2]);
            assertEquals("https://example.com/first", first.path("url").asText());
            assertEquals("https://example.com/shared", second.path("url").asText());
            assertEquals(1, second.path("page").asInt(), "the shared result keeps its first-seen page provenance");
            assertEquals("https://example.com/later", third.path("url").asText());
            assertEquals(2, third.path("page").asInt());
            JsonNode summary = READER.readTree(lines[3]);
            assertEquals(3, summary.path("result_count").asInt());
            assertEquals(1, summary.path("duplicates_removed").asInt());
            for (int index = 0; index < 4; index++) {
                String line = lines[index];
                assertTrue(
                        schemas.validateText("jsonl-record.schema.json", line).isEmpty(),
                        "record " + index + " must satisfy jsonl-record.schema.json: " + line);
            }
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /** The last nonempty line of a child's LF-terminated stdout stream. */
    protected static String lastNonEmptyLine(ProcessHarness.ProcessResult result) {
        String[] lines = new String(result.stdout(), UTF_8).split("\n", -1);
        return lines[lines.length - 2];
    }

    protected static void assertWindow(JsonNode window, String policy, int limit, int remaining, int resetMillis) {
        assertEquals(policy, window.path("policy").asText());
        assertEquals(limit, window.path("limit").asInt());
        assertEquals(remaining, window.path("remaining").asInt());
        assertEquals(resetMillis, window.path("reset_ms").asInt());
    }

    /** Reads the child's stdout until it contains {@code expected}, returning everything seen. */
    protected static byte[] readUntilContains(java.io.InputStream stream, byte[] expected, java.time.Duration timeout)
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
    protected static byte[] drainAfterExit(ProcessHarness.Session session) throws IOException, InterruptedException {
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

    protected static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = new byte[first.length + second.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    /**
     * Launches the search command once against the scripted server: the hidden loopback
     * override, then the scenario's own arguments, all inside the given environment.
     */
    protected static ProcessHarness.ProcessResult run(
            List<String> searchCommand, ScriptedSseServer server, Map<String, String> environment, String... arguments)
            throws IOException {
        List<String> command = new ArrayList<>(searchCommand);
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
    protected static Map<String, String> childEnvironment(Path sandbox, String testKey) {
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
    protected static String freshToken() {
        return "BSK" + Long.toUnsignedString(UUID.randomUUID().getMostSignificantBits(), 36)
                + Long.toUnsignedString(UUID.randomUUID().getLeastSignificantBits(), 36);
    }

    /** Byte-level absence over both streams is recursive absence for alphanumeric tokens. */
    protected static void assertTokenMaterialAbsent(ProcessHarness.ProcessResult result, String... tokens) {
        String stdout = new String(result.stdout(), UTF_8);
        String stderr = new String(result.stderr(), UTF_8);
        for (String token : tokens) {
            assertFalse(stdout.contains(token), "token material leaked to child stdout");
            assertFalse(stderr.contains(token), "token material leaked to child stderr");
        }
    }

    /** Command, exit status, and streams for reports, with every known token redacted first. */
    protected static String describe(ProcessHarness.ProcessResult result, String... secrets) {
        String stdout = new String(result.stdout(), UTF_8);
        String stderr = new String(result.stderr(), UTF_8);
        for (String secret : secrets) {
            stdout = stdout.replace(secret, "<redacted>");
            stderr = stderr.replace(secret, "<redacted>");
        }
        return "exitStatus=" + result.exitStatus() + ", stdout=<" + stdout + ">, stderr=<" + stderr + ">";
    }

    /** A private home directory for one scenario, removed with everything under it on close. */
    protected static final class Sandbox implements AutoCloseable {

        final Path directory;

        private Sandbox(Path directory) {
            this.directory = directory;
        }

        static Sandbox create(String prefix) throws IOException {
            return new Sandbox(Files.createTempDirectory(prefix));
        }

        @Override
        public void close() {
            try (Stream<Path> files = Files.walk(directory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            } catch (IOException unreadable) {
                // a leftover temporary home cannot affect any other scenario's isolation
            }
        }
    }
}

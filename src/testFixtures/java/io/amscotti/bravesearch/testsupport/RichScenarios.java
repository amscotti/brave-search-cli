package io.amscotti.bravesearch.testsupport;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The rich callback vertical-slice scenarios, run against a whole CLI process and a
 * scripted loopback server. Each scenario takes the command prefix that launches the
 * rich command — the installed JVM launcher plus the subcommand token, or the native
 * binary plus the same token — so both executables must show identical evidence: the
 * per-vertical human sections with the provider attribution lines, one schema-valid
 * JSON success envelope whose generic projection names verticals and counts while its
 * {@code data.upstream} keeps the undocumented body lossless, one schema-valid JSONL
 * record per vertical block plus the summary, raw stdout byte-identical to the served
 * body, the authentication failure's exit status, usage rejections that predate any
 * network dispatch — the blank and missing callback key, the extra positional, and the
 * search spellings the endpoint does not document — the loopback test-key routing that
 * dispatches exactly one GET whose single parameter is the {@code callback_key} wire
 * name and never the search verticals' {@code q}, the Api-Version pin, and the silent
 * early termination on a closed downstream pipe.
 *
 * <p>The harness plumbing lives once in {@link SearchScenarios}; this class carries the
 * rich fixture bodies and the rich-specific evidence: the callback-key request line,
 * the vertical-block projection, and the attribution that third-party rich data carries.
 */
public final class RichScenarios extends SearchScenarios {

    /** The callback key every standard scenario looks up. */
    private static final String CALLBACK_KEY = "cb-7f3a2b";

    /** The served multi-vertical success body, including an unknown vertical and exact-scale decimals. */
    private static final byte[] RICH_RESULTS = """
            {
              "type": "rich",
              "query": {
                "original": "three word query"
              },
              "videos": [
                {
                  "title": "First Provider Video",
                  "url": "https://videos.example.com/first",
                  "description": "First provider video description.",
                  "source": "Example Video Provider",
                  "unknown_item_field": {
                    "score": 0.5
                  }
                },
                {
                  "title": "Second Provider Video",
                  "url": "https://videos.example.com/second",
                  "source": "Example Video Provider"
                }
              ],
              "images": [
                {
                  "title": "Provider Image",
                  "url": "https://images.example.com/one",
                  "source": "Example Image Provider"
                },
                {
                  "title": 42
                }
              ],
              "faqs": [
                {
                  "title": "What is a rich callback?",
                  "url": "https://example.com/faq",
                  "description": "A reference to real-time rich results of an earlier web search.",
                  "source": "Example FAQ Provider"
                }
              ],
              "future_widgets": [
                {
                  "widget": "one"
                },
                {
                  "widget": "two"
                }
              ],
              "unknown_future_block": {
                "cost": 1.10,
                "long": 0.1000000000000000000001
              }
            }
            """
            .getBytes(UTF_8);

    /** The exact human document the multi-vertical body renders, every line LF-terminated. */
    private static final String EXPECTED_HUMAN = """
            Rich results for: cb-7f3a2b

            videos (2):
             1  First Provider Video
                https://videos.example.com/first
                First provider video description.
                source: Example Video Provider

             2  Second Provider Video
                https://videos.example.com/second
                source: Example Video Provider

            images (2):
             1  Provider Image
                https://images.example.com/one
                source: Example Image Provider

             2  (no title)

            faqs (1):
             1  What is a rich callback?
                https://example.com/faq
                A reference to real-time rich results of an earlier web search.
                source: Example FAQ Provider

            future_widgets: 2 items (unrecognized vertical; the json envelope carries the lossless body)
            4 verticals, 7 items.
            quota: 0 of 1 remaining (window resets in 1s)
            """;

    /**
     * The tolerant-edge served body: non-object elements inside a known vertical and an
     * empty known vertical, beside one ordinary item per remaining known vertical.
     */
    private static final byte[] TOLERANT_EDGE_RESULTS = """
            {
              "type": "rich",
              "videos": [
                {
                  "title": "Only Usable Video",
                  "url": "https://videos.example.com/only",
                  "source": "Example Video Provider"
                },
                "not an object",
                42
              ],
              "images": [],
              "faqs": [
                {
                  "title": "What is a rich callback?",
                  "url": "https://example.com/faq",
                  "description": "A reference to real-time rich results of an earlier web search.",
                  "source": "Example FAQ Provider"
                }
              ]
            }
            """
            .getBytes(UTF_8);

    private static final String SANDBOX_PREFIX = "brave-rich-process-";

    private RichScenarios() {}

    /** A 200 fixture exchange renders the per-vertical human sections, LF-only, without any ANSI byte. */
    public static void humanHappyPathRendersPerVerticalSectionsWithoutAnsi(List<String> richCommand) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = fixtureServer().start()) {
            ProcessHarness.ProcessResult result =
                    run(richCommand, server, childEnvironment(sandbox.directory, testKey), CALLBACK_KEY);

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
     * JSON mode emits exactly one LF-terminated success envelope that validates, stays
     * lossless, and keeps its projection generic: vertical names and item counts only,
     * never the internals of an undocumented response shape.
     */
    public static void jsonEnvelopeIsOneValidatedLfLine(List<String> richCommand) throws Exception {
        String testKey = freshToken();
        SchemaCatalog schemas = new SchemaCatalog();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = fixtureServer().start()) {
            ProcessHarness.ProcessResult result =
                    run(richCommand, server, childEnvironment(sandbox.directory, testKey), "--output", "json", CALLBACK_KEY);

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            String stdout = new String(result.stdout(), UTF_8);
            assertTrue(
                    stdout.indexOf('\n') == stdout.length() - 1,
                    () -> "the envelope must be exactly one LF-terminated line: " + describe(result, testKey));
            assertTrue(
                    schemas.validateText("envelope-success.schema.json", stdout).isEmpty(),
                    () -> "the envelope must satisfy envelope-success.schema.json: " + describe(result, testKey));

            JsonNode envelope = READER.readTree(result.stdout());
            assertEquals("rich", envelope.path("command").asText());
            JsonNode projection = envelope.path("data").path("projection");
            assertEquals(4, projection.path("vertical_count").asInt());
            assertEquals(7, projection.path("item_count").asInt());
            assertEquals("videos", projection.path("verticals").get(0).path("vertical").asText());
            assertEquals(2, projection.path("verticals").get(0).path("item_count").asInt());
            assertEquals("future_widgets", projection.path("verticals").get(3).path("vertical").asText());
            assertFalse(
                    projection.path("verticals").get(0).has("title"),
                    "the generic projection never models item internals");
            assertTrue(
                    stdout.contains("0.1000000000000000000001"),
                    "the spot decimal must be on stdout byte-for-byte");
            assertTrue(stdout.contains("\"cost\":1.10"), "the trailing-zero cost must be on stdout byte-for-byte");
            assertEquals(
                    "0.1000000000000000000001",
                    envelope.path("data").path("upstream").path("unknown_future_block").path("long").asText(),
                    "the spot decimal must survive the whole process at exact scale");
            assertEquals(REQUEST_ID, envelope.path("meta").path("request_id").asText());
            assertEquals(RESPONSE_API_VERSION, envelope.path("meta").path("api_version").asText());
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /**
     * The provider attribution that third-party rich data carries survives every
     * channel: the human document renders the source lines, and the JSON envelope keeps
     * the attribution members losslessly inside {@code data.upstream} — never inside the
     * generic projection.
     */
    public static void providerAttributionSurvivesHumanRenderingAndMachineLosslessness(List<String> richCommand)
            throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = fixtureServer().start()) {
            Map<String, String> environment = childEnvironment(sandbox.directory, testKey);
            ProcessHarness.ProcessResult human = run(richCommand, server, environment, CALLBACK_KEY);
            ProcessHarness.ProcessResult json =
                    run(richCommand, server, environment, "--output", "json", CALLBACK_KEY);

            assertEquals(0, human.exitStatus(), () -> describe(human, testKey));
            String humanText = new String(human.stdout(), UTF_8);
            assertTrue(
                    humanText.lines().anyMatch(line -> line.equals("    source: Example Video Provider")),
                    "the human document renders the provider attribution line: " + describe(human, testKey));
            assertTrue(
                    humanText.lines().anyMatch(line -> line.equals("    source: Example FAQ Provider")),
                    "every known vertical's attribution renders: " + describe(human, testKey));

            assertEquals(0, json.exitStatus(), () -> describe(json, testKey));
            JsonNode envelope = READER.readTree(json.stdout());
            assertEquals(
                    "Example Video Provider",
                    envelope.path("data").path("upstream").path("videos").get(0).path("source").asText(),
                    "machine output keeps the attribution member losslessly inside data.upstream");
            assertFalse(
                    new String(json.stdout(), UTF_8).contains("\"verticals\":[{\"vertical\":\"videos\",\"source\""),
                    "the generic projection carries no attribution internals");
            assertTokenMaterialAbsent(human, testKey);
            assertTokenMaterialAbsent(json, testKey);
        }
    }

    /** JSONL mode emits one validated record per vertical block and exactly one summary record after them. */
    public static void jsonlEmitsOneValidatedRecordPerVerticalThenSummary(List<String> richCommand) throws Exception {
        String testKey = freshToken();
        SchemaCatalog schemas = new SchemaCatalog();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = fixtureServer().start()) {
            ProcessHarness.ProcessResult result =
                    run(richCommand, server, childEnvironment(sandbox.directory, testKey), "--output", "jsonl", CALLBACK_KEY);

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            String stdout = new String(result.stdout(), UTF_8);
            assertFalse(stdout.contains("\r"), "JSONL lines must be LF-terminated only");
            String[] lines = stdout.split("\n", -1);
            assertEquals(
                    6,
                    lines.length,
                    () -> "four vertical records, one summary record, one terminator: " + describe(result, testKey));

            String[] expectedBuckets = {"videos", "images", "faqs", "future_widgets"};
            for (int position = 0; position < 4; position++) {
                String line = lines[position];
                assertTrue(
                        schemas.validateText("rich-result.schema.json", line).isEmpty(),
                        () -> "vertical record must satisfy rich-result.schema.json: " + line);
                assertTrue(
                        schemas.validateText("jsonl-record.schema.json", line).isEmpty(),
                        () -> "vertical record must satisfy jsonl-record.schema.json: " + line);
                JsonNode record = READER.readTree(line);
                assertEquals("result", record.path("type").asText());
                assertEquals("rich", record.path("command").asText());
                assertEquals(expectedBuckets[position], record.path("bucket").asText());
                assertEquals(position, record.path("position").asInt());
            }
            assertEquals(2, READER.readTree(lines[0]).path("item_count").asInt());
            assertEquals(2, READER.readTree(lines[3]).path("item_count").asInt());

            assertTrue(
                    schemas.validateText("rich-summary.schema.json", lines[4]).isEmpty(),
                    () -> "summary record must satisfy rich-summary.schema.json: " + lines[4]);
            assertTrue(
                    schemas.validateText("jsonl-record.schema.json", lines[4]).isEmpty(),
                    () -> "summary record must satisfy jsonl-record.schema.json: " + lines[4]);
            JsonNode summary = READER.readTree(lines[4]);
            assertEquals("summary", summary.path("type").asText());
            assertEquals("rich", summary.path("command").asText());
            assertEquals(4, summary.path("result_count").asInt());
            assertEquals(7, summary.path("item_count").asInt());
            assertEquals(200, summary.path("http_status").asInt());
            assertEquals(REQUEST_ID, summary.path("request_id").asText());
            assertEquals(RESPONSE_API_VERSION, summary.path("api_version").asText());
            assertEquals("", new String(result.stderr(), UTF_8), () -> describe(result, testKey));
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /** Raw mode writes the served body bytes to stdout byte-identically, adding and removing nothing. */
    public static void rawOutputIsTheServedBodyBytesExactly(List<String> richCommand) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = fixtureServer().start()) {
            ProcessHarness.ProcessResult result =
                    run(richCommand, server, childEnvironment(sandbox.directory, testKey), "--output", "raw", CALLBACK_KEY);

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            assertEquals(
                    RICH_RESULTS.length,
                    result.stdout().length,
                    () -> "raw stdout must hold exactly the served body bytes: " + describe(result, testKey));
            assertArrayEquals(RICH_RESULTS, result.stdout(), "raw stdout must be byte-identical to the served body");
            assertEquals("", new String(result.stderr(), UTF_8), () -> describe(result, testKey));
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /** A 401 keeps the authentication exit: one diagnostic line in human mode, one failure envelope in JSON mode. */
    public static void authenticationFailureExitsFourInHumanAndJsonModes(List<String> richCommand) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = ScriptedSseServer.builder()
                        .statusCode(401)
                        .header("Content-Type", "application/json")
                        .writeBytes(UNAUTHORIZED_ERROR)
                        .start()) {
            ProcessHarness.ProcessResult human = run(richCommand, server, childEnvironment(sandbox.directory, testKey), CALLBACK_KEY);
            assertEquals(4, human.exitStatus(), () -> describe(human, testKey));
            assertEquals("", new String(human.stdout(), UTF_8), () -> describe(human, testKey));
            List<String> diagnostics = new String(human.stderr(), UTF_8).lines().toList();
            assertEquals(1, diagnostics.size(), () -> "exactly one diagnostic line: " + describe(human, testKey));
            assertFalse(diagnostics.getFirst().contains("Exception"), "no raw stack trace may reach stderr");
            assertTrue(
                    diagnostics.getFirst().startsWith("rich: "),
                    () -> "the diagnostic names the command: " + describe(human, testKey));
            assertTokenMaterialAbsent(human, testKey);

            ProcessHarness.ProcessResult json =
                    run(richCommand, server, childEnvironment(sandbox.directory, testKey), "--output", "json", CALLBACK_KEY);
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
            assertEquals("unauthorized", envelope.path("error").path("upstream_code").asText());
            assertEquals(401, envelope.path("meta").path("http_status").asInt());
            assertTokenMaterialAbsent(json, testKey);
        }
    }

    /**
     * The blank callback key, the missing key, the extra positional, and the search
     * spellings the rich endpoint does not document are all rejected with the usage exit
     * before the server can observe any request.
     */
    public static void usageFailureHappensBeforeAnyNetworkDispatch(List<String> richCommand) throws Exception {
        usageFailureHappensBeforeAnyNetworkDispatch(
                richCommand, fixtureServer(), "callback key must not be blank", SANDBOX_PREFIX, "  ");
        usageFailureHappensBeforeAnyNetworkDispatch(richCommand, fixtureServer(), "Usage:", SANDBOX_PREFIX);
        usageFailureHappensBeforeAnyNetworkDispatch(
                richCommand, fixtureServer(), "Usage:", SANDBOX_PREFIX, "cb-1", "cb-2");
        usageFailureHappensBeforeAnyNetworkDispatch(
                richCommand, fixtureServer(), "Unknown option", SANDBOX_PREFIX, "--country", "DE", CALLBACK_KEY);
        usageFailureHappensBeforeAnyNetworkDispatch(
                richCommand,
                fixtureServer(),
                "Unknown option",
                SANDBOX_PREFIX,
                "--safe-search",
                "moderate",
                CALLBACK_KEY);
        usageFailureHappensBeforeAnyNetworkDispatch(
                richCommand, fixtureServer(), "Unknown option", SANDBOX_PREFIX, "--all-pages", CALLBACK_KEY);
        usageFailureHappensBeforeAnyNetworkDispatch(
                richCommand, fixtureServer(), "Unknown option", SANDBOX_PREFIX, "--goggle", "https://example.com/g", CALLBACK_KEY);
    }

    /**
     * A loopback run authenticates with the test key, never forwards a stored
     * credential, and dispatches exactly one GET whose single query parameter is the
     * endpoint's own {@code callback_key} wire name: the string {@code q=} never appears
     * on the request line, and the opaque key percent-encodes independently — including
     * a key handed over after {@code --} that starts like an option.
     */
    public static void loopbackRunSendsTheCallbackKeyOnTheCallbackKeyWireNameNeverQueryQ(List<String> richCommand)
            throws Exception {
        String testKey = freshToken();
        String storedDecoy = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = fixtureServer().start()) {
            Map<String, String> environment = childEnvironment(sandbox.directory, testKey);
            environment.put("BRAVE_API_KEY", storedDecoy);
            ProcessHarness.ProcessResult result = run(richCommand, server, environment, CALLBACK_KEY);

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey, storedDecoy));
            assertTrue(
                    new String(result.stdout(), UTF_8).startsWith("Rich results for: "),
                    () -> "the exchange must have succeeded: " + describe(result, testKey, storedDecoy));

            List<ScriptedSseServer.RecordedRequest> requests = server.requests();
            assertEquals(1, requests.size(), "a rich lookup is exactly one request");
            assertEquals(
                    "/web/rich?callback_key=cb-7f3a2b",
                    requests.getFirst().path(),
                    "the lookup rides the callback_key wire name, never the search q");
            assertFalse(requests.getFirst().path().contains("q="), "no search query parameter may ride a rich request");
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

            ProcessHarness.ProcessResult opaque =
                    run(richCommand, server, environment, "--", "-opaque/key?a=1&b=2");
            assertEquals(0, opaque.exitStatus(), () -> describe(opaque, testKey, storedDecoy));
            assertEquals(
                    "/web/rich?callback_key=-opaque%2Fkey%3Fa%3D1%26b%3D2",
                    server.requests().get(1).path(),
                    "an option-like opaque key travels verbatim after --, percent-encoded as one parameter");
            assertTokenMaterialAbsent(opaque, testKey, storedDecoy);
        }
    }

    /** Without the loopback test key the run keeps the local-configuration exit and dispatches nothing. */
    public static void missingLoopbackTestKeyKeepsTheLocalConfigurationExit(List<String> richCommand) throws Exception {
        String storedDecoy = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = fixtureServer().start()) {
            Map<String, String> environment = childEnvironment(sandbox.directory, null);
            environment.put("BRAVE_API_KEY", storedDecoy);
            ProcessHarness.ProcessResult result = run(richCommand, server, environment, CALLBACK_KEY);

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
    public static void apiVersionPinReachesTheWireAndImpossibleDatesFailUsage(List<String> richCommand)
            throws Exception {
        apiVersionPinReachesTheWireVerbatim(richCommand);
    }

    /** A downstream consumer closing the pipe early is silent success: exit exactly 0, no diagnostic. */
    public static void downstreamPipeCloseIsSilentZero(List<String> richCommand) throws Exception {
        String testKey = freshToken();
        byte[] oversized = oversizedRichBody();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = ScriptedSseServer.builder()
                        .statusCode(200)
                        .header("Content-Type", "application/json")
                        .writeBytes(oversized)
                        .start()) {
            List<String> full = new ArrayList<>(richCommand);
            full.add("--base-url");
            full.add(server.baseUrl().toString());
            full.addAll(List.of("--output", "raw", CALLBACK_KEY));
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

    /** The Api-Version pin of the rich command: the pinned spelling travels, the impossible date stays home. */
    private static void apiVersionPinReachesTheWireVerbatim(List<String> richCommand) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = fixtureServer().start()) {
            ProcessHarness.ProcessResult pinned = run(
                    richCommand,
                    server,
                    childEnvironment(sandbox.directory, testKey),
                    "--api-version",
                    RESPONSE_API_VERSION,
                    CALLBACK_KEY);
            assertEquals(0, pinned.exitStatus(), () -> describe(pinned, testKey));
            assertEquals(
                    Optional.of(RESPONSE_API_VERSION),
                    server.requests().getFirst().firstHeader("Api-Version"),
                    "the pinned version must travel on the wire exactly as spelled");
            assertTokenMaterialAbsent(pinned, testKey);

            int requestsBefore = server.requests().size();
            ProcessHarness.ProcessResult impossible = run(
                    richCommand,
                    server,
                    childEnvironment(sandbox.directory, testKey),
                    "--api-version",
                    "2026-13-99",
                    CALLBACK_KEY);
            assertEquals(2, impossible.exitStatus(), () -> describe(impossible, testKey));
            assertTrue(
                    new String(impossible.stderr(), UTF_8).contains("Usage:"),
                    () -> "an impossible date is a usage failure: " + describe(impossible, testKey));
            assertEquals(requestsBefore, server.requests().size(), "an impossible date must never be dispatched anywhere");
            assertTokenMaterialAbsent(impossible, testKey);
        }
    }

    /**
     * The tolerant-edge fixture every scenario family also exercises: a known vertical
     * carrying non-object elements — counted as items, rendered as nothing — beside an
     * empty known vertical, whose section header still renders with its zero count.
     */
    public static void nonObjectElementsAndEmptyKnownVerticalsStayCountedAndRenderTolerantly(
            List<String> richCommand) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = ScriptedSseServer.builder()
                        .statusCode(200)
                        .header("Content-Type", "application/json")
                        .writeBytes(TOLERANT_EDGE_RESULTS)
                        .start()) {
            ProcessHarness.ProcessResult human = run(richCommand, server, childEnvironment(sandbox.directory, testKey), CALLBACK_KEY);
            ProcessHarness.ProcessResult json = run(
                    richCommand, server, childEnvironment(sandbox.directory, testKey), "--output", "json", CALLBACK_KEY);

            assertEquals(0, human.exitStatus(), () -> describe(human, testKey));
            assertEquals(
                    """
                    Rich results for: cb-7f3a2b

                    videos (3):
                     1  Only Usable Video
                        https://videos.example.com/only
                        source: Example Video Provider

                    images (0):

                    faqs (1):
                     1  What is a rich callback?
                        https://example.com/faq
                        A reference to real-time rich results of an earlier web search.
                        source: Example FAQ Provider
                    3 verticals, 4 items.
                    """,
                    new String(human.stdout(), UTF_8),
                    () -> "non-object elements render nothing yet stay counted, and an empty known vertical keeps its header: "
                            + describe(human, testKey));

            assertEquals(0, json.exitStatus(), () -> describe(json, testKey));
            JsonNode projection = READER.readTree(json.stdout()).path("data").path("projection");
            assertEquals(3, projection.path("vertical_count").asInt());
            assertEquals(4, projection.path("item_count").asInt(), "non-object elements count as items of their vertical");
            assertEquals(3, projection.path("verticals").get(0).path("item_count").asInt());
            assertEquals(0, projection.path("verticals").get(1).path("item_count").asInt(), "the empty vertical stays a vertical");
            assertTokenMaterialAbsent(human, testKey);
            assertTokenMaterialAbsent(json, testKey);
        }
    }

    /** A rich body far beyond any pipe buffer, so the raw writer is still writing when a consumer leaves. */
    private static byte[] oversizedRichBody() {
        return ("{\"videos\":[{\"title\":\"" + "x".repeat(96 * 1024) + "\"}],\"type\":\"rich\"}")
                .getBytes(UTF_8);
    }

    /** The fixture exchange every success scenario serves: the multi-vertical body, quota windows, identifiers. */
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
                .writeBytes(RICH_RESULTS);
    }
}

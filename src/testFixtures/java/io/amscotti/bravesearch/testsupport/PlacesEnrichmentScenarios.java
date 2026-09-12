package io.amscotti.bravesearch.testsupport;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * The place enrichment vertical-slice scenarios, run against a whole CLI process and a
 * scripted loopback server. Each scenario takes the command prefix that launches one
 * enrichment command — the installed JVM launcher plus the group and subcommand tokens,
 * or the native binary plus the same tokens — so both executables must show identical
 * evidence: per-id human blocks in input order with {@code (not returned)} placeholders
 * and a counts line that includes the missing count, the auto-chunking wire form of
 * forty-five ids as three twenty-twenty-five requests whose repeated {@code ids}
 * parameters carry the exact chunk slices in input order, order reconstruction with
 * duplicates at every matching position, the invocation cap of two hundred ids, the
 * pagination-family partial-failure rules of a later chunk failure on every output
 * mode, rate pacing between chunks, the pre-dispatch raw rejection of the
 * multi-request family, the authentication failure's exit status, the loopback
 * test-key routing on every chunk under a decoy environment, the Api-Version pin, a
 * downstream pipe close mid-fan-out, and an interrupt that lands mid-fan-out while
 * the walk paces behind an exhausted window.
 */
public final class PlacesEnrichmentScenarios extends SearchScenarios {

    private static final String SANDBOX_PREFIX = "brave-places-enrichment-";

    private PlacesEnrichmentScenarios() {}

    /** A 200 fixture exchange renders the per-id human listing with placeholders, LF-only. */
    public static void humanHappyPathRendersInputOrderWithPlaceholders(List<String> detailsCommand) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = ScriptedSseServer.startSequence(detailsChunk("poi-a", "poi-c"))) {
            ProcessHarness.ProcessResult result = run(
                    detailsCommand,
                    server,
                    childEnvironment(sandbox.directory, testKey),
                    "poi-a",
                    "poi-b",
                    "poi-a",
                    "poi-c");

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            assertEquals(
                    "Place details for 4 ids\n"
                            + "\n"
                            + " 1  First POI\n"
                            + "    poi-a\n"
                            + "    https://example.com/first\n"
                            + "    First text.\n"
                            + "    1 Main St\n"
                            + "    +1 215 555 0100\n"
                            + "    https://example.com/thumb.jpg\n"
                            + "    $$\n"
                            + "    America/New_York\n"
                            + "\n"
                            + " 2  poi-b\n"
                            + "    (not returned)\n"
                            + "\n"
                            + " 3  First POI\n"
                            + "    poi-a\n"
                            + "    https://example.com/first\n"
                            + "    First text.\n"
                            + "    1 Main St\n"
                            + "    +1 215 555 0100\n"
                            + "    https://example.com/thumb.jpg\n"
                            + "    $$\n"
                            + "    America/New_York\n"
                            + "\n"
                            + " 4  First POI\n"
                            + "    poi-c\n"
                            + "    https://example.com/first\n"
                            + "    First text.\n"
                            + "    1 Main St\n"
                            + "    +1 215 555 0100\n"
                            + "    https://example.com/thumb.jpg\n"
                            + "    $$\n"
                            + "    America/New_York\n"
                            + "4 ids, 3 returned, 1 not returned.\n",
                    new String(result.stdout(), UTF_8),
                    () -> describe(result, testKey));
            assertEquals("", new String(result.stderr(), UTF_8), () -> describe(result, testKey));
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /**
     * Forty-five ids fan out as exactly three sequential requests — twenty, twenty,
     * five — whose repeated {@code ids} parameters carry the exact chunk slices in
     * input order.
     */
    public static void autoChunkingServesFortyFiveIdsAsThreeChunkRequests(List<String> command) throws Exception {
        String testKey = freshToken();
        List<String> ids = fortyFiveIds();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = ScriptedSseServer.startSequence(
                        detailsChunk(ids.subList(0, 20)),
                        detailsChunk(ids.subList(20, 40)),
                        detailsChunk(ids.subList(40, 45)))) {
            ProcessHarness.ProcessResult result =
                    runIds(command, server, childEnvironment(sandbox.directory, testKey), ids);

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            List<ScriptedSseServer.RecordedRequest> requests = server.requests();
            assertEquals(3, requests.size(), "forty-five ids compose exactly three chunk requests");
            assertEquals("/local/pois?" + queryOf(ids.subList(0, 20)), requests.get(0).path(), "chunk one is ids 1-20");
            assertEquals("/local/pois?" + queryOf(ids.subList(20, 40)), requests.get(1).path(), "chunk two is ids 21-40");
            assertEquals("/local/pois?" + queryOf(ids.subList(40, 45)), requests.get(2).path(), "chunk three is ids 41-45");
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /**
     * The descriptions command walks its own endpoint path with the same chunk rule:
     * forty-five ids, three requests on {@code /local/descriptions}.
     */
    public static void describeAutoChunkingWalksItsOwnEndpointPath(List<String> describeCommand) throws Exception {
        String testKey = freshToken();
        List<String> ids = fortyFiveIds();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = ScriptedSseServer.startSequence(
                        describeChunk(ids.subList(0, 20)),
                        describeChunk(ids.subList(20, 40)),
                        describeChunk(ids.subList(40, 45)))) {
            ProcessHarness.ProcessResult result =
                    run(describeCommand, server, childEnvironment(sandbox.directory, testKey), ids.toArray(String[]::new));

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            List<ScriptedSseServer.RecordedRequest> requests = server.requests();
            assertEquals(3, requests.size());
            for (ScriptedSseServer.RecordedRequest request : requests) {
                assertTrue(request.path().startsWith("/local/descriptions?ids="), "the describe path is its own: " + request.path());
            }
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /**
     * JSON mode emits one envelope whose {@code data.upstream} array is the ordered
     * per-request entry list of the three chunks and whose projection reconstructs all
     * forty-five input positions.
     */
    public static void jsonEnvelopeCarriesTheMultiRequestUpstreamArray(List<String> command) throws Exception {
        String testKey = freshToken();
        SchemaCatalog schemas = new SchemaCatalog();
        List<String> ids = fortyFiveIds();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = ScriptedSseServer.startSequence(
                        detailsChunk(ids.subList(0, 20)),
                        detailsChunk(ids.subList(20, 40)),
                        detailsChunk(ids.subList(40, 45)))) {
            ProcessHarness.ProcessResult result =
                    runIds(command, server, childEnvironment(sandbox.directory, testKey), ids, "--output", "json");

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            String stdout = new String(result.stdout(), UTF_8);
            assertTrue(
                    stdout.indexOf('\n') == stdout.length() - 1,
                    () -> "the envelope must be exactly one LF-terminated line: " + describe(result, testKey));
            assertTrue(
                    schemas.validateText("envelope-success.schema.json", stdout).isEmpty(),
                    () -> "the envelope must satisfy envelope-success.schema.json: " + describe(result, testKey));
            JsonNode envelope = READER.readTree(stdout);
            JsonNode upstream = envelope.path("data").path("upstream");
            assertTrue(upstream.isArray(), "a fan-out invocation encodes the upstream array form");
            assertEquals(3, upstream.size());
            assertEquals(0, upstream.get(0).path("request_index").asInt());
            assertEquals(1, upstream.get(1).path("request_index").asInt());
            assertEquals(2, upstream.get(2).path("request_index").asInt());
            JsonNode projection = envelope.path("data").path("projection");
            assertEquals(45, projection.path("id_count").asInt());
            assertEquals(45, projection.path("returned_count").asInt());
            assertEquals(0, projection.path("missing_count").asInt());
            assertEquals(3, projection.path("requested_requests").asInt());
            assertEquals(3, projection.path("received_requests").asInt());
            assertEquals(45, projection.path("results").size());
            assertEquals("poi-1", projection.path("results").get(0).path("id").asText());
            assertEquals("poi-45", projection.path("results").get(44).path("id").asText());
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /**
     * The JSONL records reconstruct the input order exactly: a duplicate input id
     * renders at both of its positions, and a missing or expired id renders its
     * placeholder at its original position, both provable against the published record
     * schemas.
     */
    public static void duplicatesAndMissingIdsReconstructAtTheirOriginalPositions(
            List<String> command, String commandName, String resultSchema, String summarySchema) throws Exception {
        String testKey = freshToken();
        SchemaCatalog schemas = new SchemaCatalog();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = ScriptedSseServer.startSequence(
                        detailsChunk("poi-a", "poi-c"), describeChunk("poi-a"))) {
            ProcessHarness.ProcessResult result = run(
                    command,
                    server,
                    childEnvironment(sandbox.directory, testKey),
                    "--output",
                    "jsonl",
                    "poi-a",
                    "poi-b",
                    "poi-a",
                    "poi-c");

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            String[] lines = new String(result.stdout(), UTF_8).split("\n", -1);
            assertEquals(
                    6,
                    lines.length,
                    "four per-id records, one summary record, one terminator: " + describe(result, testKey));
            for (int index = 0; index < 4; index++) {
                final String line = lines[index];
                final int record = index;
                assertTrue(
                        schemas.validateText(resultSchema, line).isEmpty(),
                        () -> "record " + record + " must satisfy " + resultSchema + ": " + line);
                assertTrue(
                        schemas.validateText("jsonl-record.schema.json", line).isEmpty(),
                        () -> "record " + record + " must satisfy jsonl-record.schema.json: " + line);
            }
            JsonNode first = READER.readTree(lines[0]);
            assertEquals(commandName, first.path("command").asText());
            assertEquals(0, first.path("position").asInt());
            assertEquals("poi-a", first.path("id").asText());
            assertTrue(first.path("present").asBoolean(), "the returned id renders present at its first position");
            JsonNode missing = READER.readTree(lines[1]);
            assertEquals(1, missing.path("position").asInt());
            assertEquals("poi-b", missing.path("id").asText());
            assertFalse(missing.path("present").asBoolean(), "the missing id keeps its original position as a placeholder");
            JsonNode duplicate = READER.readTree(lines[2]);
            assertEquals(2, duplicate.path("position").asInt());
            assertEquals("poi-a", duplicate.path("id").asText());
            assertTrue(duplicate.path("present").asBoolean(), "the duplicate input id fills every matching position");
            JsonNode last = READER.readTree(lines[3]);
            assertEquals(3, last.path("position").asInt());
            assertTrue(last.path("present").asBoolean());
            JsonNode summary = READER.readTree(lines[4]);
            assertTrue(
                    schemas.validateText(summarySchema, lines[4]).isEmpty(),
                    () -> "the summary must satisfy " + summarySchema + ": " + lines[4]);
            assertEquals(4, summary.path("id_count").asInt());
            assertEquals(3, summary.path("returned_count").asInt());
            assertEquals(1, summary.path("missing_count").asInt());
            assertEquals(1, summary.path("requested_requests").asInt());
            assertEquals(1, summary.path("received_requests").asInt());
            assertEquals(200, summary.path("http_status").asInt());
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /** The two-hundredth id is served as ten chunks; the two-hundred-first is rejected before any dispatch. */
    public static void invocationCapAcceptsTwoHundredAndRejectsTwoHundredAndOne(List<String> command)
            throws Exception {
        String testKey = freshToken();
        List<String> ids = IntStream.rangeClosed(1, 200).mapToObj(number -> "poi-" + number).toList();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = ScriptedSseServer.startSequence(detailsChunk(ids.subList(0, 20)))) {
            ProcessHarness.ProcessResult result =
                    runIds(command, server, childEnvironment(sandbox.directory, testKey), ids);

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            assertEquals(10, server.requests().size(), "the cap composes exactly ten chunk requests");
            assertTokenMaterialAbsent(result, testKey);

            List<String> tooMany = new ArrayList<>(ids);
            tooMany.add("poi-201");
            int requestsBefore = server.requests().size();
            ProcessHarness.ProcessResult rejected =
                    run(command, server, childEnvironment(sandbox.directory, testKey), tooMany.toArray(String[]::new));

            assertEquals(2, rejected.exitStatus(), () -> "the cap is a usage rule: " + describe(rejected, testKey));
            assertTrue(
                    new String(rejected.stderr(), UTF_8).contains("at most 200 ids"),
                    () -> describe(rejected, testKey));
            assertEquals(requestsBefore, server.requests().size(), "a rejected invocation never dispatches");
            assertTokenMaterialAbsent(rejected, testKey);
        }
    }

    /**
     * A later chunk failure follows the pagination family's partial-failure rules:
     * jsonl keeps the first chunk's twenty streamed records and ends in the counted
     * error record with the upstream exit status, human emits no payload and one
     * counted diagnostic, and json writes the counted failure envelope.
     */
    public static void laterChunkFailureFollowsThePaginationPartialRules(List<String> command, String commandName)
            throws Exception {
        String testKey = freshToken();
        SchemaCatalog schemas = new SchemaCatalog();
        List<String> ids = fortyFiveIds();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer jsonlServer = failingSecondChunkServer(ids);
                ScriptedSseServer humanServer = failingSecondChunkServer(ids);
                ScriptedSseServer jsonServer = failingSecondChunkServer(ids)) {
            ScriptedSseServer server = jsonlServer;
            ProcessHarness.ProcessResult jsonl =
                    runIds(command, server, childEnvironment(sandbox.directory, testKey), ids, "--output", "jsonl");

            assertEquals(7, jsonl.exitStatus(), () -> "the failed chunk's kind owns the status: " + describe(jsonl, testKey));
            String[] lines = new String(jsonl.stdout(), UTF_8).split("\n", -1);
            assertEquals(
                    22,
                    lines.length,
                    "twenty streamed records, one error record, one terminator: " + describe(jsonl, testKey));
            assertTrue(lines[0].contains("\"id\":\"poi-1\""), "the first chunk's records stand");
            JsonNode error = READER.readTree(lines[20]);
            assertEquals("error", error.path("type").asText());
            assertEquals("UPSTREAM_ERROR", error.path("code").asText());
            assertEquals(3, error.path("requested_requests").asInt());
            assertEquals(1, error.path("received_requests").asInt());
            assertEquals("", new String(jsonl.stderr(), UTF_8), "jsonl owns its failure explanation: " + describe(jsonl, testKey));
            assertTrue(
                    schemas.validateText("jsonl-record.schema.json", lines[20]).isEmpty(),
                    "the error record satisfies the published record schema");

            server = humanServer;
            ProcessHarness.ProcessResult human =
                    runIds(command, server, childEnvironment(sandbox.directory, testKey), ids);
            assertEquals(7, human.exitStatus(), () -> describe(human, testKey));
            assertEquals("", new String(human.stdout(), UTF_8), "human mode buffers: no partial payload");
            List<String> diagnostics = new String(human.stderr(), UTF_8).lines().toList();
            assertEquals(1, diagnostics.size(), () -> describe(human, testKey));
            assertTrue(
                    diagnostics.getFirst().equals(commandName + ": upstream exchange failed with status 500"
                            + " (1 of 3 requests completed)"),
                    "the counted diagnostic names the walk's request counts: " + diagnostics.getFirst());

            server = jsonServer;
            ProcessHarness.ProcessResult json =
                    runIds(command, server, childEnvironment(sandbox.directory, testKey), ids, "--output", "json");
            assertEquals(7, json.exitStatus(), () -> describe(json, testKey));
            assertTrue(
                    new String(json.stdout(), UTF_8)
                            .contains("\"details\":{\"requested_requests\":3,\"received_requests\":1}"),
                    () -> describe(json, testKey));
            assertTokenMaterialAbsent(json, testKey);
        }
    }

    /**
     * An exhausted request window paces the next chunk behind its reset: the JSON
     * envelope's second upstream entry reports the wait it performed, and the second
     * request provably arrives after the reset elapsed.
     */
    public static void chunkPacingWaitsBehindAnExhaustedWindow(List<String> command) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = ScriptedSseServer.startSequence(
                        ScriptedSseServer.builder()
                                .statusCode(200)
                                .header("Content-Type", "application/json")
                                .header("X-RateLimit-Limit", "1")
                                .header("X-RateLimit-Policy", "request")
                                .header("X-RateLimit-Remaining", "0")
                                .header("X-RateLimit-Reset", "1")
                                .writeBytes(detailsBody("poi-1")),
                        detailsChunk("poi-21"))) {
            List<String> ids = new ArrayList<>();
            ids.add("poi-1");
            ids.addAll(IntStream.rangeClosed(21, 40).mapToObj(number -> "poi-" + number).toList());
            long before = System.nanoTime();
            ProcessHarness.ProcessResult result =
                    runIds(command, server, childEnvironment(sandbox.directory, testKey), ids, "--output", "json");

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            long elapsedMillis = (System.nanoTime() - before) / 1_000_000;
            assertTrue(
                    elapsedMillis >= 900,
                    () -> "the second chunk must wait out the exhausted window's one-second reset, took " + elapsedMillis + "ms");
            JsonNode second = READER.readTree(result.stdout()).path("data").path("upstream").get(1);
            int waitedMs = second.path("meta").path("waited_ms").asInt();
            assertTrue(
                    waitedMs >= 900 && waitedMs <= 1000,
                    "the paced chunk reports the reset still outstanding at wait start — the one-second"
                            + " reset minus the body-read time already spent — never the reset re-anchored"
                            + " at the wait: waited_ms=" + waitedMs);
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /** Raw output is refused with the usage exit before the server can observe any request. */
    public static void rawOutputIsRejectedBeforeAnyDispatch(List<String> command) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = ScriptedSseServer.startSequence(detailsChunk("poi-a"))) {
            ProcessHarness.ProcessResult result =
                    run(command, server, childEnvironment(sandbox.directory, testKey), "--output", "raw", "poi-a");

            assertEquals(2, result.exitStatus(), () -> describe(result, testKey));
            assertTrue(
                    new String(result.stderr(), UTF_8).contains("raw is not accepted by this command"),
                    () -> describe(result, testKey));
            assertEquals("", new String(result.stdout(), UTF_8), () -> describe(result, testKey));
            assertEquals(0, server.requests().size(), "raw is rejected before anything is dispatched");
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /** A 401 on the first chunk keeps the authentication exit in human and json modes. */
    public static void authenticationFailureExitsFourInHumanAndJsonModes(List<String> command, String commandName)
            throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = ScriptedSseServer.startSequence(
                        ScriptedSseServer.builder()
                                .statusCode(401)
                                .header("Content-Type", "application/json")
                                .writeBytes(UNAUTHORIZED_ERROR))) {
            ProcessHarness.ProcessResult human =
                    run(command, server, childEnvironment(sandbox.directory, testKey), "poi-a");
            assertEquals(4, human.exitStatus(), () -> describe(human, testKey));
            assertEquals("", new String(human.stdout(), UTF_8), () -> describe(human, testKey));
            List<String> diagnostics = new String(human.stderr(), UTF_8).lines().toList();
            assertEquals(1, diagnostics.size(), () -> describe(human, testKey));
            assertTrue(
                    diagnostics.getFirst().startsWith(commandName + ": "),
                    () -> "the diagnostic names the command: " + describe(human, testKey));
            assertTokenMaterialAbsent(human, testKey);

            ProcessHarness.ProcessResult json = run(
                    command, server, childEnvironment(sandbox.directory, testKey), "--output", "json", "poi-a");
            assertEquals(4, json.exitStatus(), () -> describe(json, testKey));
            JsonNode envelope = READER.readTree(json.stdout());
            assertFalse(envelope.path("ok").asBoolean());
            assertEquals("AUTHENTICATION_FAILED", envelope.path("error").path("code").asText());
            assertTokenMaterialAbsent(json, testKey);
        }
    }

    /**
     * A loopback run authenticates every chunk with the test key, never forwards the
     * stored decoy credential, and dispatches exactly the chunk requests the id count
     * composes.
     */
    public static void loopbackRunSendsTheTestKeyOnEveryChunk(List<String> command) throws Exception {
        String testKey = freshToken();
        String storedDecoy = freshToken();
        List<String> ids = fortyFiveIds();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = ScriptedSseServer.startSequence(
                        detailsChunk(ids.subList(0, 20)),
                        detailsChunk(ids.subList(20, 40)),
                        detailsChunk(ids.subList(40, 45)))) {
            Map<String, String> environment = childEnvironment(sandbox.directory, testKey);
            environment.put("BRAVE_API_KEY", storedDecoy);
            ProcessHarness.ProcessResult result = runIds(command, server, environment, ids);

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey, storedDecoy));
            List<ScriptedSseServer.RecordedRequest> requests = server.requests();
            assertEquals(3, requests.size());
            for (ScriptedSseServer.RecordedRequest request : requests) {
                assertEquals(
                        Optional.of(testKey),
                        request.firstHeader("X-Subscription-Token"),
                        "every chunk carries the loopback test key");
                assertFalse(request.path().contains(storedDecoy), "the stored credential never rides the request line");
                for (List<String> values : request.headers().values()) {
                    for (String value : values) {
                        assertFalse(value.contains(storedDecoy), "the stored credential never reaches a loopback peer");
                    }
                }
            }
            assertTokenMaterialAbsent(result, testKey, storedDecoy);
        }
    }

    /** The Api-Version pin travels verbatim on every chunk's wire. */
    public static void apiVersionPinReachesTheWire(List<String> command) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = ScriptedSseServer.startSequence(
                        detailsChunk("poi-1"), detailsChunk("poi-21"))) {
            ProcessHarness.ProcessResult result = run(
                    command,
                    server,
                    childEnvironment(sandbox.directory, testKey),
                    "--api-version",
                    RESPONSE_API_VERSION,
                    "poi-1",
                    "poi-21");

            assertEquals(0, result.exitStatus(), () -> describe(result, testKey));
            for (ScriptedSseServer.RecordedRequest request : server.requests()) {
                assertEquals(
                        Optional.of(RESPONSE_API_VERSION),
                        request.firstHeader("Api-Version"),
                        "the pin rides every chunk exactly as spelled");
            }
            assertTokenMaterialAbsent(result, testKey);
        }
    }

    /**
     * A downstream consumer closing the pipe during a JSONL fan-out — the
     * head-of-stream reader that leaves after the first chunk — is silent success: the
     * child exits exactly 0, writes no diagnostic, and produces no stack trace. The
     * second chunk is held back until the consumer closed, so the broken pipe provably
     * strikes the mid-walk record writer.
     */
    public static void downstreamPipeCloseMidFanOutIsSilentZero(List<String> command) throws Exception {
        String testKey = freshToken();
        java.util.concurrent.CountDownLatch releaseSecondChunk = new java.util.concurrent.CountDownLatch(1);
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = ScriptedSseServer.startSequence(
                        detailsChunk(List.of("poi-1")),
                        ScriptedSseServer.builder()
                                .statusCode(200)
                                .header("Content-Type", "application/json")
                                .stallUntil(releaseSecondChunk)
                                .writeBytes(detailsBody("poi-2")))) {
            List<String> full = new ArrayList<>(command);
            full.add("--base-url");
            full.add(server.baseUrl().toString());
            full.addAll(List.of("--output", "jsonl", "poi-1", "poi-2"));
            try (ProcessHarness.Session session =
                    new ProcessHarness().start(full, childEnvironment(sandbox.directory, testKey), SCRUBBED)) {
                byte[] seen = readUntilContains(session.stdout(), "\"position\":0".getBytes(UTF_8), PROBE_DEADLINE);
                assertTrue(seen.length > 0, "the walk streams its first chunk's records before the consumer leaves");

                session.stdout().close();
                releaseSecondChunk.countDown();

                assertTrue(
                        session.awaitExit(java.time.Duration.ofSeconds(60)),
                        "the child must terminate once the consumer closed the pipe mid-fan-out");
                assertEquals(
                        0,
                        session.exitValue(),
                        () -> "a mid-fan-out closed pipe is successful early termination, stderr=<"
                                + new String(session.stderr(), UTF_8) + ">");
                assertEquals("", new String(session.stderr(), UTF_8), "early termination stays silent");
            }
        } finally {
            releaseSecondChunk.countDown();
        }
    }

    /**
     * A SIGINT that lands while a chunk fan-out is paced behind an exhausted rate-limit
     * window — in the pacing wait after the first chunk or, for a child slow to consume the
     * served chunk, inside the first exchange — renders the walk's transport-failure
     * document, jsonl's counted error record or human's one stderr diagnostic, while the
     * process itself exits by the conventional interrupt status 130.
     */
    public static void sigintDuringPacedChunkWalkExits130(List<String> command, String commandName) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = pacedChunkServer()) {
            List<String> prefix = new ArrayList<>(command);
            prefix.add("--base-url");
            prefix.add(server.baseUrl().toString());

            List<String> jsonlCommand = new ArrayList<>(prefix);
            jsonlCommand.addAll(List.of("--output", "jsonl"));
            jsonlCommand.addAll(pacedWalkIds());
            try (ProcessHarness.Session session =
                    new ProcessHarness().start(jsonlCommand, childEnvironment(sandbox.directory, testKey), SCRUBBED)) {
                byte[] seen = readUntilContains(session.stdout(), "\"position\":0".getBytes(UTF_8), PROBE_DEADLINE);

                session.signal("INT");

                assertTrue(session.awaitExit(PROBE_DEADLINE), "an interrupted fan-out must exit promptly");
                assertEquals(
                        130,
                        session.exitValue(),
                        () -> "the latched interrupt owns the status, stderr=<" + new String(session.stderr(), UTF_8) + ">");
                assertEquals("", new String(session.stderr(), UTF_8), "jsonl owns its failure explanation on stdout");
                String[] lines = new String(concat(seen, drainAfterExit(session)), UTF_8).split("\n", -1);
                JsonNode error = READER.readTree(lines[lines.length - 2]);
                assertEquals("error", error.path("type").asText(), "the walk ends in its transport-failure record");
                assertEquals("TRANSPORT_ERROR", error.path("code").asText());
                assertEquals(2, error.path("requested_requests").asInt(), "the error record carries the chunk budget");
                assertEquals(1, error.path("received_requests").asInt(), "the completed chunk stays countable");
            }

            List<String> humanCommand = new ArrayList<>(prefix);
            humanCommand.addAll(pacedWalkIds());
            int humanRequestCount = server.requests().size() + 1;
            try (ProcessHarness.Session session =
                    new ProcessHarness().start(humanCommand, childEnvironment(sandbox.directory, testKey), SCRUBBED)) {
                assertTrue(server.awaitRequestCount(humanRequestCount, PROBE_DEADLINE), "the current fan-out must reach the scripted server");
                // the current child has a live exchange: the signal can land while it
                // consumes the first chunk or while it waits for the rate-limit reset

                session.signal("INT");

                assertTrue(session.awaitExit(PROBE_DEADLINE), "an interrupted fan-out must exit promptly");
                assertEquals(
                        130,
                        session.exitValue(),
                        () -> "the latched interrupt owns the status, stderr=<" + new String(session.stderr(), UTF_8) + ">");
                assertEquals("", new String(drainAfterExit(session), UTF_8), "a buffered mode emits no partial payload");
                List<String> diagnostics = new String(session.stderr(), UTF_8).lines().toList();
                assertEquals(1, diagnostics.size(), () -> "exactly one diagnostic line: " + diagnostics);
                assertTrue(
                        diagnostics.getFirst().matches(
                                commandName + ": (pagination was (interrupted while waiting for the rate-limit reset|cancelled)"
                                        + " before page 2 \\(1 of 2 requests completed\\)"
                                        + "|upstream exchange cancelled \\(0 of 2 requests completed\\))"),
                        () -> "the diagnostic names the stopped walk with its counts: " + diagnostics);
            }
        }
    }

    /** Launches the command with the leading options then the ids as the positional tail. */
    private static ProcessHarness.ProcessResult runIds(
            List<String> command,
            ScriptedSseServer server,
            Map<String, String> environment,
            List<String> ids,
            String... options)
            throws Exception {
        List<String> arguments = new ArrayList<>(List.of(options));
        arguments.addAll(ids);
        return run(command, server, environment, arguments.toArray(String[]::new));
    }

    /** One served chunk that leaves the request window exhausted behind a one-hour reset. */
    private static ScriptedSseServer pacedChunkServer() throws java.io.IOException {
        return ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .header("X-RateLimit-Limit", "1")
                .header("X-RateLimit-Policy", "request")
                .header("X-RateLimit-Remaining", "0")
                .header("X-RateLimit-Reset", "3600")
                .writeBytes(detailsBody("poi-1"))
                .start();
    }

    /** Twenty-one ids: two chunks, so the walk paces before its second chunk request. */
    private static List<String> pacedWalkIds() {
        return IntStream.rangeClosed(1, 21).mapToObj(number -> "poi-" + number).toList();
    }

    /** A sequence whose first chunk serves and whose second chunk fails with a 500. */
    private static ScriptedSseServer failingSecondChunkServer(List<String> ids) throws java.io.IOException {
        return ScriptedSseServer.startSequence(
                detailsChunk(ids.subList(0, 20)),
                ScriptedSseServer.builder()
                        .statusCode(500)
                        .header("Content-Type", "application/json")
                        .writeBytes(SERVER_ERROR));
    }

    private static List<String> fortyFiveIds() {
        return IntStream.rangeClosed(1, 45).mapToObj(number -> "poi-" + number).toList();
    }

    /** The exact repeated-ids wire query of one chunk slice. */
    private static String queryOf(List<String> ids) {
        StringBuilder query = new StringBuilder();
        for (String id : ids) {
            if (!query.isEmpty()) {
                query.append('&');
            }
            query.append("ids=").append(id);
        }
        return query.toString();
    }

    /** One 200 details response script serving the given ids as full detail entries. */
    private static ScriptedSseServer.Builder detailsChunk(List<String> ids) {
        return ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .writeBytes(detailsBody(ids.toArray(String[]::new)));
    }

    private static ScriptedSseServer.Builder detailsChunk(String... ids) {
        return detailsChunk(List.of(ids));
    }

    private static byte[] detailsBody(String... ids) {
        StringBuilder entries = new StringBuilder();
        for (String id : ids) {
            if (!entries.isEmpty()) {
                entries.append(',');
            }
            entries.append("{\"id\":\"").append(id).append("\",\"title\":\"First POI\",\"url\":\"https://example.com/first\",")
                    .append("\"description\":\"First text.\",\"postal_address\":{\"displayAddress\":\"1 Main St\"},")
                    .append("\"contact\":{\"telephone\":\"+1 215 555 0100\"},\"price_range\":\"$$\",")
                    .append("\"timezone\":\"America/New_York\",\"thumbnail\":{\"src\":\"https://example.com/thumb.jpg\"}}");
        }
        return ("{\"type\":\"local_pois\",\"results\":[" + entries + "]}").getBytes(UTF_8);
    }

    private static ScriptedSseServer.Builder describeChunk(String... ids) {
        return describeChunk(List.of(ids));
    }

    /** One 200 descriptions response script serving the given ids with description text. */
    private static ScriptedSseServer.Builder describeChunk(List<String> ids) {
        StringBuilder entries = new StringBuilder();
        for (String id : ids) {
            if (!entries.isEmpty()) {
                entries.append(',');
            }
            entries.append("{\"id\":\"").append(id).append("\",\"description\":\"An AI-generated summary of ").append(id).append(".\"}");
        }
        byte[] body = ("{\"results\":[" + entries + "]}").getBytes(UTF_8);
        return ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .writeBytes(body);
    }
}

package io.amscotti.bravesearch.testsupport;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Client-observable behavior of the scripted byte server: bodies arrive in scripted order, a
 * scripted abrupt close surfaces as a client IOException, requests are recorded for assertions,
 * and stalls hold responses back until their latch opens.
 */
final class ScriptedSseServerTest {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void tearDown() {
        client.shutdownNow();
    }

    @Test
    void servesScriptedBytesInOrderWithScriptedStatusAndHeaders() throws Exception {
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "text/event-stream")
                .header("X-Script-Marker", "streaming")
                .writeBytes("first-chunk".getBytes(UTF_8))
                .delay(Duration.ofMillis(20))
                .heartbeat("keep-alive")
                .writeBytes("second-chunk".getBytes(UTF_8))
                .flush()
                .start()) {
            HttpRequest request = request(server).build();
            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("text/event-stream", response.headers().firstValue("Content-Type").orElseThrow());
            assertEquals("streaming", response.headers().firstValue("X-Script-Marker").orElseThrow());
            assertEquals("first-chunk: keep-alive\n\nsecond-chunk", response.body());
        }
    }

    @Test
    void abruptCloseSurfacesAsClientIoException() throws Exception {
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .writeBytes("data: partial\n\n".getBytes(UTF_8))
                .flush()
                .abruptClose()
                .start()) {
            HttpRequest request = request(server).build();
            assertThrows(IOException.class, () -> client.send(request, BodyHandlers.ofString()));
        }
    }

    @Test
    void recordsMethodPathAndHeadersOfEachRequest() throws Exception {
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .writeBytes("data: recorded\n\n".getBytes(UTF_8))
                .start()) {
            HttpRequest request = request(server)
                    .header("X-Probe-Id", "probe-token")
                    .build();
            client.send(request, BodyHandlers.ofString());

            assertTrue(server.awaitFirstRequest(REQUEST_TIMEOUT), "first request never released the latch");
            var requests = server.requests();
            assertEquals(1, requests.size());
            ScriptedSseServer.RecordedRequest recorded = requests.getFirst();
            assertEquals("GET", recorded.method());
            assertEquals("/sse?since=1", recorded.path());
            assertEquals("probe-token", recorded.firstHeader("x-probe-id").orElseThrow());
        }
    }

    @Test
    void aRequestBodyBeyondTheRecordingBoundFailsLoudlyInsteadOfRecordingTruncated() throws Exception {
        byte[] oversized = new byte[1024 * 1024 + 1];
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .writeBytes("data: ok\n\n".getBytes(UTF_8))
                .start()) {
            HttpRequest request = HttpRequest.newBuilder(server.baseUrl().resolve("/oversized"))
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(oversized))
                    .build();

            int servedStatus = sendRecordingOnlyStatuses(request);
            assertTrue(
                    servedStatus != 200,
                    "the scripted response must never serve a request body past the recording bound");
            assertTrue(
                    server.requests().isEmpty(),
                    "no truncated body may be recorded as assertion evidence for an over-bound request");
        }
    }

    /** The status actually served, or 0 when the exchange died before any status arrived. */
    private int sendRecordingOnlyStatuses(HttpRequest request) throws InterruptedException {
        try {
            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            return response.statusCode();
        } catch (IOException connectionTorn) {
            return 0;
        }
    }

    @Test
    void awaitFirstRequestTimesOutWhileNoRequestArrives() throws Exception {
        try (ScriptedSseServer server = ScriptedSseServer.builder().start()) {
            assertFalse(server.awaitFirstRequest(Duration.ofMillis(200)));
            assertTrue(server.requests().isEmpty());
        }
    }

    @Test
    void requestCountWaitRequiresANewArrivalAfterAnEarlierRequest() throws Exception {
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .writeBytes("ok".getBytes(UTF_8))
                .start()) {
            client.send(request(server).build(), BodyHandlers.ofString());
            assertTrue(server.awaitRequestCount(1, Duration.ZERO));
            assertFalse(server.awaitRequestCount(2, Duration.ofMillis(50)));

            client.sendAsync(request(server).build(), BodyHandlers.ofString());
            assertTrue(server.awaitRequestCount(2, REQUEST_TIMEOUT));
            assertEquals(2, server.requests().size());
        }
    }

    @Test
    void stalledScriptCompletesOnlyWhenItsLatchOpens() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .stallUntil(release)
                .writeBytes("data: late\n\n".getBytes(UTF_8))
                .start()) {
            CompletableFuture<HttpResponse<String>> pending =
                    client.sendAsync(request(server).build(), BodyHandlers.ofString());
            assertFalse(pending.isDone(), "response completed while the script was still stalled");
            release.countDown();
            HttpResponse<String> response = pending.get(10, TimeUnit.SECONDS);
            assertEquals("data: late\n\n", response.body());
        }
    }

    @Test
    void heldHeadersWithholdTheResponseUntilTheirLatchOpens() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .holdHeadersUntil(release)
                .writeBytes("data: after-headers\n\n".getBytes(UTF_8))
                .start()) {
            CompletableFuture<HttpResponse<String>> pending =
                    client.sendAsync(request(server).build(), BodyHandlers.ofString());
            assertFalse(pending.isDone(), "response completed while headers were still held");
            release.countDown();
            HttpResponse<String> response = pending.get(10, TimeUnit.SECONDS);
            assertEquals("data: after-headers\n\n", response.body());
        }
    }

    @Test
    void sequentialResponsesServeInRequestOrderAndRepeatTheLastOne() throws Exception {
        try (ScriptedSseServer server = ScriptedSseServer.startSequence(
                ScriptedSseServer.builder()
                        .statusCode(200)
                        .header("Content-Type", "application/json")
                        .writeBytes("first-response".getBytes(UTF_8)),
                ScriptedSseServer.builder()
                        .statusCode(500)
                        .header("Content-Type", "application/json")
                        .writeBytes("second-response".getBytes(UTF_8)))) {
            HttpResponse<String> first = client.send(request(server).build(), BodyHandlers.ofString());
            assertEquals(200, first.statusCode());
            assertEquals("first-response", first.body());

            HttpResponse<String> second = client.send(request(server).build(), BodyHandlers.ofString());
            assertEquals(500, second.statusCode());
            assertEquals("second-response", second.body());

            HttpResponse<String> beyond = client.send(request(server).build(), BodyHandlers.ofString());
            assertEquals(
                    500,
                    beyond.statusCode(),
                    "requests beyond the sequence repeat the last scripted response");
            assertEquals("second-response", beyond.body());
        }
    }

    @Test
    void aClientThatDisconnectsMidBodyFiresTheClosedConnectionWitness() throws Exception {
        byte[] chunk = new byte[8 * 1024];
        ScriptedSseServer.Builder script = ScriptedSseServer.builder();
        for (int i = 0; i < 2048; i++) {
            script.writeBytes(chunk).flush();
        }
        try (ScriptedSseServer server = script.start()) {
            HttpResponse<java.io.InputStream> response =
                    client.send(request(server).build(), BodyHandlers.ofInputStream());
            byte[] firstRead = response.body().readNBytes(chunk.length);
            assertEquals(chunk.length, firstRead.length, "the first chunk must arrive before the disconnect");
            response.body().close();

            assertTrue(
                    server.awaitConnectionClosed(Duration.ofSeconds(10)),
                    "a handler-side write after the client disconnect must fire the witness");
        }
    }

    private HttpRequest.Builder request(ScriptedSseServer server) {
        return HttpRequest.newBuilder(server.baseUrl().resolve("/sse?since=1")).timeout(REQUEST_TIMEOUT);
    }
}

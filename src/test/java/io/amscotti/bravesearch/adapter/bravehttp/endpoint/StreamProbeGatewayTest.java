package io.amscotti.bravesearch.adapter.bravehttp.endpoint;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.application.port.out.StreamProbePort;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.testsupport.ScriptedSseServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Opening contracts of the probe transport: a peer that accepts the connection but never
 * answers is bounded by the request timeout derived from the wall budget, so the opening phase
 * can never block past the run's deadline.
 */
final class StreamProbeGatewayTest {

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void openTimesOutWhenHeadersNeverArrive() throws Exception {
        CountDownLatch neverSendHeaders = new CountDownLatch(1);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .holdHeadersUntil(neverSendHeaders)
                .writeBytes("never delivered".getBytes(UTF_8))
                .start()) {
            StreamProbeGateway gateway = new StreamProbeGateway();

            IOException failure = assertThrows(
                    IOException.class,
                    () -> gateway.open(
                            server.baseUrl().resolve("events"),
                            new CancellationContext(),
                            null,
                            Duration.ofMillis(500)));

            assertInstanceOf(HttpTimeoutException.class, failure, "the silent peer must surface as a request timeout");
            assertTrue(server.awaitFirstRequest(Duration.ofSeconds(5)), "the peer must have seen the request");
        } finally {
            neverSendHeaders.countDown();
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void openDeliversThePeerBytesAndReleasesEveryTransportResource() throws Exception {
        byte[] payload = "data: hello\n\n".getBytes(UTF_8);
        try (ScriptedSseServer server =
                ScriptedSseServer.builder().writeBytesUntilClosed(payload).start()) {
            StreamProbeGateway gateway = new StreamProbeGateway();

            var stream = gateway.open(
                    server.baseUrl().resolve("events"), new CancellationContext(), null, null);
            byte[] delivered = readUntilFirstChunk(stream);

            assertTrue(server.awaitFirstRequest(Duration.ofSeconds(5)), "the peer must have seen the request");
            assertEquals(
                    "text/event-stream",
                    server.requests().getFirst().firstHeader("Accept").orElseThrow(),
                    "the probe negotiates the event stream form");
            assertTrue(
                    delivered.length >= payload.length,
                    "the probe must surface at least the first scripted chunk, got " + delivered.length + " bytes");
            for (int index = 0; index < payload.length; index++) {
                assertEquals(payload[index], delivered[index], "the probe must surface the peer bytes verbatim");
            }

            stream.close();

            assertTrue(
                    server.awaitConnectionClosed(Duration.ofSeconds(10)),
                    "closing the probe must sever the exchange the peer is still writing to");
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void openHonorsAnExplicitWallBudgetForTheHeadersPhase() throws Exception {
        byte[] payload = "data: hello\n\n".getBytes(UTF_8);
        try (ScriptedSseServer server =
                ScriptedSseServer.builder().writeBytesUntilClosed(payload).start()) {
            StreamProbeGateway gateway = new StreamProbeGateway();

            var stream = gateway.open(
                    server.baseUrl().resolve("events"),
                    new CancellationContext(),
                    null,
                    Duration.ofSeconds(30));
            try {
                byte[] delivered = readUntilFirstChunk(stream);

                assertTrue(
                        delivered.length >= payload.length,
                        "a budgeted open must still deliver the peer bytes, got " + delivered.length + " bytes");
            } finally {
                stream.close();
            }
            assertTrue(server.awaitFirstRequest(Duration.ofSeconds(5)), "the peer must have seen the request");
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void openRejectsANonSuccessStatusAndReleasesTheTransport() throws Exception {
        try (ScriptedSseServer server =
                ScriptedSseServer.builder().statusCode(500).writeBytes("gone".getBytes(UTF_8)).start()) {
            StreamProbeGateway gateway = new StreamProbeGateway();

            IOException failure = assertThrows(
                    IOException.class,
                    () -> gateway.open(
                            server.baseUrl().resolve("events"),
                            new CancellationContext(),
                            null,
                            Duration.ofSeconds(5)));

            assertTrue(
                    failure.getMessage().contains("500"),
                    "the rejection names the status, never the body: " + failure.getMessage());
            assertTrue(server.awaitFirstRequest(Duration.ofSeconds(5)), "the peer must have seen the request");
        }
    }

    /** Subscribes for the whole stream and returns everything delivered up to the first chunk. */
    private static byte[] readUntilFirstChunk(StreamProbePort.ProbeStream stream) throws InterruptedException {
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        CountDownLatch firstChunk = new CountDownLatch(1);
        stream.bytes().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(byte[] item) {
                received.writeBytes(item);
                firstChunk.countDown();
            }

            @Override
            public void onError(Throwable failure) {
                firstChunk.countDown();
            }

            @Override
            public void onComplete() {
                firstChunk.countDown();
            }
        });

        assertTrue(firstChunk.await(10, TimeUnit.SECONDS), "the peer must start delivering bytes");
        return received.toByteArray();
    }
}

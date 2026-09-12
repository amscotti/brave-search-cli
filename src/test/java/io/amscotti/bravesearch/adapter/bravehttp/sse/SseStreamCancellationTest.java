package io.amscotti.bravesearch.adapter.bravehttp.sse;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.SseException;
import io.amscotti.bravesearch.application.stream.StreamBodyPublisher;
import io.amscotti.bravesearch.testsupport.ScriptedSseServer;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Cancellation wiring of the event-stream parser behind the real streaming body publisher: a
 * bounds breach detected inside the subscriber must latch the run's terminal cause and end the
 * body subscription, and the scripted peer must observe the severed connection — the same
 * unblocking contract the body publisher guarantees for interruption and deadlines.
 */
final class SseStreamCancellationTest {

    private static final long TINY_LINE_BOUND = 1024;
    private static final long TINY_EVENT_BOUND = 64;

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void eventBoundOverflowCancelsTheRunAndSeversTheServerConnection() throws Exception {
        byte[] endlessEventLines = "data: 0123456789abcdef\n".getBytes(UTF_8);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .header("Content-Type", "text/event-stream")
                .writeBytesUntilClosed(endlessEventLines)
                .start()) {
            CancellationContext cancellation = new CancellationContext();
            BreachWitness witness = new BreachWitness();

            driveStreamThroughPublisher(server.baseUrl().resolve("answers"), cancellation, witness, TINY_LINE_BOUND, TINY_EVENT_BOUND);

            assertNotNull(witness.breach.get(), "the event bound must breach with the typed overflow");
            assertEquals(
                    CancellationContext.Cause.SUBSCRIBER_FAILURE,
                    cancellation.cause().orElseThrow(),
                    "the breach must latch the run's terminal cause");
            assertTrue(
                    server.awaitConnectionClosed(java.time.Duration.ofSeconds(10)),
                    "the peer must observe the closed connection");
        }
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void lineBoundOverflowCancelsTheRunAndSeversTheServerConnection() throws Exception {
        byte[] endlessLine = "0123456789abcdef".getBytes(UTF_8);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .header("Content-Type", "text/event-stream")
                .writeBytesUntilClosed(endlessLine)
                .start()) {
            CancellationContext cancellation = new CancellationContext();
            BreachWitness witness = new BreachWitness();

            driveStreamThroughPublisher(server.baseUrl().resolve("answers"), cancellation, witness, 64, 1024 * 1024);

            assertNotNull(witness.breach.get(), "the line bound must breach with the typed overflow");
            assertEquals(
                    CancellationContext.Cause.SUBSCRIBER_FAILURE,
                    cancellation.cause().orElseThrow(),
                    "the breach must latch the run's terminal cause");
            assertTrue(server.awaitConnectionClosed(java.time.Duration.ofSeconds(10)), "the peer must observe the closed connection");
        }
    }

    private static void driveStreamThroughPublisher(
            URI url, CancellationContext cancellation, BreachWitness witness, long lineBound, long eventBound)
            throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<InputStream> response = client.send(
                HttpRequest.newBuilder(url).header("Accept", "text/event-stream").GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode(), "the scripted peer must answer with a stream");

        SseEventParser parser = new SseEventParser(event -> {}, cancellation, lineBound, eventBound);
        ExecutorService reader = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "sse-breach-reader");
            thread.setDaemon(true);
            return thread;
        });
        StreamBodyPublisher publisher =
                new StreamBodyPublisher(response.body(), reader, cancellation, Clock.systemUTC(), null, null);
        ParsingSubscriber subscriber = new ParsingSubscriber(parser, witness);
        try {
            publisher.subscribe(subscriber);
            assertTrue(witness.breached.await(20, TimeUnit.SECONDS), "the breach must fire within the run");
        } finally {
            subscriber.cancel();
            reader.shutdownNow();
        }
    }

    /** Captures the typed breach and the terminal error the publisher delivers for it. */
    private static final class BreachWitness {
        final CountDownLatch breached = new CountDownLatch(1);
        final AtomicReference<RuntimeException> breach = new AtomicReference<>();

        void observe(RuntimeException typed) {
            breach.set(typed);
            breached.countDown();
        }
    }

    /** Feeds every published chunk into the parser and cancels the subscription on a breach. */
    private static final class ParsingSubscriber implements Flow.Subscriber<byte[]> {
        private final SseEventParser parser;
        private final BreachWitness witness;
        private Flow.Subscription subscription;

        ParsingSubscriber(SseEventParser parser, BreachWitness witness) {
            this.parser = parser;
            this.witness = witness;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(byte[] chunk) {
            try {
                parser.feed(chunk, 0, chunk.length);
            } catch (SseException.Overflow breach) {
                witness.observe(breach);
                subscription.cancel();
            }
        }

        @Override
        public void onError(Throwable thrown) {
            // the publisher's terminal report; the breach itself is witnessed in onNext
        }

        @Override
        public void onComplete() {
            // an endless scripted stream never completes on its own
        }

        void cancel() {
            if (subscription != null) {
                subscription.cancel();
            }
        }
    }
}

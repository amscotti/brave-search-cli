package io.amscotti.bravesearch.application.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Reactive-stream contracts of the streaming-body publisher, exercised against a real HTTP
 * exchange whose handler scripts bytes and stalls on demand.
 */
final class StreamBodyPublisherTest {

    private static final Duration AWAIT = Duration.ofSeconds(10);
    private static final Duration SHORT_PROBE = Duration.ofMillis(300);
    private static final Duration TIGHT_TIMEOUT = Duration.ofMillis(250);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<CountDownLatch> releaseOnTeardown = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private HttpClient client;

    @AfterEach
    void tearDown() {
        releaseOnTeardown.forEach(CountDownLatch::countDown);
        executor.shutdownNow();
        if (client != null) {
            client.shutdownNow();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void demandZeroThenTwo() throws Exception {
        CountDownLatch chunkOneWritten = latch();
        CountDownLatch chunkTwoAllowed = latch();
        CountDownLatch finishAllowed = latch();
        InetSocketAddress address = start(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write('a');
                out.flush();
                chunkOneWritten.countDown();
                awaitRelease(chunkTwoAllowed);
                out.write('b');
                out.flush();
                awaitRelease(finishAllowed);
            }
        });

        RecordingSubscriber subscriber = new RecordingSubscriber(null, 0, 0);
        publisher(bodyFrom(address), null, null, new CancellationContext()).subscribe(subscriber);
        assertTrue(subscriber.subscribed.await(5, TimeUnit.SECONDS), "subscription must start");
        Flow.Subscription subscription = subscriber.subscription;

        subscription.request(0);
        assertTrue(chunkOneWritten.await(5, TimeUnit.SECONDS), "the server must have written a chunk");
        assertFalse(
                awaitTrue(SHORT_PROBE, () -> !subscriber.chunks.isEmpty()),
                "zero demand must withhold every chunk");

        subscription.request(2);
        assertTrue(awaitTrue(() -> subscriber.chunks.size() == 1), "the first chunk must arrive on demand");
        chunkTwoAllowed.countDown();
        assertTrue(awaitTrue(() -> subscriber.chunks.size() == 2), "the second chunk must arrive on demand");
        assertFalse(
                awaitTrue(SHORT_PROBE, () -> subscriber.chunks.size() > 2),
                "delivery must never run ahead of the requested credit");

        finishAllowed.countDown();
        subscription.request(1);
        assertTrue(awaitTrue(() -> subscriber.completed.get() == 1), "draining the body must complete exactly once");
        assertEquals(2, subscriber.chunks.size(), "no chunk beyond the requested credit");
        assertEquals(0, subscriber.errors.size(), "no error may accompany a clean drain");
    }

    @Test
    void cancelUnblocksStalledRead() throws Exception {
        CountDownLatch serverSawClose = new CountDownLatch(1);
        InetSocketAddress address = start(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write('x');
                out.flush();
                byte[] filler = new byte[64 * 1024];
                try {
                    for (int i = 0; i < 10_000; i++) {
                        out.write(filler);
                        out.flush();
                    }
                } catch (IOException expectedClose) {
                    serverSawClose.countDown();
                }
            } catch (IOException ignored) {
                // the flood ends when the reader cancels; closing the broken stream needs no witness
            }
        });

        RecordingSubscriber subscriber = new RecordingSubscriber(null, 0, 0);
        StreamBodyPublisher publisher = publisher(bodyFrom(address), null, null, new CancellationContext());
        publisher.subscribe(subscriber);
        assertTrue(subscriber.subscribed.await(5, TimeUnit.SECONDS), "subscription must start");
        subscriber.subscription.request(1);
        assertTrue(awaitTrue(() -> !subscriber.chunks.isEmpty()), "the first chunk must arrive");

        subscriber.subscription.cancel();
        assertTrue(readerReleasedTheExecutor(), "the reader task must end after cancel");
        assertEquals(0, subscriber.completed.get(), "cancel must not complete the subscriber");
        assertEquals(0, subscriber.errors.size(), "cancel must not error the subscriber");
        assertTrue(awaitTrue(() -> serverSawClose.getCount() == 0), "the server must observe the closed connection");
    }

    @Test
    void idleTimeoutFires() throws Exception {
        CountDownLatch stall = latch();
        InetSocketAddress address = start(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write('i');
                out.flush();
                awaitRelease(stall);
            }
        });

        CancellationContext cancellation = new CancellationContext();
        RecordingSubscriber subscriber = new RecordingSubscriber(null, 0, 0);
        publisher(bodyFrom(address), TIGHT_TIMEOUT, null, cancellation).subscribe(subscriber);
        assertTrue(subscriber.subscribed.await(5, TimeUnit.SECONDS), "subscription must start");
        subscriber.subscription.request(1);
        assertTrue(awaitTrue(() -> subscriber.chunks.size() == 1), "the first byte must arrive before the stall");

        assertTrue(awaitTrue(() -> !subscriber.errors.isEmpty()), "the idle timeout must deliver onError");
        Throwable error = subscriber.errors.get(0);
        assertInstanceOf(TimeoutException.class, error, "the idle timeout must surface as a timeout");
        assertTrue(error.getMessage().contains("idle"), "the error must name the idle timeout: " + error.getMessage());
        assertEquals(CancellationContext.Cause.IDLE_TIMEOUT, cancellation.cause().orElseThrow());
        assertEquals(0, subscriber.completed.get(), "a timeout must never complete the subscriber");
    }

    @Test
    void wallTimeoutFires() throws Exception {
        InetSocketAddress address = start(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                byte[] filler = new byte[1024];
                try {
                    for (int i = 0; i < 100_000; i++) {
                        out.write(filler);
                        out.flush();
                    }
                } catch (IOException expectedClose) {
                    // the reader closes the body after the deadline; the server ends there
                }
            } catch (IOException ignored) {
                // closing the broken stream after the flood ends needs no witness
            }
        });

        CancellationContext cancellation = new CancellationContext();
        RecordingSubscriber subscriber = new RecordingSubscriber(null, 0, 0);
        publisher(bodyFrom(address), null, Instant.now().plus(TIGHT_TIMEOUT), cancellation).subscribe(subscriber);
        assertTrue(subscriber.subscribed.await(5, TimeUnit.SECONDS), "subscription must start");
        subscriber.subscription.request(Long.MAX_VALUE);

        assertTrue(awaitTrue(() -> !subscriber.errors.isEmpty()), "the wall deadline must deliver onError");
        Throwable error = subscriber.errors.get(0);
        assertInstanceOf(TimeoutException.class, error, "the wall deadline must surface as a timeout");
        assertTrue(error.getMessage().contains("wall"), "the error must name the wall deadline: " + error.getMessage());
        assertEquals(CancellationContext.Cause.WALL_TIMEOUT, cancellation.cause().orElseThrow());
        assertEquals(0, subscriber.completed.get(), "a timeout must never complete the subscriber");
        assertFalse(subscriber.chunks.isEmpty(), "bytes must flow before the deadline cuts the stream");
    }

    @Test
    void secondSubscriberRejected() throws Exception {
        CountDownLatch stall = latch();
        InetSocketAddress address = start(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write('s');
                out.flush();
                awaitRelease(stall);
            }
        });

        RecordingSubscriber first = new RecordingSubscriber(null, 0, 0);
        RecordingSubscriber second = new RecordingSubscriber(null, 0, 0);
        StreamBodyPublisher publisher = publisher(bodyFrom(address), null, null, new CancellationContext());
        publisher.subscribe(first);
        assertTrue(first.subscribed.await(5, TimeUnit.SECONDS), "the first subscription must start");

        publisher.subscribe(second);
        assertTrue(second.subscribed.await(5, TimeUnit.SECONDS), "a refused subscriber still receives onSubscribe");
        assertNotNull(second.subscription, "the refused subscription must be handed over before the error");
        assertEquals(1, second.errors.size(), "the refused subscriber must receive exactly one error");
        assertInstanceOf(IllegalStateException.class, second.errors.get(0), "refusal is a double-subscription error");
        assertEquals(0, first.errors.size(), "the live subscription must stay untouched by the refusal");

        first.subscription.cancel();
        assertTrue(readerReleasedTheExecutor(), "the reader task must still end on the live subscription's cancel");
    }

    @Test
    void subscribeAfterTheTerminalDeliversOnErrorInsteadOfThrowing() {
        CancellationContext cancellation = new CancellationContext();
        StreamBodyPublisher cutDown = new StreamBodyPublisher(
                new ByteArrayInputStream(new byte[] {1}), executor, cancellation, Clock.systemUTC(), null, null);
        cutDown.cancel();

        RecordingSubscriber latecomer = new RecordingSubscriber(null, 0, 0);
        cutDown.subscribe(latecomer);

        assertTrue(latecomer.subscribed.getCount() == 0, "the Flow contract still hands over the subscription first");
        assertNotNull(latecomer.subscription, "the subscription handle arrives before the terminal error");
        assertEquals(1, latecomer.errors.size(), "a subscription after the terminal must receive exactly one error");
        assertInstanceOf(IllegalStateException.class, latecomer.errors.get(0), "a late subscription is a closed publisher");
        assertEquals(0, latecomer.completed.get(), "a late subscription is never completed");
        assertTrue(latecomer.chunks.isEmpty(), "a terminal publisher delivers no bytes");
    }

    @Test
    void subscribeOntoAShutdownExecutorDeliversTheRejectionAsOnError() {
        ExecutorService shutDown = Executors.newSingleThreadExecutor();
        shutDown.shutdownNow();
        CancellationContext cancellation = new CancellationContext();
        StreamBodyPublisher stranded = new StreamBodyPublisher(
                new ByteArrayInputStream(new byte[] {1}), shutDown, cancellation, Clock.systemUTC(), null, null);
        RecordingSubscriber newcomer = new RecordingSubscriber(null, 0, 0);

        stranded.subscribe(newcomer);

        assertEquals(0, newcomer.subscribed.getCount(), "the Flow contract still hands over the subscription first");
        assertEquals(1, newcomer.errors.size(), "the refused reader task must surface as exactly one error");
        assertInstanceOf(
                RejectedExecutionException.class, newcomer.errors.get(0), "the executor's refusal is the error itself");
        assertEquals(
                CancellationContext.Cause.TRANSPORT_FAILURE,
                cancellation.cause().orElseThrow(),
                "a refused reader still latches a cause, so no awaiting run can hang");
        assertEquals(0, newcomer.completed.get(), "a refused reader never completes the subscriber");
        assertTrue(newcomer.chunks.isEmpty(), "a refused reader delivers no bytes");
    }

    @Test
    void negativeRequestErrors() throws Exception {
        CountDownLatch stall = latch();
        InetSocketAddress address = start(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write('n');
                out.flush();
                awaitRelease(stall);
            }
        });

        RecordingSubscriber subscriber = new RecordingSubscriber(null, 0, 0);
        publisher(bodyFrom(address), null, null, new CancellationContext()).subscribe(subscriber);
        assertTrue(subscriber.subscribed.await(5, TimeUnit.SECONDS), "subscription must start");

        subscriber.subscription.request(1);
        subscriber.subscription.request(-1);
        assertTrue(awaitTrue(() -> !subscriber.errors.isEmpty()), "a negative request must deliver onError");
        assertInstanceOf(IllegalArgumentException.class, subscriber.errors.get(0), "the Flow spec error type");
        assertEquals(0, subscriber.completed.get(), "a protocol violation must never complete the subscriber");
        assertTrue(readerReleasedTheExecutor(), "the reader task must stop after the protocol violation");
    }

    @Test
    void negativeRequestLatchesSubscriberFailureBeforeErroring() throws Exception {
        CountDownLatch stall = latch();
        InetSocketAddress address = start(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write('n');
                out.flush();
                awaitRelease(stall);
            }
        });

        CancellationContext cancellation = new CancellationContext();
        RecordingSubscriber subscriber = new RecordingSubscriber(null, 0, 0);
        publisher(bodyFrom(address), null, null, cancellation).subscribe(subscriber);
        assertTrue(subscriber.subscribed.await(5, TimeUnit.SECONDS), "subscription must start");

        subscriber.subscription.request(-1);

        assertEquals(
                CancellationContext.Cause.SUBSCRIBER_FAILURE,
                cancellation.cause().orElseThrow(),
                "a protocol-violating subscriber must latch its cause before the error is delivered");
    }

    @Test
    void subscriberThrowingLatchesSubscriberFailure() throws Exception {
        CountDownLatch serverSawClose = new CountDownLatch(1);
        InetSocketAddress address = start(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                byte[] filler = new byte[1024];
                try {
                    for (int i = 0; i < 100_000; i++) {
                        out.write(filler);
                        out.flush();
                    }
                } catch (IOException expectedClose) {
                    serverSawClose.countDown();
                }
            } catch (IOException ignored) {
                // the flood ends when the reader stops; closing the broken stream needs no witness
            }
        });

        CancellationContext cancellation = new CancellationContext();
        RuntimeException subscriberExplosion = new RuntimeException("subscriber exploded");
        RecordingSubscriber subscriber = new RecordingSubscriber(subscriberExplosion, 0, 0);
        publisher(bodyFrom(address), null, null, cancellation).subscribe(subscriber);
        assertTrue(subscriber.subscribed.await(5, TimeUnit.SECONDS), "subscription must start");
        subscriber.subscription.request(Long.MAX_VALUE);

        assertTrue(
                awaitTrue(() -> cancellation.cause().orElse(null) == CancellationContext.Cause.SUBSCRIBER_FAILURE),
                "a throwing subscriber must latch the subscriber-failure cause");
        assertTrue(awaitTrue(() -> serverSawClose.getCount() == 0), "the body stream must be closed after the failure");
        assertTrue(readerReleasedTheExecutor(), "the reader task must not hang after the failure");
        assertEquals(0, subscriber.completed.get(), "a failed subscriber is never completed");
        assertEquals(1, subscriber.errors.size(), "the failing subscriber must receive exactly one onError");
    }

    @Test
    void noConcurrentCallbacks() throws Exception {
        InetSocketAddress address = start(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(new byte[10]);
                out.flush();
            }
        });

        RecordingSubscriber subscriber = new RecordingSubscriber(null, 1, 1);
        publisher(bodyFrom(address), null, null, new CancellationContext()).subscribe(subscriber);

        assertTrue(awaitTrue(() -> subscriber.completed.get() == 1), "paced demand must still drain to completion");
        assertEquals(10, subscriber.chunks.size(), "one chunk per requested credit");
        assertEquals(0, subscriber.errors.size(), "no error may accompany a clean drain");
        Set<Thread> callbackThreads = Set.copyOf(subscriber.threads);
        assertEquals(1, callbackThreads.size(), "every data callback must run on the single reader thread");
        assertEquals(11, subscriber.threads.size(), "ten onNext calls plus one onComplete were expected");
    }

    @Test
    void midStreamReset() throws Exception {
        InetSocketAddress address = start(exchange -> {
            exchange.sendResponseHeaders(200, 1000);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(new byte[100]);
                out.flush();
            }
        });

        CancellationContext cancellation = new CancellationContext();
        RecordingSubscriber subscriber = new RecordingSubscriber(null, 0, 0);
        publisher(bodyFrom(address), null, null, cancellation).subscribe(subscriber);
        assertTrue(subscriber.subscribed.await(5, TimeUnit.SECONDS), "subscription must start");
        subscriber.subscription.request(Long.MAX_VALUE);

        assertTrue(awaitTrue(() -> !subscriber.errors.isEmpty()), "an abrupt mid-body close must surface as onError");
        assertTrue(readerReleasedTheExecutor(), "the reader task must end after the reset");
        assertEquals(1, subscriber.errors.size(), "exactly one onError, with no retry");
        assertEquals(0, subscriber.completed.get(), "a broken body must never complete the subscriber");
        assertTrue(cancellation.cause().isEmpty(), "a raw stream failure must not latch a run cause");
    }

    private StreamBodyPublisher publisher(
            InputStream body, Duration idleTimeout, Instant wallDeadline, CancellationContext cancellation) {
        return new StreamBodyPublisher(body, executor, cancellation, Clock.systemUTC(), idleTimeout, wallDeadline);
    }

    private InetSocketAddress start(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", handler);
        server.start();
        return server.getAddress();
    }

    private InputStream bodyFrom(InetSocketAddress address) throws IOException, InterruptedException {
        client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + address.getPort() + "/"))
                .version(HttpClient.Version.HTTP_1_1)
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofInputStream()).body();
    }

    private CountDownLatch latch() {
        CountDownLatch latch = new CountDownLatch(1);
        releaseOnTeardown.add(latch);
        return latch;
    }

    private boolean readerReleasedTheExecutor() {
        try {
            Future<?> marker = executor.submit(() -> {
            });
            marker.get(AWAIT.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (ExecutionException e) {
            throw new IllegalStateException("the marker task failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void awaitRelease(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean awaitTrue(BooleanSupplier condition) {
        return awaitTrue(AWAIT, condition);
    }

    private static boolean awaitTrue(Duration bound, BooleanSupplier condition) {
        long deadlineNanos = System.nanoTime() + bound.toNanos();
        while (System.nanoTime() - deadlineNanos < 0) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    /** Subscriber that records every callback, optionally throwing from onNext and pacing demand. */
    private static final class RecordingSubscriber implements Flow.Subscriber<byte[]> {

        private final RuntimeException throwFromOnNext;
        private final long requestOnSubscribe;
        private final long requestPerChunk;

        final CountDownLatch subscribed = new CountDownLatch(1);
        final List<byte[]> chunks = new CopyOnWriteArrayList<>();
        final List<Thread> threads = new CopyOnWriteArrayList<>();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final AtomicInteger completed = new AtomicInteger();
        volatile Flow.Subscription subscription;

        RecordingSubscriber(RuntimeException throwFromOnNext, long requestOnSubscribe, long requestPerChunk) {
            this.throwFromOnNext = throwFromOnNext;
            this.requestOnSubscribe = requestOnSubscribe;
            this.requestPerChunk = requestPerChunk;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscribed.countDown();
            if (requestOnSubscribe > 0) {
                subscription.request(requestOnSubscribe);
            }
        }

        @Override
        public void onNext(byte[] chunk) {
            threads.add(Thread.currentThread());
            chunks.add(chunk);
            if (requestPerChunk > 0) {
                subscription.request(requestPerChunk);
            }
            if (throwFromOnNext != null) {
                throw throwFromOnNext;
            }
        }

        @Override
        public void onError(Throwable error) {
            threads.add(Thread.currentThread());
            errors.add(error);
        }

        @Override
        public void onComplete() {
            threads.add(Thread.currentThread());
            completed.incrementAndGet();
        }
    }
}

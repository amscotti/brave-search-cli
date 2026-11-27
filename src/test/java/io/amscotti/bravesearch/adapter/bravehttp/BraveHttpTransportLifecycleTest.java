package io.amscotti.bravesearch.adapter.bravehttp;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.application.exchange.BraveApiRequest;
import io.amscotti.bravesearch.application.exchange.BraveHttpResponse;
import io.amscotti.bravesearch.application.exchange.ResponseLimits;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The close/send lifecycle of the transport: closing while exchanges are in flight ends them
 * as cancelled and returns at once, genuine caller interrupts survive body-read failures, and
 * every send after close reports the fixed internal failure.
 */
final class BraveHttpTransportLifecycleTest {

    private static final Duration GENEROUS_HEADERS_TIMEOUT = Duration.ofSeconds(10);

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void closeDuringAnExchangeEndsItAsCancelledAndReturnsAtOnce() throws Exception {
        // a stalled exchange must not hold a closing library open until its budget runs out:
        // the close ends the exchange and lands at once
        GatedClient gated = new GatedClient();
        BraveHttpTransport transport = new BraveHttpTransport(
                gated, GENEROUS_HEADERS_TIMEOUT, ResponseLimits.production(), Clock.systemUTC());
        try {
            AtomicReference<Outcome<BraveHttpResponse>> outcome = new AtomicReference<>();
            Thread sending = Thread.ofPlatform().unstarted(() -> {
                try {
                    outcome.set(transport.send(request()));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            sending.start();
            assertTrue(gated.entered.await(10, TimeUnit.SECONDS), "the gated client holds the exchange");

            long startedAt = System.nanoTime();
            transport.close();
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            sending.join(10_000);

            assertTrue(elapsedMs < 5_000, "the close does not wait out the exchange: " + elapsedMs + " ms");
            assertFalse(sending.isAlive(), "the in-flight exchange is ended by the close");
            Outcome.Failure<BraveHttpResponse> ended = (Outcome.Failure<BraveHttpResponse>) outcome.get();
            assertEquals(FailureKind.TRANSPORT, ended.kind(), "an exchange the close took away is a cancelled one");
            assertEquals("upstream exchange cancelled", ended.diagnostic());
            assertTrue(gated.clientClosed.get(), "the owned client is closed by the close");

            // after close, a further exchange on the same transport learns the closed client without dispatching
            Outcome<BraveHttpResponse> after = transport.send(request());
            Outcome.Failure<BraveHttpResponse> failure = (Outcome.Failure<BraveHttpResponse>) after;
            assertEquals(FailureKind.INTERNAL, failure.kind());
            assertEquals("the HTTP client has been closed", failure.diagnostic());
            assertFalse(
                    failure.diagnostic().contains("sentinel"),
                    "the closed diagnostic carries no credential material");
        } finally {
            gated.released.countDown();
            transport.close();
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void closeStillCancelsRemainingInFlightExchangeWhenSharingACancellationContext() throws Exception {
        // two concurrent exchanges share one injected CancellationContext; the first completes
        // before close(), which must not remove the second exchange from close's cancellation reach
        CancellationContext shared = new CancellationContext();
        SecondCallGatedClient client = new SecondCallGatedClient();
        try (BraveHttpTransport transport = new BraveHttpTransport(
                client, GENEROUS_HEADERS_TIMEOUT, ResponseLimits.production(), Clock.systemUTC(), shared)) {
            CountDownLatch firstCanFinish = new CountDownLatch(1);
            client.firstGate = firstCanFinish;

            Thread first = Thread.ofPlatform().start(() -> {
                try {
                    transport.send(request());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(client.firstEntered.await(5, TimeUnit.SECONDS));

            AtomicReference<Outcome<BraveHttpResponse>> secondOutcome = new AtomicReference<>();
            Thread second = Thread.ofPlatform().start(() -> {
                try {
                    secondOutcome.set(transport.send(request()));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(client.secondEntered.await(5, TimeUnit.SECONDS));

            // finish the first exchange while the second remains blocked in flight
            firstCanFinish.countDown();
            first.join(5_000);
            assertFalse(first.isAlive());

            long startedAt = System.nanoTime();
            transport.close();
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            second.join(5_000);

            assertTrue(elapsedMs < 5_000, "close must cancel the surviving exchange promptly: " + elapsedMs + " ms");
            assertFalse(second.isAlive(), "the surviving exchange was ended by close");
            Outcome.Failure<BraveHttpResponse> failure = (Outcome.Failure<BraveHttpResponse>) secondOutcome.get();
            assertEquals(FailureKind.TRANSPORT, failure.kind());
            assertEquals("upstream exchange cancelled", failure.diagnostic());
        } finally {
            client.secondReleased.countDown();
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void anExternalCallerInterruptDuringBodyReadSurvivesTransportReturn() throws Exception {
        // when the JDK body InputStream is interrupted by an external caller, it restores the
        // interrupt flag and throws IOException; the transport's finally must not wipe that flag
        CountDownLatch readingBody = new CountDownLatch(1);
        InterruptibleBodyClient client = new InterruptibleBodyClient(readingBody);
        try (BraveHttpTransport transport = new BraveHttpTransport(
                client, GENEROUS_HEADERS_TIMEOUT, ResponseLimits.production(), Clock.systemUTC())) {
            AtomicReference<Outcome<BraveHttpResponse>> outcome = new AtomicReference<>();
            AtomicBoolean interruptPreserved = new AtomicBoolean();
            Thread caller = Thread.ofPlatform().start(() -> {
                try {
                    outcome.set(transport.send(request()));
                    interruptPreserved.set(Thread.currentThread().isInterrupted());
                } catch (InterruptedException unexpected) {
                    interruptPreserved.set(true);
                }
            });
            assertTrue(readingBody.await(5, TimeUnit.SECONDS), "the exchange reached body reading");
            caller.interrupt();
            caller.join(5_000);

            assertFalse(caller.isAlive());
            Outcome.Failure<BraveHttpResponse> failure = (Outcome.Failure<BraveHttpResponse>) outcome.get();
            assertEquals(FailureKind.TRANSPORT, failure.kind());
            assertTrue(interruptPreserved.get(), "genuine external caller interrupt flag must not be cleared");
        }
    }

    private static BraveApiRequest request() {
        return BraveApiRequest.get(URI.create("https://api.search.brave.com/web/search?q=artemis"))
                .token(Credential.of("sentinel-token".getBytes(UTF_8)))
                .build();
    }

    /** An HTTP client whose send blocks until the test releases it, recording its own close. */
    private static final class GatedClient extends HttpClient {

        final CountDownLatch entered = new CountDownLatch(1);

        final CountDownLatch released = new CountDownLatch(1);

        final AtomicBoolean clientClosed = new AtomicBoolean(false);

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException {
            entered.countDown();
            try {
                if (!released.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("the gated exchange was never released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("the gated exchange was interrupted");
            }
            if (clientClosed.get()) {
                throw new IOException("the JDK client is closed");
            }
            return (HttpResponse<T>) new CannedResponse(request);
        }

        @Override
        public void close() {
            clientClosed.set(true);
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            try {
                return SSLContext.getDefault();
            } catch (Exception missing) {
                throw new AssertionError(missing);
            }
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<java.util.concurrent.Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("gated send is synchronous"));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("gated send is synchronous"));
        }
    }

    /** An empty 200 response: no body means no content type is required of it. */
    private record CannedResponse(HttpRequest request) implements HttpResponse<InputStream> {

        @Override
        public int statusCode() {
            return 200;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public Optional<HttpResponse<InputStream>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (name, value) -> true);
        }

        @Override
        public InputStream body() {
            return InputStream.nullInputStream();
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    /** Lets the first send complete on its gate while the second send stays parked until interrupted. */
    private static final class SecondCallGatedClient extends HttpClient {

        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch firstEntered = new CountDownLatch(1);
        final CountDownLatch secondEntered = new CountDownLatch(1);
        final CountDownLatch secondReleased = new CountDownLatch(1);
        volatile CountDownLatch firstGate = new CountDownLatch(0);

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException, InterruptedException {
            int call = calls.incrementAndGet();
            if (call == 1) {
                firstEntered.countDown();
                firstGate.await(10, TimeUnit.SECONDS);
                return (HttpResponse<T>) new CannedResponse(request);
            }
            secondEntered.countDown();
            if (!secondReleased.await(10, TimeUnit.SECONDS)) {
                throw new IOException("the second gated exchange was never released");
            }
            return (HttpResponse<T>) new CannedResponse(request);
        }

        @Override
        public void close() {}

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            try {
                return SSLContext.getDefault();
            } catch (Exception missing) {
                throw new AssertionError(missing);
            }
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<java.util.concurrent.Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
    }

    /** Returns headers immediately and an InputStream that mirrors HttpResponseInputStream on interrupt. */
    private static final class InterruptibleBodyClient extends HttpClient {

        private final CountDownLatch readingBody;

        InterruptibleBodyClient(CountDownLatch readingBody) {
            this.readingBody = readingBody;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return (HttpResponse<T>) new HttpResponse<InputStream>() {
                @Override
                public int statusCode() {
                    return 200;
                }

                @Override
                public HttpRequest request() {
                    return request;
                }

                @Override
                public Optional<HttpResponse<InputStream>> previousResponse() {
                    return Optional.empty();
                }

                @Override
                public HttpHeaders headers() {
                    return HttpHeaders.of(Map.of("Content-Type", java.util.List.of("application/json")), (n, v) -> true);
                }

                @Override
                public InputStream body() {
                    return new InputStream() {
                        @Override
                        public int read() throws IOException {
                            return read(new byte[1], 0, 1);
                        }

                        @Override
                        public int read(byte[] target, int offset, int length) throws IOException {
                            readingBody.countDown();
                            try {
                                new CountDownLatch(1).await();
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IOException("interrupted while reading response body");
                            }
                            return -1;
                        }
                    };
                }

                @Override
                public Optional<javax.net.ssl.SSLSession> sslSession() {
                    return Optional.empty();
                }

                @Override
                public URI uri() {
                    return request.uri();
                }

                @Override
                public Version version() {
                    return Version.HTTP_1_1;
                }
            };
        }

        @Override
        public void close() {}

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            try {
                return SSLContext.getDefault();
            } catch (Exception missing) {
                throw new AssertionError(missing);
            }
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<java.util.concurrent.Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
    }
}

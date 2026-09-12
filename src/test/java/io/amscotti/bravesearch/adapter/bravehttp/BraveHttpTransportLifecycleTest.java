package io.amscotti.bravesearch.adapter.bravehttp;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.application.exchange.BraveApiRequest;
import io.amscotti.bravesearch.application.exchange.BraveHttpResponse;
import io.amscotti.bravesearch.application.exchange.ResponseLimits;
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
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The close/send lifecycle of the transport: closing while an exchange is in flight waits
 * for its completion instead of severing it, and every send after the close reports the
 * fixed internal failure.
 */
final class BraveHttpTransportLifecycleTest {

    private static final Duration GENEROUS_HEADERS_TIMEOUT = Duration.ofSeconds(10);

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void closeDuringAnExchangeWaitsForItsCompletion() throws Exception {
        GatedClient gated = new GatedClient();
        try (BraveHttpTransport transport = new BraveHttpTransport(
                gated, GENEROUS_HEADERS_TIMEOUT, ResponseLimits.production(), Clock.systemUTC())) {
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

            AtomicBoolean closeReturned = new AtomicBoolean(false);
            Thread closing = Thread.ofPlatform().unstarted(() -> {
                transport.close();
                closeReturned.set(true);
            });
            closing.start();
            closing.join(300);
            assertTrue(closing.isAlive(), "closing waits for the in-flight exchange");

            gated.released.countDown();
            sending.join(10_000);
            closing.join(10_000);
            assertTrue(closeReturned.get(), "the close lands after the exchange completes");
            assertTrue(gated.clientClosed.get(), "the owned client is closed exactly once the exchange is done");
            assertInstanceOf(
                    Outcome.Success.class, outcome.get(), "the in-flight exchange is never severed by the close");

            Outcome<BraveHttpResponse> after = transport.send(request());
            assertInstanceOf(Outcome.Failure.class, after, "sends after the close stay closed");
            Outcome.Failure<BraveHttpResponse> failure = (Outcome.Failure<BraveHttpResponse>) after;
            assertEquals(FailureKind.INTERNAL, failure.kind());
            assertEquals("the HTTP client has been closed", failure.diagnostic());
            assertFalse(
                    failure.diagnostic().contains("sentinel"),
                    "the closed diagnostic carries no credential material");
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
}

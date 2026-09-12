package io.amscotti.bravesearch.adapter.bravehttp;

import static java.time.Duration.ofMillis;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The client every Brave exchange travels on: redirect-refusing, connect-bounded, HTTP/2-capable. */
final class BraveHttpClientFactoryTest {

    @Test
    void refusesRedirectsAtTheClientLevel() {
        assertEquals(HttpClient.Redirect.NEVER, new BraveHttpClientFactory(ofMillis(500)).newClient().followRedirects());
    }

    @Test
    void injectsTheConnectTimeout() {
        assertEquals(
                java.util.Optional.of(Duration.ofMillis(1500)),
                new BraveHttpClientFactory(ofMillis(1500)).newClient().connectTimeout());
    }

    @Test
    void keepsHttp2AllowedForAlpnNegotiation() {
        assertEquals(HttpClient.Version.HTTP_2, new BraveHttpClientFactory(ofMillis(500)).newClient().version());
    }

    @Test
    void defaultsToVirtualThreadExecutor() throws Exception {
        HttpClient client = new BraveHttpClientFactory(ofMillis(500)).newClient();
        org.junit.jupiter.api.Assertions.assertTrue(client.executor().isPresent(), "default client configures an executor");
        java.util.concurrent.Executor executor = client.executor().get();
        java.util.concurrent.atomic.AtomicBoolean isVirtual = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        executor.execute(() -> {
            isVirtual.set(Thread.currentThread().isVirtual());
            latch.countDown();
        });
        org.junit.jupiter.api.Assertions.assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS), "task executed");
        org.junit.jupiter.api.Assertions.assertTrue(isVirtual.get(), "default executor runs tasks on virtual threads");
    }

    @Test
    void rejectsUnusableConnectTimeouts() {
        assertThrows(NullPointerException.class, () -> new BraveHttpClientFactory(null));
        assertThrows(IllegalArgumentException.class, () -> new BraveHttpClientFactory(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new BraveHttpClientFactory(ofMillis(-1)));
    }
}

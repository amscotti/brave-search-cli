package io.amscotti.bravesearch.adapter.bravehttp;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.application.exchange.HttpProxy;
import io.amscotti.bravesearch.testsupport.ScriptedSseServer;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The proxy seam of the client factory: a client built with a proxy routes an unlisted origin
 * to it — for an https origin that is a CONNECT tunnel, so the origin's credentials stay
 * inside TLS exactly as they would on a direct connection — while a bypassed or loopback
 * origin is reached directly.
 */
final class BraveHttpClientFactoryProxyTest {

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aClientWithAProxySendsAnUnlistedOriginToTheProxyAndNowhereElse() throws Exception {
        // the stand-in records what the client sends it and answers with a failure; the
        // origin named in the request is never contacted, because the client speaks only to
        // the proxy — the selector test below shows an https origin takes the same route
        try (ScriptedSseServer standIn = ScriptedSseServer.builder()
                .statusCode(502)
                .writeBytes("no forwarding from this stand-in".getBytes(UTF_8))
                .start()) {
            HttpProxy configured = new HttpProxy("127.0.0.1", standIn.baseUrl().getPort(), List.of());
            ProxySelector selector = BraveHttpClientFactory.proxySelector(configured);

            List<Proxy> routes = selector.select(URI.create("https://api.search.brave.com/web/search?q=q"));
            assertEquals(1, routes.size(), "one route: the proxy");
            assertEquals(Proxy.Type.HTTP, routes.getFirst().type());
            assertEquals(new InetSocketAddress("127.0.0.1", standIn.baseUrl().getPort()), routes.getFirst().address());

            java.net.http.HttpClient client =
                    new BraveHttpClientFactory(Duration.ofSeconds(5)).newClient(Optional.of(configured));
            java.net.http.HttpRequest outgoing = java.net.http.HttpRequest.newBuilder(
                            URI.create("http://api.search.brave.com/web/search?q=q"))
                    .timeout(Duration.ofSeconds(10))
                    .build();
            java.net.http.HttpResponse<Void> answered =
                    client.send(outgoing, java.net.http.HttpResponse.BodyHandlers.discarding());

            assertEquals(502, answered.statusCode(), "the stand-in's answer is what the exchange saw");
            assertEquals(1, standIn.requests().size(), "the exchange arrived at the proxy stand-in");
            assertEquals("GET", standIn.requests().getFirst().method());
            assertTrue(
                    standIn.requests().getFirst().path().contains("api.search.brave.com"),
                    "a plain-http request reaches the proxy in its absolute form: " + standIn.requests().getFirst().path());
        }
    }

    @Test
    void aBypassedOrLoopbackOriginIsReachedDirectly() {
        HttpProxy configured = new HttpProxy("127.0.0.1", 3128, List.of("example.com"));
        ProxySelector selector = BraveHttpClientFactory.proxySelector(configured);

        assertEquals(List.of(Proxy.NO_PROXY), selector.select(URI.create("http://127.0.0.1:9/x")), "loopback is never proxied");
        assertEquals(List.of(Proxy.NO_PROXY), selector.select(URI.create("https://www.example.com/x")), "a bypass entry");
        assertEquals(1, selector.select(URI.create("https://api.search.brave.com/x")).size());
        assertTrue(selector.select(URI.create("https://api.search.brave.com/x")).getFirst().address() instanceof InetSocketAddress);
    }
}

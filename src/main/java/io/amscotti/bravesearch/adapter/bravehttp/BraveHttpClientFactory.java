package io.amscotti.bravesearch.adapter.bravehttp;

import io.amscotti.bravesearch.application.exchange.HttpProxy;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Builds the {@link HttpClient} every Brave exchange travels on.
 *
 * <p>Redirects are refused at the client level, so a 3xx surfaces as a response the caller
 * classifies as an upstream failure instead of being silently followed — following a
 * {@code Location} would otherwise move a token-bearing request to an arbitrary origin. The
 * connect timeout is injected by the composition. HTTP/2 stays allowed through normal ALPN
 * negotiation with an HTTP/1.1 fallback; websockets are never configured because the API
 * surface is request/response plus server-sent events. A client built without an injected
 * executor rides a virtual thread per task; a composition may instead hand the client the
 * executor its internal tasks ride — the library composition always does, routing its
 * caller-visible executor policy through this seam, and the executor's lifecycle stays owned
 * by whoever supplied it.
 */
public final class BraveHttpClientFactory {

    private final Duration connectTimeout;

    private final Executor executor;

    /**
     * @throws NullPointerException when {@code connectTimeout} is null
     * @throws IllegalArgumentException when {@code connectTimeout} is zero or negative
     */
    public BraveHttpClientFactory(Duration connectTimeout) {
        requireUsable(connectTimeout);
        this.connectTimeout = connectTimeout;
        this.executor = null;
    }

    /**
     * The executor-aware wiring for compositions that route the client's internal tasks onto
     * a chosen executor.
     *
     * @throws NullPointerException when {@code connectTimeout} or {@code executor} is null
     * @throws IllegalArgumentException when {@code connectTimeout} is zero or negative
     */
    public BraveHttpClientFactory(Duration connectTimeout, Executor executor) {
        requireUsable(connectTimeout);
        this.connectTimeout = connectTimeout;
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** A fresh redirect-refusing client; callers own its lifecycle. */
    public HttpClient newClient() {
        return newClient(Optional.empty());
    }

    /**
     * A fresh redirect-refusing client whose exchanges travel through {@code proxy} when it
     * says so for their origin; callers own its lifecycle.
     */
    public HttpClient newClient(Optional<HttpProxy> proxy) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connectTimeout)
                .executor(executor != null ? executor : Executors.newVirtualThreadPerTaskExecutor());
        proxy.ifPresent(configured -> builder.proxy(proxySelector(configured)));
        return builder.build();
    }

    /**
     * The route decision of one proxy as a selector: the proxy for every origin it carries,
     * a direct connection for a bypassed or loopback one.
     */
    public static ProxySelector proxySelector(HttpProxy proxy) {
        Objects.requireNonNull(proxy, "proxy");
        return new ProxySelector() {

            @Override
            public List<Proxy> select(URI target) {
                if (!proxy.proxies(target.getHost())) {
                    return List.of(Proxy.NO_PROXY);
                }
                return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxy.host(), proxy.port())));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress proxyAddress, IOException failure) {
                // the exchange reports the failure itself; the selector has nothing to add
            }
        };
    }

    private static void requireUsable(Duration connectTimeout) {
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        if (connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("connect timeout must be positive");
        }
    }
}

package io.amscotti.bravesearch.adapter.bravehttp;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;
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
 * surface is request/response plus server-sent events. Tasks default to a virtual thread per
 * task executor. A composition may additionally hand the client the executor its internal tasks
 * ride — the library composition routes its caller-visible executor policy through this seam,
 * and the executor's lifecycle stays owned by whoever supplied it.
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
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connectTimeout)
                .executor(executor != null ? executor : Executors.newVirtualThreadPerTaskExecutor());
        return builder.build();
    }

    private static void requireUsable(Duration connectTimeout) {
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        if (connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("connect timeout must be positive");
        }
    }
}

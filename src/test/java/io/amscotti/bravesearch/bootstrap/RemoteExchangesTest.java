package io.amscotti.bravesearch.bootstrap;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.amscotti.bravesearch.adapter.bravehttp.BraveHttpTransport;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.SuggestEndpoint;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.BraveApiRequest;
import io.amscotti.bravesearch.application.exchange.BraveHttpResponse;
import io.amscotti.bravesearch.application.port.out.RemoteDispatch;
import io.amscotti.bravesearch.application.port.out.RemoteExchange;
import io.amscotti.bravesearch.application.port.out.RemoteWalk;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.config.LocalConfigException;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.request.SuggestRequest;
import io.amscotti.bravesearch.domain.result.SuggestSearchResult;
import io.amscotti.bravesearch.testsupport.ScriptedSseServer;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Lifecycle contract of the process exchanges: a run owns its transport, and only its run. A
 * single dispatch opens one transport and closes it with the exchange; a sequential walk opens
 * one transport for all of its requests, so the connection to the peer survives from the first
 * request to the last, and releases it when the walk closes.
 */
final class RemoteExchangesTest {

    private static final Duration BUDGET = Duration.ofSeconds(10);

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aSingleDispatchOpensOneTransportAndOneConnectionAndClosesTheTransportWithTheExchange()
            throws Exception {
        try (ScriptedSseServer server = serving("{}").start()) {
            AtomicInteger opened = new AtomicInteger();
            RemoteExchange<SuggestRequest, SuggestSearchResult> exchange = exchange(opened);

            Outcome<SuggestSearchResult> outcome = exchange.dispatch(dispatch(server, "single"));

            assertEquals(200, valueOf(outcome).httpStatus());
            assertEquals(1, opened.get(), "one dispatch opens exactly one transport");
            assertEquals(1, server.connectionCount());
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void separateDispatchesNeverShareATransportOrAConnection() throws Exception {
        // the control for the walk below: without a walk, every dispatch is its own lifecycle
        try (ScriptedSseServer server =
                ScriptedSseServer.startSequence(serving("{}"), serving("{}"))) {
            AtomicInteger opened = new AtomicInteger();
            RemoteExchange<SuggestRequest, SuggestSearchResult> exchange = exchange(opened);

            exchange.dispatch(dispatch(server, "first"));
            exchange.dispatch(dispatch(server, "second"));

            assertEquals(2, opened.get());
            assertEquals(2, server.connectionCount(), "two dispatches arrive over two connections");
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aWalkRidesOneTransportAndOneConnectionAcrossEveryRequestAndReleasesThemWhenItCloses()
            throws Exception {
        try (ScriptedSseServer server = ScriptedSseServer.startSequence(
                serving("{}"), serving("{}"), serving("{\"suggest\":[]}"))) {
            AtomicInteger opened = new AtomicInteger();
            RemoteExchange<SuggestRequest, SuggestSearchResult> exchange = exchange(opened);

            RemoteWalk<SuggestRequest, SuggestSearchResult> walk = exchange.openWalk(dispatch(server, "walk"));
            try {
                for (int request = 1; request <= 3; request++) {
                    final int number = request;
                    Outcome<SuggestSearchResult> outcome =
                            walk.dispatch(SuggestRequest.builder("request-" + number).build());
                    assertEquals(200, valueOf(outcome).httpStatus(), () -> "request " + number);
                }

                assertEquals(3, server.requests().size());
                assertEquals(1, opened.get(), "one transport serves the whole walk");
                assertEquals(1, server.connectionCount(), "every request keeps the connection to the peer");

                walk.close();

                // the closed walk releases its transport: a late request learns the fixed
                // closed-client verdict instead of reaching the peer
                Outcome.Failure<SuggestSearchResult> closed = failureOf(walk.dispatch(SuggestRequest.builder("late").build()));
                assertEquals(FailureKind.INTERNAL, closed.kind());
                assertEquals("the HTTP client has been closed", closed.diagnostic());
                assertEquals(3, server.requests().size(), "the late request never reached the peer");
            } finally {
                walk.close();
            }
        }
    }

    @Test
    void theDefaultWalkLeavesEveryDispatchASelfContainedExchangeOfTheScopedInvocation() {
        List<String> dispatched = new java.util.ArrayList<>();
        RemoteExchange<String, Integer> selfContained = invocation -> {
            dispatched.add(invocation.request());
            return new Outcome.Success<>(invocation.request().length());
        };

        RemoteWalk<String, Integer> walk = selfContained.openWalk(scopeOf("scope"));

        assertEquals(1, valueOf(walk.dispatch("a")));
        assertEquals(3, valueOf(walk.dispatch("abc")));
        walk.close();

        assertEquals(List.of("a", "abc"), dispatched, "each dispatch carries its own request");
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aConfiguredProxyNeverComesBetweenARunAndItsLoopbackOrigin() throws Exception {
        try (ScriptedSseServer origin = serving("{}").start();
                ScriptedSseServer proxyStandIn = serving("{}").start()) {
            RemoteExchange<SuggestRequest, SuggestSearchResult> proxied = proxiedExchange(proxyStandIn);

            Outcome<SuggestSearchResult> outcome = proxied.dispatch(dispatch(origin, "direct"));

            assertEquals(200, valueOf(outcome).httpStatus());
            assertEquals(1, origin.requests().size(), "the loopback origin is reached directly");
            assertEquals(0, proxyStandIn.requests().size(), "the configured proxy never sees a loopback run");
        }
    }

    @Test
    void anUnusableProxyValueFailsTheExchangeAsAConfigurationErrorBeforeAnyConnection() {
        RemoteExchange<SuggestRequest, SuggestSearchResult> misconfigured = RemoteExchanges.blocking(
                SuggestEndpoint::assemble,
                SuggestSearchResult::new,
                name -> name.equals("HTTPS_PROXY") ? "socks5://proxy.invalid:1080" : null);
        RemoteDispatch<SuggestRequest> toProduction = new RemoteDispatch<>(
                SuggestRequest.builder("q").build(),
                BraveApiOrigin.production(),
                Credential.of("sentinel".getBytes(UTF_8)),
                BUDGET,
                BUDGET,
                null,
                new CancellationContext());

        LocalConfigException failure =
                assertThrows(LocalConfigException.class, () -> misconfigured.dispatch(toProduction));

        assertEquals(LocalConfigException.Reason.MISCONFIGURED_PROXY, failure.reason());
        assertFalse(failure.getMessage().contains("proxy.invalid"), "the failure names the rule, never the address");
    }

    @Test
    void openWalkClosesTheTransportWhenGatewayConstructionFails() throws Exception {
        AtomicReference<BraveHttpTransport> opened = new AtomicReference<>();
        RemoteExchange<SuggestRequest, SuggestSearchResult> exchange = RemoteExchanges.blocking(
                SuggestEndpoint::assemble,
                SuggestSearchResult::new,
                (connectTimeout, totalTimeout, cancellation) -> {
                    // open a real transport with a positive header timeout so construction fails
                    // inside BraveHttpBlockingGateway when it rejects the zero total timeout
                    BraveHttpTransport transport =
                            RemoteExchanges.DIRECT_TRANSPORTS.open(connectTimeout, BUDGET, cancellation);
                    opened.set(transport);
                    return transport;
                });
        RemoteDispatch<SuggestRequest> invalidTotalBudget = new RemoteDispatch<>(
                SuggestRequest.builder("q").build(),
                BraveApiOrigin.production(),
                Credential.of("sentinel".getBytes(UTF_8)),
                Duration.ZERO,
                BUDGET,
                null,
                new CancellationContext());

        assertThrows(IllegalArgumentException.class, () -> exchange.openWalk(invalidTotalBudget));

        BraveApiRequest probe = SuggestEndpoint.assemble(
                SuggestRequest.builder("after-close").build(),
                BraveApiOrigin.production(),
                Credential.of("sentinel".getBytes(UTF_8)),
                null);
        Outcome.Failure<BraveHttpResponse> closed = failureOf(opened.get().send(probe, BUDGET));
        assertEquals(FailureKind.INTERNAL, closed.kind());
        assertEquals(
                "the HTTP client has been closed",
                closed.diagnostic(),
                "a transport opened before gateway construction fails must be closed immediately");
    }

    /** The process exchange over an environment that routes everything through the stand-in. */
    private static RemoteExchange<SuggestRequest, SuggestSearchResult> proxiedExchange(ScriptedSseServer proxyStandIn) {
        return RemoteExchanges.blocking(
                SuggestEndpoint::assemble,
                SuggestSearchResult::new,
                name -> name.equals("HTTPS_PROXY") ? "http://127.0.0.1:" + proxyStandIn.baseUrl().getPort() : null);
    }

    private static RemoteExchange<SuggestRequest, SuggestSearchResult> exchange(AtomicInteger opened) {
        return RemoteExchanges.blocking(
                SuggestEndpoint::assemble,
                SuggestSearchResult::new,
                (connectTimeout, totalTimeout, cancellation) -> {
                    opened.incrementAndGet();
                    return RemoteExchanges.DIRECT_TRANSPORTS.open(connectTimeout, totalTimeout, cancellation);
                });
    }

    private static ScriptedSseServer.Builder serving(String body) {
        return ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .writeBytes(body.getBytes(UTF_8));
    }

    private static RemoteDispatch<SuggestRequest> dispatch(ScriptedSseServer server, String query) {
        return new RemoteDispatch<>(
                SuggestRequest.builder(query).build(),
                BraveApiOrigin.fromOverride(
                        server.baseUrl().toString(), host -> { throw new AssertionError("literals are never resolved"); }),
                Credential.of(("sentinel-" + UUID.randomUUID()).getBytes(UTF_8)),
                BUDGET,
                Duration.ofSeconds(5),
                null,
                new CancellationContext());
    }

    private static RemoteDispatch<String> scopeOf(String request) {
        return new RemoteDispatch<>(
                request,
                BraveApiOrigin.production(),
                Credential.of("scope-sentinel".getBytes(UTF_8)),
                BUDGET,
                BUDGET,
                null,
                new CancellationContext());
    }

    private static <T> T valueOf(Outcome<T> outcome) {
        return switch (outcome) {
            case Outcome.Success<T> success -> success.value();
            case Outcome.Failure<T> failure ->
                    throw new AssertionError("expected a success, saw " + failure.kind() + ": " + failure.diagnostic());
        };
    }

    private static <T> Outcome.Failure<T> failureOf(Outcome<T> outcome) {
        return switch (outcome) {
            case Outcome.Success<T> ignored -> throw new AssertionError("expected a failure, saw a success");
            case Outcome.Failure<T> failure -> failure;
        };
    }
}

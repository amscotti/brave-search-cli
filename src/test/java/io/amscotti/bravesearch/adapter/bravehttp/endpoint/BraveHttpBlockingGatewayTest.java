package io.amscotti.bravesearch.adapter.bravehttp.endpoint;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.bravehttp.BraveHttpClientFactory;
import io.amscotti.bravesearch.adapter.bravehttp.BraveHttpTransport;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.ResponseLimits;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.CredentialResolutionException;
import io.amscotti.bravesearch.application.port.out.ResolvedCredential;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.request.SuggestRequest;
import io.amscotti.bravesearch.domain.result.SuggestSearchResult;
import io.amscotti.bravesearch.testsupport.ScriptedSseServer;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Loopback contract of the one blocking gateway every endpoint shares, over the suggest
 * endpoint's wire translation: the assembled request reaches the wire with its exact request
 * line while the loopback token rides the subscription header and the stored credential stays
 * off the wire; success projects the observed exchange member for member; an unauthorized
 * exchange classifies as authentication and a rate-limited one keeps its windows, both without
 * leaking the token; an unresolvable stored credential is a local-configuration failure; the
 * injected total deadline bounds a stalled body; an interrupted exchange restores the
 * interrupt flag; and a closed gateway reports the fixed internal failure.
 */
final class BraveHttpBlockingGatewayTest {

    private static final Duration GENEROUS_HEADERS_TIMEOUT = Duration.ofSeconds(10);

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void theAssembledRequestReachesTheWireExactlyAndTheResponseFlowsThroughMemberForMember()
            throws Exception {
        String loopbackSentinel = "loopback-sentinel-" + UUID.randomUUID();
        byte[] body = ("{\"suggest\":[{\"value\":\"artemis\"}]}").getBytes(UTF_8);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .header("X-Request-Id", "req-opaque-17")
                .header("Api-Version", "2026-08-01")
                .header("X-RateLimit-Limit", "1")
                .header("X-RateLimit-Policy", "request")
                .header("X-RateLimit-Remaining", "0")
                .header("X-RateLimit-Reset", "7")
                .writeBytes(body)
                .start()) {

            Outcome<SuggestSearchResult> outcome;
            try (BraveHttpBlockingGateway<SuggestRequest, SuggestSearchResult> gateway = libraryGateway(
                    server, new NeverConsulted(), CredentialProvider.of(credential(loopbackSentinel)))) {
                outcome = gateway.exchange(SuggestRequest.builder("artemis").build());
            }

            SuggestSearchResult result = valueOf(outcome);
            assertEquals(200, result.httpStatus());
            assertArrayEquals(body, result.body().toByteArray(), "the lossless body rides the result");
            assertEquals("req-opaque-17", result.requestId());
            assertEquals("2026-08-01", result.apiVersion());
            assertEquals(1, result.rateLimits().windows().size());
            assertEquals("request", result.rateLimits().windows().getFirst().policy());

            ScriptedSseServer.RecordedRequest recorded = server.requests().getFirst();
            assertEquals("GET", recorded.method());
            assertEquals("/suggest/search?q=artemis", recorded.path());
            assertEquals(
                    loopbackSentinel,
                    recorded.firstHeader("X-Subscription-Token").orElseThrow(),
                    "a loopback exchange draws its token from the loopback provider, never the stored credential");
            assertEquals("application/json", recorded.firstHeader("Accept").orElseThrow());
            assertNull(
                    recorded.firstHeader("Api-Version").orElse(null),
                    "an unpinned invocation sends no Api-Version header");
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void eachExchangeDrawsItsCredentialThroughTheProviderAfresh() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        CredentialProvider rotating = new CredentialProvider() {

            @Override
            public Credential resolve() {
                return credential("rotating-" + resolutions.incrementAndGet());
            }

            @Override
            public ResolvedCredential resolveWithProvenance() {
                return new ResolvedCredential(resolve(), "test fixture", false);
            }
        };
        try (ScriptedSseServer server = ScriptedSseServer.startSequence(json(), json());
                BraveHttpBlockingGateway<SuggestRequest, SuggestSearchResult> gateway =
                        libraryGateway(server, new NeverConsulted(), rotating)) {

            gateway.exchange(SuggestRequest.builder("first").build());
            gateway.exchange(SuggestRequest.builder("second").build());

            assertEquals(2, server.requests().size());
            assertEquals(
                    "rotating-1",
                    server.requests().get(0).firstHeader("X-Subscription-Token").orElseThrow(),
                    "the first exchange carries the provider's first answer");
            assertEquals(
                    "rotating-2",
                    server.requests().get(1).firstHeader("X-Subscription-Token").orElseThrow(),
                    "the next exchange asks the provider again: a credential is resolved per exchange");
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void anUnauthorizedResponseClassifiesAsAuthenticationWithoutLeakingTheToken() throws Exception {
        String sentinel = "sentinel-" + UUID.randomUUID();
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(401)
                .header("Content-Type", "application/json")
                .writeBytes("{\"error\":{\"code\":\"Unauthorized\",\"detail\":\"recovery hint\"}}"
                        .getBytes(UTF_8))
                .start()) {

            Outcome<SuggestSearchResult> outcome = commandGateway(server, sentinel)
                    .exchange(SuggestRequest.builder("artemis").build());

            Outcome.Failure<SuggestSearchResult> failure = failureOf(outcome);
            assertEquals(FailureKind.AUTHENTICATION, failure.kind());
            assertEquals(401, failure.httpStatus(), "the machine surface learns the failing status");
            assertEquals("Unauthorized", failure.upstream().code());
            assertFalse(failure.diagnostic().contains(sentinel), "the diagnostic carries no token material");
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aRateLimitedResponseKeepsItsObservedWindows() throws Exception {
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(429)
                .header("Content-Type", "application/json")
                .header("X-RateLimit-Limit", "1,15")
                .header("X-RateLimit-Policy", "request,minute")
                .header("X-RateLimit-Remaining", "0,14")
                .header("X-RateLimit-Reset", "7,42")
                .writeBytes("{\"error\":{\"code\":\"TooManyRequests\"}}".getBytes(UTF_8))
                .start()) {

            Outcome<SuggestSearchResult> outcome = commandGateway(server, "sentinel-" + UUID.randomUUID())
                    .exchange(SuggestRequest.builder("q").build());

            Outcome.Failure<SuggestSearchResult> failure = failureOf(outcome);
            assertEquals(FailureKind.RATE_LIMITED, failure.kind());
            assertEquals(429, failure.httpStatus(), "the failing status survives the gateway mapping");
            assertEquals(2, failure.rateLimits().windows().size(), "a 429 explains itself with its windows");
            assertEquals("request", failure.rateLimits().windows().getFirst().policy());
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void anUnresolvableStoredCredentialFailsAsLocalConfiguration() {
        Outcome<SuggestSearchResult> outcome = new BraveHttpBlockingGateway<SuggestRequest, SuggestSearchResult>(
                        loopbackIndependentTransport(),
                        BraveApiOrigin.production(),
                        new MissingCredentials(),
                        new NeverConsulted(),
                        Duration.ofSeconds(2),
                        null,
                        SuggestEndpoint::assemble,
                        SuggestSearchResult::new)
                .exchange(SuggestRequest.builder("q").build());

        Outcome.Failure<SuggestSearchResult> failure = failureOf(outcome);
        assertEquals(FailureKind.LOCAL_CONFIG, failure.kind());
        assertTrue(failure.diagnostic().contains("missing credential"), failure.diagnostic());
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aClosedGatewayReportsTheFixedInternalFailureOnLaterExchanges() {
        String sentinel = "sentinel-" + UUID.randomUUID();
        BraveHttpBlockingGateway<SuggestRequest, SuggestSearchResult> gateway = new BraveHttpBlockingGateway<SuggestRequest, SuggestSearchResult>(
                loopbackIndependentTransport(),
                BraveApiOrigin.fromOverride("http://127.0.0.1:1", host -> { throw new AssertionError("literals are never resolved"); }),
                Credential.of(sentinel.getBytes(UTF_8)),
                Duration.ofSeconds(2),
                null,
                SuggestEndpoint::assemble,
                SuggestSearchResult::new);
        gateway.close();

        Outcome.Failure<SuggestSearchResult> failure = failureOf(gateway.exchange(SuggestRequest.builder("q").build()));

        assertEquals(FailureKind.INTERNAL, failure.kind());
        assertEquals("the HTTP client has been closed", failure.diagnostic());
        assertFalse(failure.diagnostic().contains(sentinel), "the diagnostic carries no token material");
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aStalledBodyBreachesTheInjectedTotalDeadlineAsATransportFailure() throws Exception {
        CountDownLatch stalled = new CountDownLatch(1);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .writeBytes("{\"suggest\":[{\"partial\"".getBytes(UTF_8))
                .flush()
                .stallUntil(stalled)
                .start()) {

            long startedAt = System.nanoTime();
            Outcome<SuggestSearchResult> outcome = libraryGateway(
                            server, new NeverConsulted(), CredentialProvider.of(credential("sentinel")), Duration.ofMillis(600))
                    .exchange(SuggestRequest.builder("q").build());
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

            Outcome.Failure<SuggestSearchResult> failure = failureOf(outcome);
            assertEquals(FailureKind.TRANSPORT, failure.kind());
            assertEquals("total request deadline exceeded", failure.diagnostic());
            assertTrue(elapsedMs < 5000, "the breach must surface within the deadline, not hang: " + elapsedMs + " ms");
        } finally {
            stalled.countDown();
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void anInterruptedExchangeRestoresTheInterruptFlagAndFailsTransport() throws Exception {
        CountDownLatch neverSendHeaders = new CountDownLatch(1);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .holdHeadersUntil(neverSendHeaders)
                .writeBytes("{}".getBytes(UTF_8))
                .start()) {

            BraveHttpBlockingGateway<SuggestRequest, SuggestSearchResult> gateway = libraryGateway(
                    server, new NeverConsulted(), CredentialProvider.of(credential("sentinel")));
            AtomicReference<Outcome<SuggestSearchResult>> outcome = new AtomicReference<>();
            AtomicBoolean interruptRestored = new AtomicBoolean();
            Thread worker = new Thread(() -> {
                outcome.set(gateway.exchange(SuggestRequest.builder("q").build()));
                interruptRestored.set(Thread.currentThread().isInterrupted());
            });
            worker.start();
            assertTrue(server.awaitFirstRequest(Duration.ofSeconds(5)), "the peer must have seen the request");
            worker.interrupt();
            worker.join(Duration.ofSeconds(5));
            gateway.close();

            Outcome.Failure<SuggestSearchResult> failure = failureOf(outcome.get());
            assertEquals(FailureKind.TRANSPORT, failure.kind());
            assertEquals("upstream exchange interrupted", failure.diagnostic());
            assertTrue(interruptRestored.get(), "the interrupt status is restored, not swallowed");
        } finally {
            neverSendHeaders.countDown();
        }
    }

    @Test
    void nullCollaboratorsAndANonPositiveBudgetAreRejected() {
        BraveHttpTransport transport = loopbackIndependentTransport();
        CredentialProvider credentials = CredentialProvider.of(credential("sentinel"));

        assertThrows(NullPointerException.class, () -> new BraveHttpBlockingGateway<SuggestRequest, SuggestSearchResult>(
                null, BraveApiOrigin.production(), credentials, credentials, Duration.ofSeconds(1), null,
                SuggestEndpoint::assemble, SuggestSearchResult::new));
        assertThrows(NullPointerException.class, () -> new BraveHttpBlockingGateway<SuggestRequest, SuggestSearchResult>(
                transport, null, credentials, credentials, Duration.ofSeconds(1), null,
                SuggestEndpoint::assemble, SuggestSearchResult::new));
        assertThrows(NullPointerException.class, () -> new BraveHttpBlockingGateway<SuggestRequest, SuggestSearchResult>(
                transport, BraveApiOrigin.production(), credentials, null, Duration.ofSeconds(1), null,
                SuggestEndpoint::assemble, SuggestSearchResult::new));
        assertThrows(NullPointerException.class, () -> new BraveHttpBlockingGateway<SuggestRequest, SuggestSearchResult>(
                transport, BraveApiOrigin.production(), null, Duration.ofSeconds(1), null,
                SuggestEndpoint::assemble, SuggestSearchResult::new));
        assertThrows(IllegalArgumentException.class, () -> new BraveHttpBlockingGateway<SuggestRequest, SuggestSearchResult>(
                transport, BraveApiOrigin.production(), credentials, credentials, Duration.ZERO, null,
                SuggestEndpoint::assemble, SuggestSearchResult::new));
        assertThrows(NullPointerException.class, () -> new BraveHttpBlockingGateway<SuggestRequest, SuggestSearchResult>(
                transport, BraveApiOrigin.production(), credentials, credentials, Duration.ofSeconds(1), null,
                null, SuggestSearchResult::new));
        assertThrows(NullPointerException.class, () -> new BraveHttpBlockingGateway<SuggestRequest, SuggestSearchResult>(
                transport, BraveApiOrigin.production(), credentials, credentials, Duration.ofSeconds(1), null,
                SuggestEndpoint::assemble, null));
    }

    private static BraveHttpBlockingGateway<SuggestRequest, SuggestSearchResult> libraryGateway(
            ScriptedSseServer server, CredentialProvider stored, CredentialProvider loopback) {
        return libraryGateway(server, stored, loopback, Duration.ofSeconds(10));
    }

    private static BraveHttpBlockingGateway<SuggestRequest, SuggestSearchResult> libraryGateway(
            ScriptedSseServer server, CredentialProvider stored, CredentialProvider loopback, Duration totalTimeout) {
        return new BraveHttpBlockingGateway<SuggestRequest, SuggestSearchResult>(
                transport(),
                loopbackOrigin(server),
                stored,
                loopback,
                totalTimeout,
                null,
                SuggestEndpoint::assemble,
                SuggestSearchResult::new);
    }

    private static BraveHttpBlockingGateway<SuggestRequest, SuggestSearchResult> commandGateway(
            ScriptedSseServer server, String token) {
        return new BraveHttpBlockingGateway<SuggestRequest, SuggestSearchResult>(
                transport(), loopbackOrigin(server), credential(token), Duration.ofSeconds(10), null,
                SuggestEndpoint::assemble, SuggestSearchResult::new);
    }

    private static BraveHttpTransport transport() {
        return new BraveHttpTransport(
                new BraveHttpClientFactory(Duration.ofSeconds(5)).newClient(),
                GENEROUS_HEADERS_TIMEOUT,
                ResponseLimits.production(),
                Clock.systemUTC());
    }

    private static BraveHttpTransport loopbackIndependentTransport() {
        return new BraveHttpTransport(
                new BraveHttpClientFactory(Duration.ofSeconds(1)).newClient(),
                Duration.ofSeconds(1),
                ResponseLimits.production(),
                Clock.systemUTC());
    }

    private static BraveApiOrigin loopbackOrigin(ScriptedSseServer server) {
        return BraveApiOrigin.fromOverride(
                server.baseUrl().toString(), host -> { throw new AssertionError("literals are never resolved"); });
    }

    private static Credential credential(String token) {
        return Credential.of(token.getBytes(UTF_8));
    }

    private static ScriptedSseServer.Builder json() {
        return ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .writeBytes("{}".getBytes(UTF_8));
    }

    private static SuggestSearchResult valueOf(Outcome<SuggestSearchResult> outcome) {
        return switch (outcome) {
            case Outcome.Success<SuggestSearchResult> success -> success.value();
            case Outcome.Failure<SuggestSearchResult> failure ->
                    throw new AssertionError("expected a success, saw " + failure.kind() + ": " + failure.diagnostic());
        };
    }

    private static Outcome.Failure<SuggestSearchResult> failureOf(Outcome<SuggestSearchResult> outcome) {
        return switch (outcome) {
            case Outcome.Success<SuggestSearchResult> ignored ->
                    throw new AssertionError("expected a failure, saw a success");
            case Outcome.Failure<SuggestSearchResult> failure -> failure;
        };
    }

    /** The stored-credential stand-in of a loopback run: consulted by nothing, ever. */
    private static final class NeverConsulted implements CredentialProvider {

        @Override
        public Credential resolve() {
            throw new AssertionError("a loopback exchange must never consult the stored credential");
        }

        @Override
        public ResolvedCredential resolveWithProvenance() {
            throw new AssertionError("a loopback exchange must never consult the stored credential");
        }
    }

    private static final class MissingCredentials implements CredentialProvider {

        @Override
        public Credential resolve() throws CredentialResolutionException {
            throw CredentialResolutionException.missing("nowhere");
        }

        @Override
        public ResolvedCredential resolveWithProvenance() throws CredentialResolutionException {
            throw CredentialResolutionException.missing("nowhere");
        }
    }
}

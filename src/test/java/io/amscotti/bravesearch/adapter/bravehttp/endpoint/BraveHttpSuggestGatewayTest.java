package io.amscotti.bravesearch.adapter.bravehttp.endpoint;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Loopback contract of the suggest gateway: the assembled request reaches the wire
 * with its exact request line while the loopback token rides the subscription header and
 * the stored credential stays off the wire; success projects the observed exchange
 * (status, lossless body, request and version identifiers, rate-limit snapshot); an
 * unauthorized exchange classifies as authentication without leaking the token; and an
 * unresolvable stored credential is a local-configuration failure.
 */
final class BraveHttpSuggestGatewayTest {

    private static final Duration GENEROUS_HEADERS_TIMEOUT = Duration.ofSeconds(10);

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void theMinimalRequestReachesTheWireExactlyAndTheResponseFlowsThrough() throws Exception {
        String loopbackSentinel = "loopback-sentinel-" + UUID.randomUUID();
        String storedSentinel = "stored-sentinel-" + UUID.randomUUID();
        byte[] body = "{}".getBytes(UTF_8);
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

            Outcome<SuggestSearchResult> outcome = gateway(
                            server,
                            new FixedCredentials(Credential.of(storedSentinel.getBytes(UTF_8))),
                            () -> Credential.of(loopbackSentinel.getBytes(UTF_8)),
                            Duration.ofSeconds(10))
                    .suggest(SuggestRequest.builder("artemis").build());

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
                    "a loopback exchange draws its token from the test supplier, never the stored credential");
            assertEquals("application/json", recorded.firstHeader("Accept").orElseThrow());
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void anUnauthorizedResponseClassifiesAsAuthenticationWithoutLeakingTheToken() throws Exception {
        String sentinel = "sentinel-" + UUID.randomUUID();
        byte[] errorBody = "{\"error\":{\"code\":\"Unauthorized\",\"detail\":\"recovery hint\"}}".getBytes(UTF_8);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(401)
                .header("Content-Type", "application/json")
                .writeBytes(errorBody)
                .start()) {

            Outcome<SuggestSearchResult> outcome = new BraveHttpSuggestGateway(
                            transport(),
                            loopbackOrigin(server),
                            Credential.of(sentinel.getBytes(UTF_8)),
                            Duration.ofSeconds(10),
                            null)
                    .suggest(SuggestRequest.builder("artemis").build());

            Outcome.Failure<SuggestSearchResult> failure = failureOf(outcome);
            assertEquals(FailureKind.AUTHENTICATION, failure.kind());
            assertEquals(401, failure.httpStatus(), "the machine surface learns the failing status");
            assertEquals("Unauthorized", failure.upstream().code());
            assertFalse(failure.diagnostic().contains(sentinel), "the diagnostic carries no token material");
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void anUnresolvableStoredCredentialFailsAsLocalConfiguration() {
        Outcome<SuggestSearchResult> outcome = new BraveHttpSuggestGateway(
                loopbackIndependentTransport(),
                BraveApiOrigin.production(),
                new MissingCredentials(),
                () -> {
                    throw new IllegalStateException("the production origin never consults the loopback supplier");
                },
                Duration.ofSeconds(2),
                null)
                .suggest(SuggestRequest.builder("artemis").build());

        Outcome.Failure<SuggestSearchResult> failure = failureOf(outcome);
        assertEquals(FailureKind.LOCAL_CONFIG, failure.kind());
        assertFalse(failure.diagnostic().isEmpty(), "the failure names the missing source");
    }

    private static BraveHttpSuggestGateway gateway(
            ScriptedSseServer server,
            CredentialProvider storedCredentials,
            Supplier<Credential> loopbackTestToken,
            Duration totalTimeout) {
        return new BraveHttpSuggestGateway(
                transport(),
                loopbackOrigin(server),
                storedCredentials,
                loopbackTestToken,
                totalTimeout,
                null);
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

    /** A stored-credential stand-in; a null credential is never consulted by a loopback origin. */
    private static final class FixedCredentials implements CredentialProvider {

        private final Credential credential;

        FixedCredentials(Credential credential) {
            this.credential = credential;
        }

        @Override
        public Credential resolve() {
            if (credential == null) {
                throw new AssertionError("a loopback exchange must never consult the stored credential");
            }
            return credential;
        }

        @Override
        public ResolvedCredential resolveWithProvenance() {
            return new ResolvedCredential(resolve(), "test fixture", false);
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

package io.amscotti.bravesearch.adapter.bravehttp.endpoint;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import io.amscotti.bravesearch.domain.request.AnswersRequest;
import io.amscotti.bravesearch.domain.result.AnswersResult;
import io.amscotti.bravesearch.testsupport.ScriptedSseServer;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Loopback contract of the blocking answers gateway: the assembled POST reaches the wire
 * with its exact body bytes — the one user message and the stream flag false — under the
 * nested web-search-options form the endpoint test pins field for field; a blocking 200
 * response projects the observed exchange across the port, its Answers usage arriving
 * through the shared {@code X-Request-*} header parsing; failing exchanges keep the
 * classifier's categories; and the library wiring routes credentials by origin — the
 * loopback test key never the stored credential, and an unresolvable stored credential a
 * local-configuration failure, not an exchange.
 */
final class BraveHttpAnswersGatewayTest {

    private static final byte[] BLOCKING_BODY =
            ("{\"id\":\"ans_01\",\"object\":\"chat.completion\",\"created\":1756579200,\"model\":\"brave\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"An independent index.\"},"
                    + "\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":900,\"completion_tokens\":120,\"total_tokens\":1020}}")
                    .getBytes(UTF_8);

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void theBlockingExchangeProjectsBodyAndHeaderObservedUsageAcrossThePort() throws Exception {
        String sentinel = "sentinel-" + UUID.randomUUID();
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .header("X-Request-ID", "req-answers-17")
                .header("Api-Version", "2026-08-30")
                .header("X-Request-Requests", "1")
                .header("X-Request-Queries", "2")
                .header("X-Request-Tokens-In", "900")
                .header("X-Request-Tokens-Out", "120")
                .header("X-Request-Total-Cost", "0.0042")
                .writeBytes(BLOCKING_BODY)
                .start()) {

            Outcome<AnswersResult> outcome = cliGateway(server, sentinel)
                    .answer(AnswersRequest.builder("what is the brave search api").stream(false).build());

            AnswersResult result = valueOf(outcome);
            assertEquals(200, result.httpStatus());
            assertArrayEquals(BLOCKING_BODY, result.body().toByteArray(), "the lossless body rides the result");
            assertEquals("req-answers-17", result.requestId());
            assertEquals("2026-08-30", result.apiVersion());
            assertEquals(1L, result.usage().requests());
            assertEquals(2L, result.usage().queries());
            assertEquals(900L, result.usage().tokensIn());
            assertEquals(120L, result.usage().tokensOut());
            assertEquals(0, new BigDecimal("0.0042").compareTo(result.usage().totalCost()), "costs keep exact scale");

            ScriptedSseServer.RecordedRequest recorded = server.requests().getFirst();
            assertEquals("POST", recorded.method());
            assertEquals("/chat/completions", recorded.path());
            assertEquals(
                    "{\"messages\":[{\"content\":\"what is the brave search api\",\"role\":\"user\"}],\"stream\":false}",
                    new String(recorded.body(), UTF_8),
                    "the served request body is the minimal blocking form byte for byte");
            assertEquals(
                    "application/json", recorded.firstHeader("Content-Type").orElseThrow());
            assertEquals(
                    sentinel,
                    recorded.firstHeader("X-Subscription-Token").orElseThrow(),
                    "the CLI wiring authenticates with the preflight-resolved credential");
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void anUnauthorizedResponseKeepsTheAuthenticationClassification() throws Exception {
        String sentinel = "sentinel-" + UUID.randomUUID();
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(401)
                .header("Content-Type", "application/json")
                .writeBytes("{\"error\":{\"code\":\"Unauthorized\",\"detail\":\"recovery hint\"}}".getBytes(UTF_8))
                .start()) {

            Outcome<AnswersResult> outcome =
                    cliGateway(server, sentinel).answer(AnswersRequest.builder("q").stream(false).build());

            Outcome.Failure<AnswersResult> failure = failureOf(outcome);
            assertEquals(FailureKind.AUTHENTICATION, failure.kind());
            assertEquals(401, failure.httpStatus());
            assertEquals("Unauthorized", failure.upstream().code());
            assertFalse(failure.diagnostic().contains(sentinel), "the diagnostic carries no token material");
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aLoopbackLibraryLookupDrawsTheTestKeyAndNeverTheStoredCredential() throws Exception {
        String loopbackSentinel = "loopback-sentinel-" + UUID.randomUUID();
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .writeBytes(BLOCKING_BODY)
                .start()) {

            Outcome<AnswersResult> outcome = new BraveHttpAnswersGateway(
                            transport(Duration.ofSeconds(10)),
                            loopbackOrigin(server),
                            new RefusingCredentials(),
                            () -> Credential.of(loopbackSentinel.getBytes(UTF_8)),
                            Duration.ofSeconds(10),
                            null)
                    .answer(AnswersRequest.builder("q").stream(false).build());

            assertEquals(200, valueOf(outcome).httpStatus());
            ScriptedSseServer.RecordedRequest recorded = server.requests().getFirst();
            assertEquals(
                    loopbackSentinel,
                    recorded.firstHeader("X-Subscription-Token").orElseThrow(),
                    "a loopback exchange draws its token from the test supplier, never the stored credential");
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void anUnresolvableStoredCredentialFailsAsLocalConfiguration() {
        Outcome<AnswersResult> outcome = new BraveHttpAnswersGateway(
                        transport(Duration.ofSeconds(1)),
                        BraveApiOrigin.production(),
                        new MissingCredentials(),
                        () -> {
                            throw new IllegalStateException("the production origin never consults the loopback supplier");
                        },
                        Duration.ofSeconds(2),
                        null)
                .answer(AnswersRequest.builder("q").stream(false).build());

        Outcome.Failure<AnswersResult> failure = failureOf(outcome);
        assertEquals(FailureKind.LOCAL_CONFIG, failure.kind());
        assertTrue(failure.diagnostic().contains("missing credential"), failure.diagnostic());
        assertNull(failure.upstream());
    }

    private static BraveHttpAnswersGateway cliGateway(ScriptedSseServer server, String sentinel) {
        return new BraveHttpAnswersGateway(
                transport(Duration.ofSeconds(10)),
                loopbackOrigin(server),
                Credential.of(sentinel.getBytes(UTF_8)),
                Duration.ofSeconds(10),
                null);
    }

    private static BraveHttpTransport transport(Duration totalTimeout) {
        return new BraveHttpTransport(
                new BraveHttpClientFactory(Duration.ofSeconds(5)).newClient(),
                totalTimeout,
                ResponseLimits.production(),
                Clock.systemUTC());
    }

    private static BraveApiOrigin loopbackOrigin(ScriptedSseServer server) {
        return BraveApiOrigin.fromOverride(
                server.baseUrl().toString(), host -> { throw new AssertionError("literals are never resolved"); });
    }

    private static AnswersResult valueOf(Outcome<AnswersResult> outcome) {
        return switch (outcome) {
            case Outcome.Success<AnswersResult> success -> success.value();
            case Outcome.Failure<AnswersResult> failure ->
                    throw new AssertionError("expected a success, saw " + failure.kind() + ": " + failure.diagnostic());
        };
    }

    private static Outcome.Failure<AnswersResult> failureOf(Outcome<AnswersResult> outcome) {
        return switch (outcome) {
            case Outcome.Success<AnswersResult> ignored ->
                    throw new AssertionError("expected a failure, saw a success");
            case Outcome.Failure<AnswersResult> failure -> failure;
        };
    }

    /** A stored-credential stand-in whose consultation on a loopback exchange is itself the failure. */
    private static final class RefusingCredentials implements CredentialProvider {

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

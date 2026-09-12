package io.amscotti.bravesearch.adapter.bravehttp.endpoint;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.amscotti.bravesearch.domain.request.RichRequest;
import io.amscotti.bravesearch.domain.result.RichResult;
import io.amscotti.bravesearch.testsupport.ScriptedSseServer;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Library-path credential routing of the rich gateway: the stored production credential,
 * resolved through the injected provider, reaches only the exact production origin, while a
 * loopback origin draws its token from the injected loopback test-token supplier — mirroring
 * the web gateway's routing proof — and a stored credential that cannot resolve on the
 * library path is a local-configuration failure, not an exchange.
 */
final class BraveHttpRichGatewayTest {

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aLoopbackLookupDrawsTheTestKeyAndNeverTheStoredCredential() throws Exception {
        String loopbackSentinel = "loopback-sentinel-" + UUID.randomUUID();
        String storedSentinel = "stored-sentinel-" + UUID.randomUUID();
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .writeBytes("{\"videos\":[]}".getBytes(UTF_8))
                .start()) {

            Outcome<RichResult> outcome = libraryGateway(
                            server,
                            new RefusingCredentials(),
                            () -> Credential.of(loopbackSentinel.getBytes(UTF_8)))
                    .rich(new RichRequest("cb-7f3a2b"));

            assertEquals(200, valueOf(outcome).httpStatus());
            ScriptedSseServer.RecordedRequest recorded = server.requests().getFirst();
            assertEquals(
                    loopbackSentinel,
                    recorded.firstHeader("X-Subscription-Token").orElseThrow(),
                    "a loopback exchange draws its token from the test supplier, never the stored credential");
            for (java.util.List<String> values : recorded.headers().values()) {
                for (String value : values) {
                    assertFalse(value.contains(storedSentinel), "the stored credential must never reach a loopback peer");
                }
            }
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void anUnresolvableStoredCredentialFailsAsLocalConfiguration() {
        Outcome<RichResult> outcome = new BraveHttpRichGateway(
                loopbackIndependentTransport(),
                BraveApiOrigin.production(),
                new MissingCredentials(),
                () -> {
                    throw new IllegalStateException("the production origin never consults the loopback supplier");
                },
                Duration.ofSeconds(2),
                null)
                .rich(new RichRequest("cb-7f3a2b"));

        Outcome.Failure<RichResult> failure = failureOf(outcome);
        assertEquals(FailureKind.LOCAL_CONFIG, failure.kind());
        assertTrue(failure.diagnostic().contains("missing credential"), failure.diagnostic());
    }

    private static BraveHttpRichGateway libraryGateway(
            ScriptedSseServer server, CredentialProvider storedCredentials, java.util.function.Supplier<Credential> loopbackTestToken) {
        return new BraveHttpRichGateway(
                new BraveHttpTransport(
                        new BraveHttpClientFactory(Duration.ofSeconds(5)).newClient(),
                        Duration.ofSeconds(10),
                        ResponseLimits.production(),
                        Clock.systemUTC()),
                BraveApiOrigin.fromOverride(
                        server.baseUrl().toString(), host -> { throw new AssertionError("literals are never resolved"); }),
                storedCredentials,
                loopbackTestToken,
                Duration.ofSeconds(10),
                null);
    }

    private static BraveHttpTransport loopbackIndependentTransport() {
        return new BraveHttpTransport(
                new BraveHttpClientFactory(Duration.ofSeconds(1)).newClient(),
                Duration.ofSeconds(1),
                ResponseLimits.production(),
                Clock.systemUTC());
    }

    private static RichResult valueOf(Outcome<RichResult> outcome) {
        return switch (outcome) {
            case Outcome.Success<RichResult> success -> success.value();
            case Outcome.Failure<RichResult> failure ->
                    throw new AssertionError("expected a success, saw " + failure.kind() + ": " + failure.diagnostic());
        };
    }

    private static Outcome.Failure<RichResult> failureOf(Outcome<RichResult> outcome) {
        return switch (outcome) {
            case Outcome.Success<RichResult> ignored ->
                    throw new AssertionError("expected a failure, saw a success");
            case Outcome.Failure<RichResult> failure -> failure;
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

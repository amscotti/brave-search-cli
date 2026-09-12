package io.amscotti.bravesearch.adapter.bravehttp.endpoint;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.amscotti.bravesearch.adapter.bravehttp.BraveHttpClientFactory;
import io.amscotti.bravesearch.adapter.bravehttp.BraveHttpTransport;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.LocalhostResolver;
import io.amscotti.bravesearch.application.exchange.ResponseLimits;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.CredentialResolutionException;
import io.amscotti.bravesearch.application.port.out.ResolvedCredential;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.request.ContextRequest;
import io.amscotti.bravesearch.domain.result.ContextResult;
import io.amscotti.bravesearch.testsupport.ScriptedSseServer;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Library-path wiring of the context gateway: the credential resolves per search through
 * the origin's routing rule, exactly like every sibling gateway — a loopback origin draws
 * its token from the injected test-token supplier (never the stored provider), and a
 * rotating supplier's later value reaches a later search's wire.
 */
final class BraveHttpContextGatewayLibraryWiringTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void eachSearchDrawsItsLoopbackTokenThroughTheRotatingTestSupplier() throws Exception {
        String storedSentinel = "stored-sentinel-" + UUID.randomUUID();
        String firstToken = "loopback-one-" + UUID.randomUUID();
        String secondToken = "loopback-two-" + UUID.randomUUID();
        AtomicReference<String> current = new AtomicReference<>(firstToken);
        byte[] body = "{}".getBytes(UTF_8);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .writeBytes(body)
                .start()) {

            BraveHttpTransport transport = new BraveHttpTransport(
                    new BraveHttpClientFactory(TIMEOUT).newClient(), TIMEOUT, ResponseLimits.production(), Clock.systemUTC());
            BraveHttpContextGateway gateway = new BraveHttpContextGateway(
                    transport,
                    BraveApiOrigin.fromOverride(server.baseUrl().toString(), LocalhostResolver.platform()),
                    new FixedCredentials(Credential.of(storedSentinel.getBytes(UTF_8))),
                    () -> Credential.of(current.get().getBytes(UTF_8)),
                    TIMEOUT,
                    null);
            try {
                Outcome<ContextResult> first = gateway.search(ContextRequest.builder("bacon").build());
                current.set(secondToken);
                Outcome<ContextResult> second = gateway.search(ContextRequest.builder("eggs").build());

                assertEquals(200, valueOf(first).httpStatus());
                assertEquals(200, valueOf(second).httpStatus());
                assertEquals(
                        firstToken,
                        server.requests().getFirst().firstHeader("X-Subscription-Token").orElseThrow(),
                        "the first search drew the supplier's first value");
                assertEquals(
                        secondToken,
                        server.requests().getLast().firstHeader("X-Subscription-Token").orElseThrow(),
                        "a rotating supplier's later value reaches the later search");
            } finally {
                gateway.close();
            }
        }
    }

    @Test
    void nullCollaboratorsAreRejected() {
        BraveHttpTransport transport = new BraveHttpTransport(
                new BraveHttpClientFactory(TIMEOUT).newClient(), TIMEOUT, ResponseLimits.production(), Clock.systemUTC());
        try {
            assertThrows(
                    NullPointerException.class,
                    () -> new BraveHttpContextGateway(
                            transport,
                            BraveApiOrigin.production(),
                            null,
                            () -> Credential.of("loopback-1".getBytes(UTF_8)),
                            TIMEOUT,
                            null));
            assertThrows(
                    NullPointerException.class,
                    () -> new BraveHttpContextGateway(
                            transport,
                            BraveApiOrigin.production(),
                            new FixedCredentials(Credential.of("stored-1".getBytes(UTF_8))),
                            null,
                            TIMEOUT,
                            null));
        } finally {
            transport.close();
        }
    }

    private static ContextResult valueOf(Outcome<ContextResult> outcome) {
        return switch (outcome) {
            case Outcome.Success<ContextResult> success -> success.value();
            case Outcome.Failure<ContextResult> failure -> throw new AssertionError(
                    "expected success, got " + failure.kind() + ": " + failure.diagnostic());
        };
    }

    private record FixedCredentials(Credential credential) implements CredentialProvider {

        @Override
        public Credential resolve() throws CredentialResolutionException {
            return credential;
        }

        @Override
        public ResolvedCredential resolveWithProvenance() throws CredentialResolutionException {
            return new ResolvedCredential(credential, "fixed", false);
        }
    }
}

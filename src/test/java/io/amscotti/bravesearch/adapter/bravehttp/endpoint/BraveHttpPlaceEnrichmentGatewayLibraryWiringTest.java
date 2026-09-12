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
import io.amscotti.bravesearch.domain.request.PlaceEnrichmentRequest;
import io.amscotti.bravesearch.domain.result.PlaceEnrichmentResult;
import io.amscotti.bravesearch.testsupport.ScriptedSseServer;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Library-path wiring of the place-enrichment gateway: the credential resolves per fetch
 * through the origin's routing rule, exactly like every sibling gateway — a loopback origin
 * draws its token from the injected test-token supplier (never the stored provider), and a
 * rotating supplier's later value reaches a later fetch's wire.
 */
final class BraveHttpPlaceEnrichmentGatewayLibraryWiringTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void eachFetchDrawsItsLoopbackTokenThroughTheRotatingTestSupplier() throws Exception {
        String storedSentinel = "stored-sentinel-" + UUID.randomUUID();
        String firstToken = "loopback-one-" + UUID.randomUUID();
        String secondToken = "loopback-two-" + UUID.randomUUID();
        AtomicReference<String> current = new AtomicReference<>(firstToken);
        byte[] body = "{\"results\":[]}".getBytes(UTF_8);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .writeBytes(body)
                .start()) {

            BraveHttpTransport transport = new BraveHttpTransport(
                    new BraveHttpClientFactory(TIMEOUT).newClient(),
                    TIMEOUT,
                    ResponseLimits.production(),
                    Clock.systemUTC());
            BraveHttpPlaceEnrichmentGateway gateway = new BraveHttpPlaceEnrichmentGateway(
                    transport,
                    BraveApiOrigin.fromOverride(server.baseUrl().toString(), LocalhostResolver.platform()),
                    new FixedCredentials(Credential.of(storedSentinel.getBytes(UTF_8))),
                    () -> Credential.of(current.get().getBytes(UTF_8)),
                    TIMEOUT,
                    null);
            try {
                Outcome<PlaceEnrichmentResult> first = gateway.fetch(request());
                current.set(secondToken);
                Outcome<PlaceEnrichmentResult> second = gateway.fetch(request());

                assertEquals(200, valueOf(first).httpStatus());
                assertEquals(200, valueOf(second).httpStatus());
                assertEquals(2, server.requests().size(), "two fetches rode the shared transport");
                assertEquals(
                        firstToken,
                        server.requests().getFirst().firstHeader("X-Subscription-Token").orElseThrow(),
                        "the first fetch drew the supplier's first value");
                assertEquals(
                        secondToken,
                        server.requests().getLast().firstHeader("X-Subscription-Token").orElseThrow(),
                        "a rotating supplier's later value reaches the later fetch");
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
                    () -> new BraveHttpPlaceEnrichmentGateway(
                            transport,
                            BraveApiOrigin.production(),
                            null,
                            () -> Credential.of("loopback-1".getBytes(UTF_8)),
                            TIMEOUT,
                            null));
            assertThrows(
                    NullPointerException.class,
                    () -> new BraveHttpPlaceEnrichmentGateway(
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

    private static PlaceEnrichmentRequest request() {
        return new PlaceEnrichmentRequest(PlaceEnrichmentRequest.Kind.DETAILS, List.of("opaque-id-1"));
    }

    private static PlaceEnrichmentResult valueOf(Outcome<PlaceEnrichmentResult> outcome) {
        return switch (outcome) {
            case Outcome.Success<PlaceEnrichmentResult> success -> success.value();
            case Outcome.Failure<PlaceEnrichmentResult> failure -> throw new AssertionError(
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

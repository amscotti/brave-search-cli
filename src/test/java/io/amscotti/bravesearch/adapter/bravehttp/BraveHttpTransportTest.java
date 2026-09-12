package io.amscotti.bravesearch.adapter.bravehttp;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.application.exchange.BraveApiRequest;
import io.amscotti.bravesearch.application.exchange.BraveHttpResponse;
import io.amscotti.bravesearch.application.exchange.ExpectedUserAgent;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.testsupport.ScriptedSseServer;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Loopback contract of the transport: the composed headers reach the wire with exact values,
 * a redirect response is classified as an upstream failure instead of being followed, and
 * transport-level breaks map to redacted TRANSPORT failures. Every test authenticates with its
 * own single-use sentinel token and proves neither the token nor its query text reaches any
 * failure diagnostic.
 */
final class BraveHttpTransportTest {

    private static final Duration GENEROUS_HEADERS_TIMEOUT = Duration.ofSeconds(10);

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void sendsTheComposedHeadersWithExactValuesOnTheWire() throws Exception {
        String token = "sentinel-" + UUID.randomUUID();
        byte[] body = "{\"web\":{\"results\":[]}}".getBytes(UTF_8);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .writeBytes(body)
                .start()) {

            Outcome<BraveHttpResponse> outcome = transport(GENEROUS_HEADERS_TIMEOUT)
                    .send(get(server.baseUrl().resolve("web/search"), token).build());

            BraveHttpResponse response = valueOf(outcome);
            assertEquals(200, response.statusCode());
            assertEquals(new UpstreamPayload(body), response.body());

            ScriptedSseServer.RecordedRequest recorded = server.requests().getFirst();
            assertEquals("GET", recorded.method());
            assertEquals("/web/search", recorded.path());
            assertEquals(token, recorded.firstHeader("X-Subscription-Token").orElseThrow());
            assertEquals("application/json", recorded.firstHeader("Accept").orElseThrow());
            assertEquals("gzip", recorded.firstHeader("Accept-Encoding").orElseThrow());
            assertEquals(ExpectedUserAgent.fromVersionResource(), recorded.firstHeader("User-Agent").orElseThrow());
            assertTrue(recorded.firstHeader("Content-Type").isEmpty(), "a bodyless GET must not carry Content-Type");
            assertTrue(recorded.firstHeader("Api-Version").isEmpty(), "no pin was given");
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void rawResponseModeTravelsAsIdentityEncoding() throws Exception {
        String token = "sentinel-" + UUID.randomUUID();
        try (ScriptedSseServer server = servingEmptyJsonBody()) {

            transport(GENEROUS_HEADERS_TIMEOUT)
                    .send(get(server.baseUrl().resolve("web/search"), token)
                            .rawMode(true)
                            .build());

            assertEquals(
                    "identity",
                    server.requests().getFirst().firstHeader("Accept-Encoding").orElseThrow());
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void theApiVersionHeaderTravelsOnlyWhenPinned() throws Exception {
        try (ScriptedSseServer server = servingEmptyJsonBody()) {
            BraveHttpTransport transport = transport(GENEROUS_HEADERS_TIMEOUT);

            transport.send(get(server.baseUrl().resolve("web/search"), "sentinel-" + UUID.randomUUID())
                    .apiVersion("2025-06-30")
                    .build());
            transport.send(get(server.baseUrl().resolve("web/search"), "sentinel-" + UUID.randomUUID())
                    .build());

            assertEquals(
                    "2025-06-30",
                    server.requests().get(0).firstHeader("Api-Version").orElseThrow());
            assertTrue(
                    server.requests().get(1).firstHeader("Api-Version").isEmpty(),
                    "without a pin no Api-Version header may travel");
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void jsonPostsCarryTheContentTypeExactlyAndGetsDoNot() throws Exception {
        try (ScriptedSseServer server = servingEmptyJsonBody()) {
            BraveHttpTransport transport = transport(GENEROUS_HEADERS_TIMEOUT);

            transport.send(BraveApiRequest.post(
                            server.baseUrl().resolve("web/search"), "{\"q\":\"café\"}".getBytes(UTF_8))
                    .token(Credential.of(("sentinel-" + UUID.randomUUID()).getBytes(UTF_8)))
                    .build());
            transport.send(get(server.baseUrl().resolve("web/search"), "sentinel-" + UUID.randomUUID())
                    .build());

            ScriptedSseServer.RecordedRequest post = server.requests().get(0);
            assertEquals("POST", post.method());
            assertEquals("application/json", post.firstHeader("Content-Type").orElseThrow());
            assertTrue(
                    server.requests().get(1).firstHeader("Content-Type").isEmpty(),
                    "a bodyless GET must not carry Content-Type");
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void redirectResponsesAreClassifiedUpstreamAndNeverFollowed() throws Exception {
        String token = "sentinel-" + UUID.randomUUID();
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(302)
                .header("Location", "/landing")
                .writeBytes(new byte[0])
                .start()) {

            Outcome<BraveHttpResponse> outcome = transport(GENEROUS_HEADERS_TIMEOUT)
                    .send(get(server.baseUrl().resolve("hop"), token).build());

            assertEquals(FailureKind.UPSTREAM, kindOf(outcome), "a 3xx must classify as an upstream failure");
            assertTrue(diagnosticOf(outcome).contains("302"), "the diagnostic names the observed status");
            assertEquals(1, server.requests().size(), "the redirect target must never be requested");
            assertNoSecrets(diagnosticOf(outcome), token);
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aRefusedConnectionIsARedactedTransportFailure() throws Exception {
        String token = "sentinel-" + UUID.randomUUID();
        String queryText = "marker-" + UUID.randomUUID();
        ScriptedSseServer shortLived = servingEmptyJsonBody();
        URI gone = shortLived.baseUrl();
        shortLived.close();

        Outcome<BraveHttpResponse> outcome = transport(GENEROUS_HEADERS_TIMEOUT)
                .send(BraveApiRequest.get(URI.create(gone + "web/search?q=" + queryText))
                        .token(Credential.of(token.getBytes(UTF_8)))
                        .build());

        assertEquals(FailureKind.TRANSPORT, kindOf(outcome));
        assertNoSecrets(diagnosticOf(outcome), token, queryText);
        assertFalse(diagnosticOf(outcome).contains("web/search"), "the diagnostic carries no URI text");
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void stalledResponseHeadersAreARedactedTransportFailure() throws Exception {
        String token = "sentinel-" + UUID.randomUUID();
        String queryText = "marker-" + UUID.randomUUID();
        CountDownLatch neverSendHeaders = new CountDownLatch(1);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .holdHeadersUntil(neverSendHeaders)
                .writeBytes("never delivered".getBytes(UTF_8))
                .start()) {

            Outcome<BraveHttpResponse> outcome = transport(Duration.ofMillis(1500))
                    .send(BraveApiRequest.get(URI.create(server.baseUrl() + "web/search?q=" + queryText))
                            .token(Credential.of(token.getBytes(UTF_8)))
                            .build());

            assertEquals(FailureKind.TRANSPORT, kindOf(outcome), "a silent peer must surface as a transport failure");
            assertNoSecrets(diagnosticOf(outcome), token, queryText);
            assertTrue(server.awaitFirstRequest(Duration.ofSeconds(5)), "the peer must have seen the request");
        } finally {
            neverSendHeaders.countDown();
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aHeaderLineRefusedDuringAssemblyIsAnInternalFailureWithAFixedDiagnostic() throws Exception {
        String token = "sentinel-" + UUID.randomUUID();

        Outcome<BraveHttpResponse> outcome = transport(GENEROUS_HEADERS_TIMEOUT)
                .send(BraveApiRequest.get(URI.create("http://127.0.0.1:9/web/search"))
                        .token(Credential.of(token.getBytes(UTF_8)))
                        .userAgent("leaky-agent\r\nX-Injected: 1")
                        .build());

        assertEquals(FailureKind.INTERNAL, kindOf(outcome), "an unsendable assembly is an internal failure");
        assertEquals("outbound request assembly failed", diagnosticOf(outcome));
        assertNoSecrets(diagnosticOf(outcome), token);
    }

    private static ScriptedSseServer servingEmptyJsonBody() throws IOException {
        return ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .writeBytes("{}".getBytes(UTF_8))
                .start();
    }

    private static BraveHttpTransport transport(Duration responseHeadersTimeout) {
        return new BraveHttpTransport(
                new BraveHttpClientFactory(Duration.ofSeconds(5)).newClient(),
                responseHeadersTimeout,
                io.amscotti.bravesearch.application.exchange.ResponseLimits.production(),
                java.time.Clock.systemUTC());
    }

    private static BraveApiRequest.Builder get(URI uri, String token) {
        return BraveApiRequest.get(uri).token(Credential.of(token.getBytes(UTF_8)));
    }

    private static BraveHttpResponse valueOf(Outcome<BraveHttpResponse> outcome) {
        return switch (outcome) {
            case Outcome.Success<BraveHttpResponse> success -> success.value();
            case Outcome.Failure<BraveHttpResponse> failure ->
                    throw new AssertionError("expected a success, saw " + failure.kind() + ": " + failure.diagnostic());
        };
    }

    private static FailureKind kindOf(Outcome<BraveHttpResponse> outcome) {
        return switch (outcome) {
            case Outcome.Success<BraveHttpResponse> ignored ->
                    throw new AssertionError("expected a failure, saw a success");
            case Outcome.Failure<BraveHttpResponse> failure -> failure.kind();
        };
    }

    private static String diagnosticOf(Outcome<BraveHttpResponse> outcome) {
        return switch (outcome) {
            case Outcome.Success<BraveHttpResponse> ignored ->
                    throw new AssertionError("expected a failure, saw a success");
            case Outcome.Failure<BraveHttpResponse> failure -> failure.diagnostic();
        };
    }

    private static void assertNoSecrets(String diagnostic, String... secrets) {
        for (String secret : secrets) {
            assertFalse(diagnostic.contains(secret), "a failure diagnostic carries secret material");
        }
    }
}

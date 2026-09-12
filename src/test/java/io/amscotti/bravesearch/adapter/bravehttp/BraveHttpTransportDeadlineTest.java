package io.amscotti.bravesearch.adapter.bravehttp;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.application.exchange.BraveApiRequest;
import io.amscotti.bravesearch.application.exchange.BraveHttpResponse;
import io.amscotti.bravesearch.application.exchange.ResponseLimits;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.testsupport.ScriptedSseServer;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The total-deadline seam of the transport: a body that stalls after the response headers
 * breaches the injected total budget as its own redacted TRANSPORT failure — distinct from
 * the headers-phase timeout, which keeps bounding the opening phase regardless of the total
 * budget — while a body that completes within the budget is delivered byte-exact.
 */
final class BraveHttpTransportDeadlineTest {

    private static final Duration GENEROUS_HEADERS_TIMEOUT = Duration.ofSeconds(10);

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aStalledBodyAfterHeadersBreachesTheTotalDeadlineWithItsOwnDiagnostic() throws Exception {
        CountDownLatch stalled = new CountDownLatch(1);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .writeBytes("{\"web\":{\"results\":[{\"partial\"".getBytes(UTF_8))
                .flush()
                .stallUntil(stalled)
                .start()) {

            long startedAt = System.nanoTime();
            Outcome<BraveHttpResponse> outcome =
                    transport().send(get(server.baseUrl().resolve("web/search")).build(), Duration.ofMillis(500));
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

            assertEquals(FailureKind.TRANSPORT, kindOf(outcome));
            assertEquals("total request deadline exceeded", diagnosticOf(outcome));
            assertTrue(elapsedMs < 5000, "the breach surfaces within the budget, not at the headers ceiling: " + elapsedMs + " ms");
        } finally {
            stalled.countDown();
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aBodyThatCompletesWithinTheTotalBudgetIsDeliveredByteExact() throws Exception {
        byte[] body = "{\"web\":{\"results\":[]}}".getBytes(UTF_8);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .writeBytes(body)
                .start()) {

            Outcome<BraveHttpResponse> outcome =
                    transport().send(get(server.baseUrl().resolve("web/search")).build(), Duration.ofSeconds(10));

            BraveHttpResponse response = valueOf(outcome);
            assertEquals(200, response.statusCode());
            assertArrayEquals(body, response.body().toByteArray());
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void theHeadersPhaseKeepsItsOwnTimeoutIndependentlyOfTheTotalBudget() throws Exception {
        CountDownLatch neverSendHeaders = new CountDownLatch(1);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .holdHeadersUntil(neverSendHeaders)
                .writeBytes("never delivered".getBytes(UTF_8))
                .start()) {

            Outcome<BraveHttpResponse> outcome = new BraveHttpTransport(
                            new BraveHttpClientFactory(Duration.ofSeconds(5)).newClient(),
                            Duration.ofMillis(1500),
                            ResponseLimits.production(),
                            Clock.systemUTC())
                    .send(get(server.baseUrl().resolve("web/search")).build(), Duration.ofSeconds(30));

            assertEquals(FailureKind.TRANSPORT, kindOf(outcome), "a silent peer stays a transport failure");
            assertTrue(
                    diagnosticOf(outcome).contains("HttpTimeoutException"),
                    "the opening phase is bounded by the headers timeout: " + diagnosticOf(outcome));
            assertFalse(
                    diagnosticOf(outcome).contains("total request deadline exceeded"),
                    "the headers-phase break never borrows the total-deadline diagnostic");
            assertTrue(server.awaitFirstRequest(Duration.ofSeconds(5)), "the peer must have seen the request");
        } finally {
            neverSendHeaders.countDown();
        }
    }

    @Test
    void aZeroOrNegativeTotalBudgetIsRejectedUpFront() {
        BraveHttpTransport bounded = transport();
        BraveApiRequest request =
                BraveApiRequest.get(URI.create("http://127.0.0.1:9/web/search?q=x"))
                        .token(Credential.of("sentinel".getBytes(UTF_8)))
                        .build();
        assertThrows(IllegalArgumentException.class, () -> bounded.send(request, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> bounded.send(request, Duration.ofSeconds(-1)));
    }

    private static BraveHttpTransport transport() {
        return new BraveHttpTransport(
                new BraveHttpClientFactory(Duration.ofSeconds(5)).newClient(),
                GENEROUS_HEADERS_TIMEOUT,
                ResponseLimits.production(),
                Clock.systemUTC());
    }

    private static BraveApiRequest.Builder get(URI uri) {
        return BraveApiRequest.get(uri).token(Credential.of(("sentinel-" + UUID.randomUUID()).getBytes(UTF_8)));
    }


    private static BraveHttpResponse valueOf(Outcome<BraveHttpResponse> outcome) {
        return switch (outcome) {
            case Outcome.Success<BraveHttpResponse> success -> success.value();
            case Outcome.Failure<BraveHttpResponse> failure ->
                    throw new AssertionError("expected a success, saw " + failure.kind() + ": " + failure.diagnostic());
        };
    }

    private static FailureKind kindOf(Outcome<BraveHttpResponse> outcome) {
        return failureOf(outcome).kind();
    }

    private static String diagnosticOf(Outcome<BraveHttpResponse> outcome) {
        return failureOf(outcome).diagnostic();
    }

    private static Outcome.Failure<BraveHttpResponse> failureOf(Outcome<BraveHttpResponse> outcome) {
        return switch (outcome) {
            case Outcome.Success<BraveHttpResponse> ignored ->
                    throw new AssertionError("expected a failure, saw a success");
            case Outcome.Failure<BraveHttpResponse> failure -> failure;
        };
    }
}

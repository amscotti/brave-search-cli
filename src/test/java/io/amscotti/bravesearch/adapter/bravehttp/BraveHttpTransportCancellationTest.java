package io.amscotti.bravesearch.adapter.bravehttp;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.application.exchange.BraveApiRequest;
import io.amscotti.bravesearch.application.exchange.BraveHttpResponse;
import io.amscotti.bravesearch.application.exchange.ResponseLimits;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.testsupport.ScriptedSseServer;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Loopback contract of a signalled single-request exchange: a SIGINT or SIGTERM latched on
 * the run's cancellation context while the response body is still trickling must end the
 * exchange promptly as its own cancelled transport failure — never the total-deadline
 * diagnostic, never an unbounded wait — severing the stalled peer on the way out.
 */
final class BraveHttpTransportCancellationTest {

    private static final Duration GENEROUS_HEADERS_TIMEOUT = Duration.ofSeconds(10);
    private static final ResponseLimits SMALL_LIMITS =
            new ResponseLimits(64 * ResponseLimits.KIB, 8 * ResponseLimits.KIB, 2 * ResponseLimits.KIB);
    private static final Clock OBSERVED_AT_CLOCK =
            Clock.fixed(Instant.parse("2026-09-01T10:15:30Z"), ZoneOffset.UTC);

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aLatchedInterruptCancelsAMidBodyReadPromptly() throws Exception {
        byte[] opening = "{\"web\":{\"results\":[{\"title\":\"partial".getBytes(UTF_8);
        // a heartbeat trickle keeps the transfer provably in flight — the body never
        // completes and the server keeps writing, so the severance witness observes the cancel
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .writeBytes(opening)
                .flush()
                .heartbeatUntilClosed("still-writing")
                .start()) {

            CancellationContext cancellation = new CancellationContext();
            Thread.ofVirtual().start(() -> {
                try {
                    assertTrue(server.awaitFirstRequest(Duration.ofSeconds(5)), "the exchange must reach the server");
                    TimeUnit.MILLISECONDS.sleep(300);
                } catch (InterruptedException waiting) {
                    Thread.currentThread().interrupt();
                }
                cancellation.latch(CancellationContext.Cause.SIGINT);
            });

            long startedAt = System.nanoTime();
            Outcome<BraveHttpResponse> outcome = send(server, cancellation, Duration.ofSeconds(20));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            Outcome.Failure<BraveHttpResponse> failure = assertInstanceOf(Outcome.Failure.class, outcome);
            assertEquals(FailureKind.TRANSPORT, failure.kind(), () -> describe(failure, elapsedMs));
            assertEquals("upstream exchange cancelled", failure.diagnostic(), () -> describe(failure, elapsedMs));
            assertTrue(elapsedMs < 5000, () -> "a cancelled exchange must not wait out its budget: " + describe(failure, elapsedMs));
            assertTrue(
                    server.awaitConnectionClosed(Duration.ofSeconds(5)),
                    "the cancelled exchange must sever the stalled peer");
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aLatchedInterruptUnblocksAHeadersPhaseWaitPromptly() throws Exception {
        CountDownLatch neverSendHeaders = new CountDownLatch(1);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .holdHeadersUntil(neverSendHeaders)
                .writeBytes("{\"web\":{\"results\":[]}}".getBytes(UTF_8))
                .start()) {
            CancellationContext cancellation = new CancellationContext();
            Thread.ofVirtual().start(() -> {
                try {
                    assertTrue(server.awaitFirstRequest(Duration.ofSeconds(5)), "the exchange must reach the server");
                    TimeUnit.MILLISECONDS.sleep(300);
                } catch (InterruptedException waiting) {
                    Thread.currentThread().interrupt();
                }
                cancellation.latch(CancellationContext.Cause.SIGINT);
            });

            long startedAt = System.nanoTime();
            Outcome<BraveHttpResponse> outcome = send(server, cancellation, Duration.ofSeconds(20));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            Outcome.Failure<BraveHttpResponse> failure = assertInstanceOf(Outcome.Failure.class, outcome);
            assertEquals(FailureKind.TRANSPORT, failure.kind(), () -> describe(failure, elapsedMs));
            assertEquals("upstream exchange cancelled", failure.diagnostic(), () -> describe(failure, elapsedMs));
            assertTrue(
                    elapsedMs < 5000,
                    () -> "a cancelled headers wait must not ride out the headers timeout: "
                            + describe(failure, elapsedMs));
            assertFalse(
                    Thread.currentThread().isInterrupted(),
                    "a completed exchange hands its caller a clear interrupt status");
        } finally {
            neverSendHeaders.countDown();
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aLatchedTerminationCancelsAMidBodyReadPromptly() throws Exception {
        byte[] opening = "{\"web\":{\"results\":[{\"title\":\"partial".getBytes(UTF_8);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .writeBytes(opening)
                .flush()
                .heartbeatUntilClosed("still-writing")
                .start()) {

            CancellationContext cancellation = new CancellationContext();
            Thread.ofVirtual().start(() -> {
                try {
                    assertTrue(server.awaitFirstRequest(Duration.ofSeconds(5)), "the exchange must reach the server");
                    TimeUnit.MILLISECONDS.sleep(300);
                } catch (InterruptedException waiting) {
                    Thread.currentThread().interrupt();
                }
                cancellation.latch(CancellationContext.Cause.SIGTERM);
            });

            Outcome<BraveHttpResponse> outcome = send(server, cancellation, Duration.ofSeconds(20));

            Outcome.Failure<BraveHttpResponse> failure = assertInstanceOf(Outcome.Failure.class, outcome);
            assertEquals(FailureKind.TRANSPORT, failure.kind(), () -> describe(failure, -1));
            assertEquals("upstream exchange cancelled", failure.diagnostic(), () -> describe(failure, -1));
            assertFalse(
                    Thread.currentThread().isInterrupted(),
                    "the converted interrupt never outlives the returned verdict");
        }
    }

    private static String describe(Outcome.Failure<BraveHttpResponse> failure, long elapsedMs) {
        return "kind=" + failure.kind() + ", diagnostic=<" + failure.diagnostic() + ">, elapsedMs=" + elapsedMs;
    }

    private static Outcome<BraveHttpResponse> send(
            ScriptedSseServer server, CancellationContext cancellation, Duration totalTimeout) throws Exception {
        BraveHttpTransport transport = new BraveHttpTransport(
                new BraveHttpClientFactory(Duration.ofSeconds(5)).newClient(),
                GENEROUS_HEADERS_TIMEOUT,
                SMALL_LIMITS,
                OBSERVED_AT_CLOCK,
                cancellation);
        return transport.send(
                BraveApiRequest.get(URI.create(server.baseUrl() + "web/search?q=cancelled-" + UUID.randomUUID()))
                        .token(Credential.of(("sentinel-" + UUID.randomUUID()).getBytes(UTF_8)))
                        .build(),
                totalTimeout);
    }
}

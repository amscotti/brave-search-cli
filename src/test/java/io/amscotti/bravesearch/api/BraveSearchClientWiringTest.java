package io.amscotti.bravesearch.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import io.amscotti.bravesearch.domain.metadata.RateLimitWindow;
import io.amscotti.bravesearch.domain.request.AnswersRequest;
import io.amscotti.bravesearch.domain.request.WebSearchRequest;
import io.amscotti.bravesearch.testsupport.ScriptedSseServer;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The client as its builder really wires it — through the composition and the real transport,
 * against a loopback origin — keeps its documented promises end to end: every failure is an
 * outcome and never an exception, a failed stream open reaches the caller unchanged, closing
 * never waits behind a stalled exchange, and one failing release never strands the others.
 */
final class BraveSearchClientWiringTest {

    private static final Duration AWAIT = Duration.ofSeconds(5);

    private static Credential token() {
        return Credential.of(("token-sentinel-" + UUID.randomUUID()).getBytes(UTF_8));
    }

    private static BraveSearchClient client(Supplier<Credential> tokenSupplier, String loopbackBaseUrl) {
        BraveSearchClient.Builder builder =
                BraveSearchClient.builder().tokenSupplier(tokenSupplier).connectTimeout(Duration.ofSeconds(2));
        if (loopbackBaseUrl != null) {
            builder.baseUrl(loopbackBaseUrl);
        }
        return builder.build();
    }

    private static AnswersRequest streaming() {
        return AnswersRequest.builder("q").stream(true).build();
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void closeEndsAnInFlightBlockingExchangeAtOnceInsteadOfWaitingOutItsBudget() throws Exception {
        // the peer sends the headers and the start of a body, then withholds the rest: left
        // alone, the exchange would run the builder's whole thirty-second total budget
        CountDownLatch stalled = new CountDownLatch(1);
        Credential token = token();
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                        .statusCode(200)
                        .header("Content-Type", "application/json")
                        .writeBytes("{\"web\":".getBytes(UTF_8))
                        .flush()
                        .stallUntil(stalled)
                        .start();
                BraveSearchClient client = client(() -> token, server.baseUrl().toString())) {
            AtomicReference<Outcome<WebSearchResponse>> outcome = new AtomicReference<>();
            Thread sending = new Thread(() -> outcome.set(
                    client.webSearch(WebSearchRequest.builder("q").build())));
            sending.start();
            assertTrue(server.awaitFirstRequest(AWAIT), "the exchange must be in flight before the close");

            long startedAt = System.nanoTime();
            client.close();
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            sending.join(AWAIT.toMillis());

            assertFalse(sending.isAlive(), "the close ended the stalled exchange");
            assertTrue(elapsedMs < 4000, "the close lands at once, not after the budget: " + elapsedMs + " ms");
            Outcome.Failure<WebSearchResponse> ended = (Outcome.Failure<WebSearchResponse>) outcome.get();
            assertEquals(FailureKind.TRANSPORT, ended.kind(), "an exchange the close took away is a cancelled one");
            assertEquals("upstream exchange cancelled", ended.diagnostic());

            Outcome<WebSearchResponse> afterwards =
                    client.webSearch(WebSearchRequest.builder("q").build());
            Outcome.Failure<WebSearchResponse> closed = (Outcome.Failure<WebSearchResponse>) afterwards;
            assertEquals(FailureKind.TRANSPORT, closed.kind());
            assertEquals("the client is closed", closed.diagnostic());
        } finally {
            stalled.countDown();
        }
    }

    @Test
    void aTokenSupplierThatYieldsNothingOrThrowsIsALocalConfigurationFailureForBlockingAndStreamingCalls() {
        // the production origin is never reached: the credential is resolved before any
        // exchange, so these promises hold without a single network round trip
        try (BraveSearchClient noToken = client(() -> null, null);
                BraveSearchClient throwing = client(
                        () -> {
                            throw new IllegalStateException("the vault is sealed");
                        },
                        null)) {
            for (BraveSearchClient client : List.of(noToken, throwing)) {
                Outcome.Failure<WebSearchResponse> blocking =
                        (Outcome.Failure<WebSearchResponse>)
                                client.webSearch(WebSearchRequest.builder("q").build());
                assertEquals(FailureKind.LOCAL_CONFIG, blocking.kind(), "a missing credential is configuration");
                assertTrue(blocking.diagnostic().contains("token supplier"), blocking.diagnostic());

                Outcome.Failure<AnswersStreamHandle> streaming =
                        (Outcome.Failure<AnswersStreamHandle>) client.answersStream(streaming());
                assertEquals(FailureKind.LOCAL_CONFIG, streaming.kind(), "for the streaming open as well");
                assertTrue(streaming.diagnostic().contains("token supplier"), streaming.diagnostic());
            }
            assertTrue(
                    ((Outcome.Failure<?>) throwing.webSearch(WebSearchRequest.builder("q").build()))
                            .diagnostic()
                            .contains("the vault is sealed"),
                    "a throwing supplier's own reason reaches the caller");
        }
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void anUnreachableOriginIsATransportFailureOutcomeForBlockingAndStreamingExchanges() {
        // nothing listens on that port, so both opens fail on the wire
        try (BraveSearchClient client = client(BraveSearchClientWiringTest::token, "http://127.0.0.1:1")) {
            Outcome<WebSearchResponse> blocked =
                    client.webSearch(WebSearchRequest.builder("q").build());
            Outcome<AnswersStreamHandle> stream = client.answersStream(streaming());

            assertEquals(FailureKind.TRANSPORT, ((Outcome.Failure<?>) blocked).kind());
            assertEquals(FailureKind.TRANSPORT, ((Outcome.Failure<?>) stream).kind());
            assertFalse(((Outcome.Failure<?>) stream).diagnostic().isBlank(), "the failure still explains itself");
        }
    }

    @Test
    void aFailedStreamOpenReachesTheCallerAsTheSameFailureInstance() {
        RateLimitSnapshot windows = new RateLimitSnapshot(
                List.of(new RateLimitWindow("request", 1, 0, Duration.ofSeconds(2))), List.of(), Instant.EPOCH);
        Outcome.Failure<AnswersStreamHandle> refused = new Outcome.Failure<>(
                FailureKind.RATE_LIMITED, "upstream exchange failed with status 429", null, windows, 429);
        try (BraveSearchClient client = facade(invocation -> refused, List.of())) {

            Outcome<AnswersStreamHandle> opened = client.answersStream(streaming());

            assertSame(refused, opened, "nothing is copied or re-typed: the failure travels intact");
        }
    }

    @Test
    void aLiveStreamWhoseCloseThrowsNeverStrandsTheOwnedResources() {
        List<String> released = new ArrayList<>();
        AutoCloseable resource = () -> released.add("resource");
        try (BraveSearchClient client = facade(
                        invocation -> new Outcome.Success<>(new StubHandle(() -> {
                            released.add("stream");
                            throw new IllegalStateException("the stream refused to close");
                        })),
                        List.of(resource))) {
            client.answersStream(streaming());

            IllegalStateException failure = assertThrows(IllegalStateException.class, client::close);

            assertEquals("the stream refused to close", failure.getMessage(), "the failure propagates");
            assertEquals(List.of("stream", "resource"), released, "and the owned resource is still released");
        }
    }

    @Test
    void aReleaseThatThrowsNeverStrandsTheOthersAndTheFirstFailurePropagates() {
        List<String> released = new ArrayList<>();
        AutoCloseable firstOwned = () -> {
            released.add("first");
            throw new IllegalStateException("the first release failed");
        };
        AutoCloseable lastOwned = () -> {
            released.add("last");
            throw new IOException("the last release failed");
        };
        BraveSearchClient client = facade(invocation -> new Outcome.Failure<>(
                        FailureKind.USAGE, "unused", null, null, 0),
                List.of(firstOwned, lastOwned));

        IllegalStateException failure = assertThrows(IllegalStateException.class, client::close);

        // releases run most recently owned first, so the failure met first in release order is
        // the last-owned resource's — and that is the one the contract reports
        assertEquals("closing an owned resource failed", failure.getMessage(), "the first failure met is the one reported");
        assertTrue(
                failure.getCause() instanceof IOException && "the last release failed".equals(failure.getCause().getMessage()),
                "a checked release failure is wrapped with its cause kept: " + failure.getCause());
        assertEquals(List.of("last", "first"), released, "every remaining release still ran, most recently owned first");
        client.close();
        assertEquals(List.of("last", "first"), released, "a second close releases nothing and throws nothing");
    }

    @Test
    void aNonPositiveBudgetIsRejectedBeforeAnyClientIsWired() {
        BraveSearchClient.Builder noConnectBudget = BraveSearchClient.builder()
                .tokenSupplier(BraveSearchClientWiringTest::token)
                .connectTimeout(Duration.ZERO);

        IllegalArgumentException connect = assertThrows(IllegalArgumentException.class, noConnectBudget::build);
        assertEquals("connect timeout must be positive", connect.getMessage());

        BraveSearchClient.Builder noTotalBudget = BraveSearchClient.builder()
                .tokenSupplier(BraveSearchClientWiringTest::token)
                .connectTimeout(Duration.ofSeconds(1))
                .totalTimeout(Duration.ofSeconds(-1));

        IllegalArgumentException total = assertThrows(IllegalArgumentException.class, noTotalBudget::build);
        assertEquals("total timeout must be positive", total.getMessage());
    }

    @Test
    void everyReleaseFailureAfterTheFirstIsKeptAsASuppressedExceptionOfTheReportedOne() {
        // two resources that both refuse to release: a caller chasing the leak needs both
        // reasons, not only the one the release order happened to meet first
        IllegalStateException resourceOne = new IllegalStateException("the first resource refused");
        IllegalArgumentException resourceTwo = new IllegalArgumentException("the second resource refused");
        IOException resourceThree = new IOException("the third resource refused");
        BraveSearchClient client = facade(
                invocation -> new Outcome.Failure<>(FailureKind.USAGE, "unused", null, null, 0),
                List.of(
                        () -> {
                            throw resourceOne;
                        },
                        () -> {
                            throw resourceTwo;
                        },
                        () -> {
                            throw resourceThree;
                        }));

        IllegalStateException reported = assertThrows(IllegalStateException.class, client::close);

        assertEquals(
                "closing an owned resource failed",
                reported.getMessage(),
                "releases run most recently owned first, so the third resource's checked failure is met first");
        assertEquals(resourceThree, reported.getCause());
        assertArrayEquals(
                new Throwable[] {resourceTwo, resourceOne},
                reported.getSuppressed(),
                "no release failure is lost: each later one is attached to the reported failure");
    }

    @Test
    void aSecondStreamThatRefusesToCloseIsKeptAsASuppressedFailureOfTheFirst() {
        IllegalStateException first = new IllegalStateException("the first stream refused");
        IllegalArgumentException second = new IllegalArgumentException("the second stream refused");
        List<RuntimeException> refusals = new ArrayList<>(List.of(first, second));
        BraveSearchClient client = facade(
                invocation -> new Outcome.Success<>(new StubHandle(() -> {
                    throw refusals.removeFirst();
                })),
                List.of());
        client.answersStream(streaming());
        client.answersStream(streaming());

        RuntimeException reported = assertThrows(RuntimeException.class, client::close);

        assertEquals(first, reported, "streams release in the order they opened");
        assertArrayEquals(
                new Throwable[] {second},
                reported.getSuppressed(),
                "the second refusal is kept with the first instead of vanishing");
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void closeEndsAnInFlightAnswersStreamOpenAtOnceInsteadOfWaitingOutTheHeadersTimeout() throws Exception {
        CountDownLatch neverSendHeaders = new CountDownLatch(1);
        Credential token = token();
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                        .holdHeadersUntil(neverSendHeaders)
                        .header("Content-Type", "text/event-stream")
                        .writeBytes("data: [DONE]\n\n".getBytes(UTF_8))
                        .start();
                BraveSearchClient client = client(() -> token, server.baseUrl().toString())) {
            AtomicReference<Outcome<AnswersStreamHandle>> outcome = new AtomicReference<>();
            Thread opening = new Thread(() -> outcome.set(client.answersStream(streaming())));
            opening.start();
            assertTrue(server.awaitFirstRequest(AWAIT), "the stream open must reach the origin before close");

            long startedAt = System.nanoTime();
            client.close();
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            opening.join(AWAIT.toMillis());

            assertFalse(opening.isAlive(), "close must end an in-flight stream open promptly");
            assertTrue(elapsedMs < 4000, "close returns at once instead of waiting out the headers timeout: " + elapsedMs + " ms");
            Outcome.Failure<AnswersStreamHandle> ended = (Outcome.Failure<AnswersStreamHandle>) outcome.get();
            assertEquals(FailureKind.TRANSPORT, ended.kind());
            assertEquals("upstream stream exchange cancelled", ended.diagnostic());
        } finally {
            neverSendHeaders.countDown();
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aCallerSuppliedExecutorCarriesStreamingOpensAndSurvivesClose() throws Exception {
        AtomicBoolean ranOnCallerExecutor = new AtomicBoolean();
        ExecutorService callerOwned = Executors.newCachedThreadPool(task -> {
            ranOnCallerExecutor.set(true);
            Thread thread = new Thread(task, "caller-stream-worker");
            thread.setDaemon(true);
            return thread;
        });
        Credential token = token();
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "text/event-stream")
                .writeBytes("data: [DONE]\n\n".getBytes(UTF_8))
                .start()) {
            try (BraveSearchClient client = BraveSearchClient.builder()
                    .tokenSupplier(() -> token)
                    .baseUrl(server.baseUrl().toString())
                    .executor(callerOwned)
                    .build()) {
                Outcome<AnswersStreamHandle> opened = client.answersStream(streaming());
                assertTrue(opened instanceof Outcome.Success<AnswersStreamHandle>, "stream opens cleanly");
                ((Outcome.Success<AnswersStreamHandle>) opened).value().close();
            }
            assertTrue(ranOnCallerExecutor.get(), "the streaming open rides the caller-supplied executor");
            assertFalse(callerOwned.isShutdown(), "client.close() never shuts down a caller-supplied executor");
        } finally {
            callerOwned.shutdownNow();
        }
    }

    @Test
    void whileOpenRacingCloseMapsTransportClosedInternalFailureToClosedClientTransportFailure() {
        AtomicReference<BraveSearchClient> holder = new AtomicReference<>();
        BraveSearchClient client = new BraveSearchClient(
                request -> {
                    holder.get().close();
                    return new Outcome.Failure<>(FailureKind.INTERNAL, "the HTTP client has been closed");
                },
                unused(),
                unused(),
                unused(),
                unused(),
                unused(),
                unused(),
                unused(),
                unused(),
                unused(),
                unused(),
                unused(),
                payload -> {
                    throw new AssertionError("no body is read");
                },
                List.of());
        holder.set(client);

        Outcome.Failure<WebSearchResponse> outcome =
                (Outcome.Failure<WebSearchResponse>) client.webSearch(WebSearchRequest.builder("q").build());

        assertEquals(FailureKind.TRANSPORT, outcome.kind());
        assertEquals("the client is closed", outcome.diagnostic());
    }

    /** A client over the given stream opener and owned resources; every other endpoint is an unused stub. */
    private static BraveSearchClient facade(
            Function<AnswersRequest, Outcome<AnswersStreamHandle>> streamOpener, List<AutoCloseable> owned) {
        return new BraveSearchClient(
                unused(),
                unused(),
                unused(),
                unused(),
                unused(),
                unused(),
                unused(),
                unused(),
                unused(),
                unused(),
                unused(),
                streamOpener,
                payload -> {
                    throw new AssertionError("no body is read by these tests");
                },
                owned);
    }

    private static <Req, Res> Function<Req, Outcome<Res>> unused() {
        return request -> {
            throw new AssertionError("this endpoint is not called by these tests");
        };
    }

    /** The smallest live handle: only its release matters here. */
    private record StubHandle(Runnable release) implements AnswersStreamHandle {

        @Override
        public Flow.Publisher<PublicStreamFrame> frames() {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {}

                @Override
                public void cancel() {}
            });
        }

        @Override
        public StreamCancellation cancellation() {
            return new StreamCancellation() {
                @Override
                public void cancel() {}

                @Override
                public boolean cancelled() {
                    return false;
                }
            };
        }

        @Override
        public void close() {
            release.run();
        }
    }
}

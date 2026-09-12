package io.amscotti.bravesearch.api.internal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.api.AnswersStreamHandle;
import io.amscotti.bravesearch.api.BraveSearchClient;
import io.amscotti.bravesearch.api.PublicStreamFrame;
import io.amscotti.bravesearch.application.exchange.InvalidOriginException;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.request.AnswersRequest;
import io.amscotti.bravesearch.domain.request.WebSearchRequest;
import io.amscotti.bravesearch.testsupport.ScriptedSseServer;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The library composition root end to end: the assembled client runs whole exchanges
 * against a loopback origin using only the caller's explicit token supplier — the
 * supplied token rides the wire, exact-scale decimals survive into the caller-owned
 * upstream snapshot, the blocking and streaming answers surfaces work through the same
 * composition, a supplier that yields nothing is a local-configuration failure, and
 * closing the client shuts its owned executor down while leaving a caller-supplied
 * executor alone.
 */
final class AssemblerTest {

    private static final long AWAIT_SECONDS = 10;

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void anAssembledClientRunsAWebSearchWithTheSuppliedTokenAndExactUpstreamDecimals() throws Exception {
        String token = "supplier-sentinel-" + UUID.randomUUID();
        byte[] body = ("{\"query\":{\"original\":\"bacon\"},\"web\":{\"results\":[{}],"
                        + "\"exact\":0.1000000000000000000001,\"trailing\":1.10}}")
                .getBytes(UTF_8);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .writeBytes(body)
                .start()) {

            try (BraveSearchClient client = loopbackClient(server, fixedToken(token))) {
                Outcome<io.amscotti.bravesearch.api.WebSearchResponse> outcome =
                        client.webSearch(WebSearchRequest.builder("bacon").build());

                io.amscotti.bravesearch.api.WebSearchResponse response = valueOf(outcome);
                assertEquals(200, response.httpStatus());
                assertEquals(
                        new BigDecimal("0.1000000000000000000001"),
                        response.upstream().path("web").path("exact").decimalValue(),
                        "an exact-scale decimal survives the whole library path");
                assertEquals(new BigDecimal("1.10"), response.upstream().path("web").path("trailing").decimalValue());
                assertEquals(
                        token,
                        server.requests().getFirst().firstHeader("X-Subscription-Token").orElseThrow(),
                        "the wire carries exactly the caller's supplied token");
            }
        }
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aTokenSupplierThatYieldsNothingIsALocalConfigurationFailure() throws Exception {
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .writeBytes("{}".getBytes(UTF_8))
                .start()) {

            try (BraveSearchClient client = loopbackClient(server, () -> null)) {
                Outcome<io.amscotti.bravesearch.api.WebSearchResponse> outcome =
                        client.webSearch(WebSearchRequest.builder("bacon").build());

                assertEquals(
                        FailureKind.LOCAL_CONFIG,
                        ((Outcome.Failure<io.amscotti.bravesearch.api.WebSearchResponse>) outcome).kind(),
                        "no environment is ever consulted; the explicit supplier is the only source");
                assertEquals(0, server.requests().size(), "no exchange ever opened");
            }
        }
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aSuppliedTokenNoHeaderValueCanCarryIsALocalConfigurationFailure() throws Exception {
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .writeBytes("{}".getBytes(UTF_8))
                .start()) {

            try (BraveSearchClient client = loopbackClient(server, fixedToken("ключ-\uD83E\uDDEA-token"))) {
                Outcome<io.amscotti.bravesearch.api.WebSearchResponse> outcome =
                        client.webSearch(WebSearchRequest.builder("bacon").build());

                Outcome.Failure<io.amscotti.bravesearch.api.WebSearchResponse> failure =
                        (Outcome.Failure<io.amscotti.bravesearch.api.WebSearchResponse>) outcome;
                assertEquals(
                        FailureKind.LOCAL_CONFIG,
                        failure.kind(),
                        "a token that cannot travel in the auth header is the caller's local configuration");
                assertFalse(failure.diagnostic().contains("ключ"), "the failure never echoes token material");
                assertEquals(0, server.requests().size(), "no exchange ever opened");
            }
        }
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void theBlockingAnswersCallRoundTripsThroughTheAssembledClient() throws Exception {
        byte[] body = "{\"choices\":[{\"message\":{\"content\":\"crisp\"}}]}".getBytes(UTF_8);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .writeBytes(body)
                .start()) {

            try (BraveSearchClient client = loopbackClient(server, fixedToken("answers-token-1"))) {
                Outcome<io.amscotti.bravesearch.api.AnswersResponse> outcome = client.answers(
                        AnswersRequest.builder("bacon").userId("agent-7").build());

                io.amscotti.bravesearch.api.AnswersResponse response = valueOf(outcome);
                assertEquals(200, response.httpStatus());
                assertEquals(
                        "crisp",
                        response.upstream()
                                .path("choices")
                                .path(0)
                                .path("message")
                                .path("content")
                                .stringValue());
                assertEquals("POST", server.requests().getFirst().method());
                assertTrue(
                        new String(server.requests().getFirst().body(), UTF_8).contains("\"user_id\":\"agent-7\""),
                        "the metadata user_id rider reached the wire");
            }
        }
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void theStreamingAnswersCallDeliversProjectedFramesAndClosesCleanly() throws Exception {
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "text/event-stream")
                .writeBytes(data("{\"choices\":[{\"delta\":{\"content\":\"Hello \"}}]}"))
                .writeBytes(data("{\"choices\":[{\"delta\":{\"content\":\"world\"}}]}"))
                .writeBytes(data("[DONE]"))
                .start()) {

            try (BraveSearchClient client = loopbackClient(server, fixedToken("stream-token-1"))) {
                Outcome<AnswersStreamHandle> opened = client.answersStream(
                        AnswersRequest.builder("bacon").stream(true).build());
                AnswersStreamHandle handle = valueOf(opened);

                FrameCollector frames = new FrameCollector();
                handle.frames().subscribe(frames);
                frames.awaitCompletion();

                assertEquals(
                        List.of(
                                new PublicStreamFrame.Text("Hello "),
                                new PublicStreamFrame.Text("world")),
                        frames.received,
                        "the public frames are exactly the projected answer text");
                assertTrue(frames.completed, "the stream completed normally");

                handle.close();
            }
        }
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void closingTheClientLeavesACallerSuppliedExecutorRunningAndShutsTheOwnedOneDown() throws Exception {
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .writeBytes("{}".getBytes(UTF_8))
                .start()) {

            ExecutorService callerOwned = Executors.newSingleThreadExecutor();
            try {
                BraveSearchClient callerExecutorClient =
                        loopbackClient(server, fixedToken("executor-token-1"), callerOwned);
                callerExecutorClient.webSearch(WebSearchRequest.builder("bacon").build());
                callerExecutorClient.close();

                assertFalse(callerOwned.isShutdown(), "a caller-supplied executor stays caller-owned");
            } finally {
                callerOwned.shutdownNow();
            }

            BraveSearchClient ownedExecutorClient = loopbackClient(server, fixedToken("executor-token-2"));
            ownedExecutorClient.webSearch(WebSearchRequest.builder("bacon").build());
            assertTrue(
                    ownedExecutorThreadsEventuallyAppear(),
                    "the client-owned executor runs its named worker threads");
            ownedExecutorClient.close();
            assertTrue(
                    ownedExecutorThreadsEventuallyVanish(),
                    "closing the client shuts its owned executor down");
        }
    }

    @Test
    void anOriginOutsideTheLoopbackContractIsRejectedAtBuild() {
        assertEquals(
                InvalidOriginException.class,
                assertThrows(
                                InvalidOriginException.class,
                                () -> BraveSearchClient.builder()
                                        .tokenSupplier(fixedToken("any-token-1"))
                                        .baseUrl("https://api.example.test")
                                        .build())
                        .getClass(),
                "the loopback-only override rule holds on the library path too");
    }

    private static BraveSearchClient loopbackClient(ScriptedSseServer server, java.util.function.Supplier<Credential> token) {
        return loopbackClient(server, token, null);
    }

    private static BraveSearchClient loopbackClient(
            ScriptedSseServer server, java.util.function.Supplier<Credential> token, ExecutorService executor) {
        BraveSearchClient.Builder builder = BraveSearchClient.builder()
                .tokenSupplier(token)
                .baseUrl(server.baseUrl().toString());
        if (executor != null) {
            builder.executor(executor);
        }
        return builder.build();
    }

    private static java.util.function.Supplier<Credential> fixedToken(String token) {
        return () -> Credential.of(token.getBytes(UTF_8));
    }

    private static byte[] data(String json) {
        return ("data: " + json + "\n\n").getBytes(UTF_8);
    }

    private static <T> T valueOf(Outcome<T> outcome) {
        return switch (outcome) {
            case Outcome.Success<T> success -> success.value();
            case Outcome.Failure<T> failure -> throw new AssertionError(
                    "expected success, got " + failure.kind() + ": " + failure.diagnostic());
        };
    }

    private static boolean ownedExecutorThreadsEventuallyAppear() throws InterruptedException {
        return spinUntil(() -> Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.getName().startsWith("brave-search-client-")));
    }

    private static boolean ownedExecutorThreadsEventuallyVanish() throws InterruptedException {
        return spinUntil(() -> Thread.getAllStackTraces().keySet().stream()
                .noneMatch(thread -> thread.getName().startsWith("brave-search-client-")));
    }

    private static boolean spinUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    private static final class FrameCollector implements Flow.Subscriber<PublicStreamFrame> {

        final List<PublicStreamFrame> received = new ArrayList<>();

        final CountDownLatch finished = new CountDownLatch(1);

        private Flow.Subscription subscription;

        private volatile boolean completed;

        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(PublicStreamFrame frame) {
            received.add(frame);
        }

        @Override
        public void onError(Throwable throwable) {
            failure.set(throwable);
            finished.countDown();
        }

        @Override
        public void onComplete() {
            completed = true;
            finished.countDown();
        }

        void awaitCompletion() throws InterruptedException {
            assertTrue(finished.await(AWAIT_SECONDS, TimeUnit.SECONDS), "the stream reached its terminal signal");
            AssertionError failureView = new AssertionError("expected no stream failure");
            if (failure.get() != null) {
                failureView.initCause(failure.get());
                throw failureView;
            }
        }
    }
}

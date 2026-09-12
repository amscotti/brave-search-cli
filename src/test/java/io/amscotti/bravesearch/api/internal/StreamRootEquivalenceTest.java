package io.amscotti.bravesearch.api.internal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.port.out.AnswersStreamDispatch;
import io.amscotti.bravesearch.application.port.out.AnswersStreamExchange;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.ResolvedCredential;
import io.amscotti.bravesearch.bootstrap.AnswersComposition;
import io.amscotti.bravesearch.bootstrap.ExchangeRegistry;
import io.amscotti.bravesearch.domain.answer.AnswerStreamEvent;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.request.AnswersRequest;
import io.amscotti.bravesearch.testsupport.ScriptedSseServer;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Root equivalence of the streaming decode: the CLI composition root ({@code bootstrap}) and
 * the library composition root ({@code api.internal}) both install the one standard semantic
 * stream wiring, so the same served bytes must decode into the same event sequence through
 * either root. The test drives each root's own open path — the CLI's stream port over the
 * process cancellation registry, the library's assembled stream opener over the client
 * registry — against two servers replaying one identical script, and compares the decoded
 * events; a root that re-configured parser, decoder, or processor bounds on its own would
 * drift visibly here.
 */
final class StreamRootEquivalenceTest {

    private static final Duration CONNECT_BUDGET = Duration.ofSeconds(5);
    private static final String SENTINEL = "equivalence-" + UUID.randomUUID();

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void bothCompositionRootsDecodeTheSameScriptedStreamIdentically() throws Exception {
        try (ScriptedSseServer cliServer = mixedEventStream();
                ScriptedSseServer libraryServer = mixedEventStream()) {
            List<AnswerStreamEvent> throughCli = eventsThroughTheCliRoot(cliServer);
            List<AnswerStreamEvent> throughLibrary = eventsThroughTheLibraryRoot(libraryServer);

            assertEquals(
                    List.of(
                            new AnswerStreamEvent.Passthrough("role"),
                            new AnswerStreamEvent.Text("Hel"),
                            new AnswerStreamEvent.Text("lo "),
                            new AnswerStreamEvent.Tagged(
                                    "citation", "{\"url\":\"https://example.com/evidence\"}"),
                            new AnswerStreamEvent.UnknownTag("weather", "{}"),
                            new AnswerStreamEvent.Passthrough("finish_reason")),
                    throughCli,
                    "the CLI root decodes the scripted stream exactly");
            assertEquals(throughCli, throughLibrary, "both roots decode the same bytes identically");
        }
    }

    private List<AnswerStreamEvent> eventsThroughTheCliRoot(ScriptedSseServer server) throws Exception {
        AnswersComposition composition =
                AnswersComposition.process(unusedCredential(), new ExchangeRegistry());
        Outcome<AnswersStreamExchange> outcome =
                composition.streams().stream(dispatch(server, streamedRequest()));
        return switch (outcome) {
            case Outcome.Success<AnswersStreamExchange> opened -> collected(opened.value());
            case Outcome.Failure<AnswersStreamExchange> failure ->
                    throw new AssertionError("the CLI root failed to open: " + failure.kind() + ": "
                            + failure.diagnostic());
        };
    }

    private List<AnswerStreamEvent> eventsThroughTheLibraryRoot(ScriptedSseServer server) throws Exception {
        Assembler.ClientWiring wiring = Assembler.assemble(
                () -> Credential.of(SENTINEL.getBytes(UTF_8)),
                CONNECT_BUDGET,
                Duration.ofSeconds(30),
                null,
                server.baseUrl().toString());
        try {
            Outcome<OpenedAnswersStream> outcome = wiring.answersStream().apply(streamedRequest());
            return switch (outcome) {
                case Outcome.Success<OpenedAnswersStream> opened -> {
                    Collecting subscriber = new Collecting();
                    opened.value().semanticFrames().subscribe(subscriber);
                    subscriber.requestUnbounded();
                    assertTrue(subscriber.terminal.await(10, TimeUnit.SECONDS), "the library stream completes");
                    yield subscriber.events;
                }
                case Outcome.Failure<OpenedAnswersStream> failure ->
                        throw new AssertionError("the library root failed to open: " + failure.kind() + ": "
                                + failure.diagnostic());
            };
        } finally {
            for (AutoCloseable owned : wiring.ownedResources()) {
                owned.close();
            }
        }
    }

    private List<AnswerStreamEvent> collected(AnswersStreamExchange exchange) throws Exception {
        try {
            Collecting subscriber = new Collecting();
            exchange.semanticFrames().subscribe(subscriber);
            subscriber.requestUnbounded();
            assertTrue(subscriber.terminal.await(10, TimeUnit.SECONDS), "the CLI stream completes");
            return subscriber.events;
        } finally {
            exchange.close();
        }
    }

    private static AnswersRequest streamedRequest() {
        return AnswersRequest.builder("q").stream(true).build();
    }

    private static AnswersStreamDispatch dispatch(ScriptedSseServer server, AnswersRequest request) {
        return new AnswersStreamDispatch(
                request,
                BraveApiOrigin.fromOverride(
                        server.baseUrl().toString(), host -> { throw new AssertionError("literals are never resolved"); }),
                Credential.of(SENTINEL.getBytes(UTF_8)),
                CONNECT_BUDGET,
                null);
    }

    /** A stored-credential source the streaming path never consults: the dispatch carries its own. */
    private static CredentialProvider unusedCredential() {
        return new CredentialProvider() {
            @Override
            public Credential resolve() {
                return Credential.of(SENTINEL.getBytes(UTF_8));
            }

            @Override
            public ResolvedCredential resolveWithProvenance() {
                throw new UnsupportedOperationException("no provenance consumer exists on this path");
            }
        };
    }

    /** One scripted stream exercising every semantic shape: passthrough, text, both tag kinds. */
    private static ScriptedSseServer mixedEventStream() throws Exception {
        return ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "text/event-stream")
                .writeBytes(event("{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}"))
                .writeBytes(event("{\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}"))
                .writeBytes(event(
                        "{\"choices\":[{\"delta\":{\"content\":\"lo <citation>{\\\"url\\\":\\\"https://example.com/evidence\\\"}</citation>\"}}]}"))
                .writeBytes(event("{\"choices\":[{\"delta\":{\"content\":\"<weather>{}</weather>\"}}]}"))
                .writeBytes(event("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}"))
                .writeBytes("data: [DONE]\n\n".getBytes(UTF_8))
                .start();
    }

    private static byte[] event(String data) {
        return ("data: " + data + "\n\n").getBytes(UTF_8);
    }

    /** Collects every delivered event and counts down once the stream reaches its terminal. */
    private static final class Collecting implements Flow.Subscriber<AnswerStreamEvent> {

        private final List<AnswerStreamEvent> events = new CopyOnWriteArrayList<>();

        private final CountDownLatch terminal = new CountDownLatch(1);

        private Flow.Subscription subscription;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
        }

        void requestUnbounded() {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(AnswerStreamEvent event) {
            events.add(event);
        }

        @Override
        public void onError(Throwable thrown) {
            terminal.countDown();
        }

        @Override
        public void onComplete() {
            terminal.countDown();
        }
    }
}

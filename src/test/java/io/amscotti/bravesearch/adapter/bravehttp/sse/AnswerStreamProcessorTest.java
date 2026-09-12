package io.amscotti.bravesearch.adapter.bravehttp.sse;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.bravehttp.json.AnswerTagDecoder;
import io.amscotti.bravesearch.application.stream.AbruptEofException;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.SseException;
import io.amscotti.bravesearch.application.stream.StreamBodyPublisher;
import io.amscotti.bravesearch.domain.answer.AnswerDecodeException;
import io.amscotti.bravesearch.domain.answer.AnswerStreamEvent;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Reactive contracts of the semantic layer: one subscriber-side decoder standing between the
 * raw decoded-byte publisher and a semantic consumer. The processor re-emits decoded events
 * honoring its own subscriber's demand — one upstream pull at a time, so raw reads never run
 * ahead of semantic demand — converts the transport's typed endings into terminal semantics,
 * and never invokes a subscriber callback concurrently.
 */
final class AnswerStreamProcessorTest {

    private static final long ROOMY_LINE = 64 * 1024;

    private static final long ROOMY_EVENT = 1024 * 1024;

    private static final long ROOMY_TAG = 1024 * 1024;

    @Test
    void semanticEventsDecodeFromRawChunksInOrder() {
        ManualSource source = new ManualSource();
        CancellationContext cancellation = new CancellationContext();
        AnswerStreamProcessor processor = processor(source, cancellation, ROOMY_EVENT);
        Collecting subscriber = new Collecting();
        processor.subscribe(subscriber);
        subscriber.requestUnbounded();

        source.emit(sse("{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}"));
        source.emit(sse("{\"choices\":[{\"delta\":{\"content\":\"An independent \"}}]}"));
        source.emit(sse("{\"choices\":[{\"delta\":{\"content\":\"<citation>{\\\"url\\\":\\\"https://example.com\\\"}</citation>index.\"}}]}"));
        source.emit(sse("{\"choices\":[{\"delta\":{\"content\":\"<future>{\\\"x\\\":1}</future>\"}}]}"));
        source.emit(sse("[DONE]"));
        source.complete();

        assertEquals(
                List.of(
                        new AnswerStreamEvent.Passthrough("role"),
                        new AnswerStreamEvent.Text("An independent "),
                        new AnswerStreamEvent.Tagged("citation", "{\"url\":\"https://example.com\"}"),
                        new AnswerStreamEvent.Text("index."),
                        new AnswerStreamEvent.UnknownTag("future", "{\"x\":1}")),
                subscriber.events,
                "the semantic layer decodes passthroughs, text, documented tags, and unknown tags in order");
        assertTrue(subscriber.terminalLatch.getCount() == 0, "the stream reached its terminal completion");
        assertNull(subscriber.error, "no failure surfaces on a well-formed ending");
        assertTrue(cancellation.cause().isEmpty(), "a well-formed ending latches no failure cause");
        assertTrue(processor.lastSemanticProgress() != null, "semantic progress was recorded");
    }

    @Test
    void unknownTagsCarryTheSseEnvelopeTheyArrivedIn() {
        ManualSource source = new ManualSource();
        CancellationContext cancellation = new CancellationContext();
        AnswerStreamProcessor processor = processor(source, cancellation, ROOMY_EVENT);
        Collecting subscriber = new Collecting();
        processor.subscribe(subscriber);
        subscriber.requestUnbounded();

        source.emit(
                ("id: evt-9\nretry: 500\nevent: weather-alert\n"
                        + "data: {\"choices\":[{\"delta\":{\"content\":\"<weather>{}</weather>\"}}]}\n\n")
                        .getBytes(UTF_8));
        source.emit(sse("{\"choices\":[{\"delta\":{\"content\":\"tail\"}}]}"));
        source.emit(sse("[DONE]"));
        source.complete();

        assertEquals(
                List.of(
                        new AnswerStreamEvent.UnknownTag("weather", "{}", "weather-alert", "evt-9", 500L),
                        new AnswerStreamEvent.Text("tail")),
                subscriber.events,
                "an unknown tag preserves the event name and the id and retry of its SSE block");
        assertNull(subscriber.error, "a well-formed envelope-carrying block is no failure");
    }

    @Test
    void zeroDownstreamDemandRequestsNothingUpstream() {
        ManualSource source = new ManualSource();
        AnswerStreamProcessor processor = processor(source, new CancellationContext(), ROOMY_EVENT);
        Collecting subscriber = new Collecting();
        processor.subscribe(subscriber);

        // the source is synchronous, so subscribe already ran every callback the wiring makes:
        // the counting probe reads zero deterministically, with no sleep to give a hidden
        // request time to appear
        assertEquals(0, source.requested.get(), "no raw read may happen while semantic demand is zero");
        assertTrue(subscriber.events.isEmpty(), "nothing is delivered without demand");

        // the probe's positive control: the same wiring pulls one window the moment demand
        // exists, proving the counter counts and the zero above is a measured fact
        subscriber.subscription.request(1);
        assertEquals(
                StreamBodyPublisher.CHUNK_LIMIT,
                source.requested.get(),
                "the first unit of semantic demand pulls exactly one window-sized raw read");
    }

    @Test
    void upstreamPullsAreGatedBySemanticDemandThroughBothLayers() {
        ManualSource source = new ManualSource();
        AnswerStreamProcessor processor = processor(source, new CancellationContext(), ROOMY_EVENT);
        Collecting subscriber = new Collecting();
        processor.subscribe(subscriber);

        subscriber.subscription.request(1);
        assertEquals(
                StreamBodyPublisher.CHUNK_LIMIT,
                source.requested.get(),
                "one unit of semantic demand pulls exactly one window-sized raw read");

        source.emit(
                sse(
                        "{\"choices\":[{\"delta\":{\"content\":\"A\"}}]}", // two events in one chunk
                        "{\"choices\":[{\"delta\":{\"content\":\"B\"}}]}"));
        assertEquals(
                List.of(new AnswerStreamEvent.Text("A")),
                subscriber.events,
                "only the first decoded event is delivered against the single unit of demand");
        assertEquals(
                StreamBodyPublisher.CHUNK_LIMIT,
                source.requested.get(),
                "a buffered overflow event holds the next raw pull until demand returns");

        subscriber.subscription.request(1);
        assertEquals(
                List.of(new AnswerStreamEvent.Text("A"), new AnswerStreamEvent.Text("B")),
                subscriber.events,
                "the buffered event is delivered from the held chunk without a new raw pull");
        assertEquals(
                StreamBodyPublisher.CHUNK_LIMIT,
                source.requested.get(),
                "satisfying buffered demand still pulls nothing new upstream");

        subscriber.subscription.request(1);
        assertEquals(
                StreamBodyPublisher.CHUNK_LIMIT * 2,
                source.requested.get(),
                "a drained buffer with live demand pulls the next window-sized raw read");
    }

    @Test
    void semanticPullsStayOneChunkAheadOfSemanticDemand() {
        ManualSource source = new ManualSource();
        AnswerStreamProcessor processor = processor(source, new CancellationContext(), ROOMY_EVENT);
        Collecting subscriber = new Collecting();
        processor.subscribe(subscriber);

        subscriber.subscription.request(1);
        assertEquals(StreamBodyPublisher.CHUNK_LIMIT, source.requested.get(), "the first pull is one chunk");

        source.emit(sse("{\"choices\":[{\"delta\":{\"content\":\"A\"}}]}", "[DONE]"));
        source.complete();
        assertEquals(List.of(new AnswerStreamEvent.Text("A")), subscriber.events);

        long pulled = source.requested.get();
        subscriber.subscription.request(1);
        assertEquals(
                pulled,
                source.requested.get(),
                "a terminal already buffered pulls nothing further, however much demand arrives");
    }

    @Test
    void terminalCompletionWaitsUntilBufferedEventsAreTaken() {
        ManualSource source = new ManualSource();
        AnswerStreamProcessor processor = processor(source, new CancellationContext(), ROOMY_EVENT);
        Collecting subscriber = new Collecting();
        processor.subscribe(subscriber);
        subscriber.subscription.request(1);

        source.emit(
                sse(
                        "{\"choices\":[{\"delta\":{\"content\":\"A\"}}]}",
                        "{\"choices\":[{\"delta\":{\"content\":\"B\"}}]}",
                        "[DONE]"));
        source.complete();

        assertTrue(subscriber.terminalLatch.getCount() > 0, "completion holds while a decoded event is untaken");
        subscriber.subscription.request(1);
        assertTrue(subscriber.terminalLatch.getCount() == 0, "completion lands once the buffer drains");
        assertEquals(
                List.of(new AnswerStreamEvent.Text("A"), new AnswerStreamEvent.Text("B")),
                subscriber.events);
        assertNull(subscriber.error);
    }

    @Test
    void anIncompleteEndingFailsTypedWhenNoUsageWasObserved() {
        ManualSource source = new ManualSource();
        AnswerStreamProcessor processor = processor(source, new CancellationContext(), ROOMY_EVENT);
        Collecting subscriber = new Collecting();
        processor.subscribe(subscriber);
        subscriber.requestUnbounded();

        source.emit(sse("{\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}"));
        source.complete();

        assertInstanceOf(SseException.Incomplete.class, subscriber.error, "the ending is the typed incomplete signal");
        assertTrue(subscriber.terminalLatch.getCount() == 0);
    }

    @Test
    void anObservedUsageTagDowngradesAnIncompleteEndingToCompletion() {
        ManualSource source = new ManualSource();
        AnswerStreamProcessor processor = processor(source, new CancellationContext(), ROOMY_EVENT);
        Collecting subscriber = new Collecting();
        processor.subscribe(subscriber);
        subscriber.requestUnbounded();

        source.emit(sse("{\"choices\":[{\"delta\":{\"content\":\"done<usage>{\\\"requests\\\":1}</usage>\"}}]}"));
        source.complete();

        assertNull(subscriber.error, "an observed usage tag is a documented terminal condition");
        assertTrue(subscriber.terminalLatch.getCount() == 0);
        assertEquals(
                List.of(
                        new AnswerStreamEvent.Text("done"),
                        new AnswerStreamEvent.Tagged("usage", "{\"requests\":1}")),
                subscriber.events);
    }

    @Test
    void aTagLeftOpenAtTheEndingFailsTyped() {
        ManualSource source = new ManualSource();
        CancellationContext cancellation = new CancellationContext();
        AnswerStreamProcessor processor = processor(source, cancellation, ROOMY_EVENT);
        Collecting subscriber = new Collecting();
        processor.subscribe(subscriber);
        subscriber.requestUnbounded();

        source.emit(sse("{\"choices\":[{\"delta\":{\"content\":\"<citation>{\\\"url\\\":\\\"https://e.com\\\"}\"}}]}"));
        source.emit(sse("[DONE]"));
        source.complete();

        assertInstanceOf(AnswerDecodeException.UnterminatedTag.class, subscriber.error);
        assertEquals(
                CancellationContext.Cause.SUBSCRIBER_FAILURE,
                cancellation.cause().orElseThrow(),
                "a semantic decode failure latches the cancel signal that severs the body");
        assertTrue(source.cancelled, "the upstream subscription is cancelled on the typed failure");
    }

    @Test
    void theFirstDecodeFailureSurfacesWhenALaterEventSharesItsChunk() {
        ManualSource source = new ManualSource();
        CancellationContext cancellation = new CancellationContext();
        AnswerStreamProcessor processor = processor(source, cancellation, ROOMY_EVENT);
        Collecting subscriber = new Collecting();
        processor.subscribe(subscriber);
        subscriber.requestUnbounded();

        source.emit(
                sse(
                        "{\"choices\":[{\"delta\":{\"content\":\"<usage>{not json}</usage>\"}}]}",
                        "{\"choices\":[{\"delta\":{\"content\":\"after\"}}]}"));

        assertInstanceOf(
                AnswerDecodeException.MalformedTagPayload.class,
                subscriber.error,
                "the stashed decode failure surfaces, never the finished decoder's rejection of the"
                        + " next event in the same chunk");
        assertEquals(
                CancellationContext.Cause.SUBSCRIBER_FAILURE,
                cancellation.cause().orElseThrow(),
                "a semantic decode failure latches the cancel signal that severs the body");
        assertTrue(source.cancelled, "the typed decode failure cancels the raw subscription");
        assertEquals(1, subscriber.terminals.get(), "the failure is delivered exactly once");
    }

    @Test
    void aStashedDecodeFailureOutranksATransportBreachBehindItInTheSameChunk() {
        ManualSource source = new ManualSource();
        CancellationContext cancellation = new CancellationContext();
        AnswerStreamProcessor processor = processor(source, cancellation, ROOMY_EVENT);
        Collecting subscriber = new Collecting();
        processor.subscribe(subscriber);
        subscriber.requestUnbounded();

        java.io.ByteArrayOutputStream chunk = new java.io.ByteArrayOutputStream();
        chunk.writeBytes(sse("{\"choices\":[{\"delta\":{\"content\":\"<usage>{not json}</usage>\"}}]}"));
        StringBuilder oversizedLine = new StringBuilder("data: ");
        for (int i = 0; i <= ROOMY_LINE; i++) {
            oversizedLine.append('x');
        }
        chunk.writeBytes((oversizedLine + "\n\n").getBytes(UTF_8));

        source.emit(chunk.toByteArray());

        assertInstanceOf(
                AnswerDecodeException.MalformedTagPayload.class,
                subscriber.error,
                "the earlier decode failure wins over the line breach that follows it in the same chunk");
        assertEquals(1, subscriber.terminals.get(), "the failure is delivered exactly once");
    }

    @Test
    void anOverflowLatchesCancelsAndFailsExactlyOnce() {
        ManualSource source = new ManualSource();
        CancellationContext cancellation = new CancellationContext();
        AnswerStreamProcessor processor = processor(source, cancellation, 64);
        Collecting subscriber = new Collecting();
        processor.subscribe(subscriber);
        subscriber.requestUnbounded();

        source.emit(sse("{\"choices\":[{\"delta\":{\"content\":\"a text run far beyond the tiny event ceiling\"}}]}"));

        assertInstanceOf(SseException.Overflow.class, subscriber.error);
        assertEquals(CancellationContext.Cause.SUBSCRIBER_FAILURE, cancellation.cause().orElseThrow());
        assertTrue(source.cancelled, "the overflow cancels the raw subscription");
        assertEquals(1, subscriber.terminals.get(), "the failure is delivered exactly once");
    }

    @Test
    void anUpstreamIoBreakSurfacesAsAbruptEofAndLatchesTransportFailure() {
        ManualSource source = new ManualSource();
        CancellationContext cancellation = new CancellationContext();
        AnswerStreamProcessor processor = processor(source, cancellation, ROOMY_EVENT);
        Collecting subscriber = new Collecting();
        processor.subscribe(subscriber);
        subscriber.requestUnbounded();

        source.emit(sse("{\"choices\":[{\"delta\":{\"content\":\"mid\"}}]}"));
        source.error(new IOException("peer-specific message text that must not travel"));

        AbruptEofException abrupt = assertInstanceOf(AbruptEofException.class, subscriber.error);
        assertTrue(
                !abrupt.getMessage().contains("peer-specific"),
                "the abrupt-ending diagnostic is content-free");
        assertEquals(CancellationContext.Cause.TRANSPORT_FAILURE, cancellation.cause().orElseThrow());
    }

    @Test
    void aThrowingSubscriberCallbackCancelsUpstreamAndFailsExactlyOnce() {
        ManualSource source = new ManualSource();
        CancellationContext cancellation = new CancellationContext();
        AnswerStreamProcessor processor = processor(source, cancellation, ROOMY_EVENT);
        Collecting subscriber = new Collecting();
        subscriber.throwOnSecondEvent = new IllegalStateException("subscriber broke");
        processor.subscribe(subscriber);
        subscriber.requestUnbounded();

        source.emit(sse("{\"choices\":[{\"delta\":{\"content\":\"A\"}}]}"));
        source.emit(sse("{\"choices\":[{\"delta\":{\"content\":\"B\"}}]}"));

        assertEquals(1, subscriber.terminals.get(), "a broken subscriber hears exactly one terminal signal");
        assertTrue(source.cancelled, "the raw subscription is cancelled after the subscriber broke");
        assertEquals(CancellationContext.Cause.SUBSCRIBER_FAILURE, cancellation.cause().orElseThrow());
    }

    @Test
    void subscriberCallbacksNeverRunConcurrently() throws Exception {
        ManualSource source = new ManualSource();
        AnswerStreamProcessor processor = processor(source, new CancellationContext(), ROOMY_EVENT);
        CountDownLatch hammering = new CountDownLatch(1);
        Collecting subscriber = new Collecting();
        subscriber.slowCallbacks = true;
        processor.subscribe(subscriber);
        Thread requester = new Thread(() -> {
            while (hammering.getCount() > 0) {
                subscriber.subscription.request(1);
            }
        });
        requester.start();
        for (int i = 0; i < 20; i++) {
            source.emit(sse("{\"choices\":[{\"delta\":{\"content\":\"x\"}}]}"));
        }
        hammering.countDown();
        requester.join(5000);
        source.emit(sse("[DONE]"));
        source.complete();
        // the hammering thread granted an unknowable amount of credit; drain whatever it left
        subscriber.subscription.request(Long.MAX_VALUE);

        assertEquals(1, subscriber.maxConcurrentCallbacks.get(), "callbacks are strictly serialized");
        assertTrue(subscriber.terminalLatch.getCount() == 0);
    }

    @Test
    void aSecondSubscriberIsRefusedWhileTheFirstStaysLive() {
        ManualSource source = new ManualSource();
        AnswerStreamProcessor processor = processor(source, new CancellationContext(), ROOMY_EVENT);
        Collecting first = new Collecting();
        Collecting second = new Collecting();
        processor.subscribe(first);
        first.requestUnbounded();

        processor.subscribe(second);

        assertInstanceOf(
                IllegalStateException.class,
                second.error,
                "the duplicate is refused per the single-subscription rule");
        source.emit(sse("{\"choices\":[{\"delta\":{\"content\":\"A\"}}]}"));
        assertEquals(
                List.of(new AnswerStreamEvent.Text("A")),
                first.events,
                "the refused side never disturbs the live subscription");
    }

    @Test
    void downstreamCancelCancelsUpstreamAndStopsEveryCallback() {
        ManualSource source = new ManualSource();
        CancellationContext cancellation = new CancellationContext();
        AnswerStreamProcessor processor = processor(source, cancellation, ROOMY_EVENT);
        Collecting subscriber = new Collecting();
        processor.subscribe(subscriber);
        subscriber.requestUnbounded();
        source.emit(sse("{\"choices\":[{\"delta\":{\"content\":\"A\"}}]}"));

        subscriber.subscription.cancel();
        assertEquals(CancellationContext.Cause.CLOSED, cancellation.cause().orElseThrow());
        assertTrue(source.cancelled, "cancelling the semantic layer cancels the raw subscription");

        int eventsAfterCancel = subscriber.events.size();
        source.emit(sse("{\"choices\":[{\"delta\":{\"content\":\"B\"}}]}"));
        source.complete();
        assertEquals(eventsAfterCancel, subscriber.events.size(), "no callback runs after cancel");
        assertEquals(0, subscriber.terminals.get(), "cancel itself delivers no terminal signal");
    }

    @Test
    void aNegativeRequestFailsTheSubscriberPerFlowRules() {
        ManualSource source = new ManualSource();
        CancellationContext cancellation = new CancellationContext();
        AnswerStreamProcessor processor = processor(source, cancellation, ROOMY_EVENT);
        Collecting subscriber = new Collecting();
        processor.subscribe(subscriber);
        subscriber.requestUnbounded();

        subscriber.subscription.request(-1);

        assertInstanceOf(IllegalArgumentException.class, subscriber.error);
        assertTrue(source.cancelled);
        assertEquals(CancellationContext.Cause.SUBSCRIBER_FAILURE, cancellation.cause().orElseThrow());
    }

    @Test
    void dataAfterTheTerminalMarkerFailsTyped() {
        ManualSource source = new ManualSource();
        AnswerStreamProcessor processor = processor(source, new CancellationContext(), ROOMY_EVENT);
        Collecting subscriber = new Collecting();
        processor.subscribe(subscriber);
        subscriber.requestUnbounded();

        source.emit(sse("[DONE]"));
        source.emit(sse("{\"choices\":[{\"delta\":{\"content\":\"late\"}}]}"));

        assertInstanceOf(SseException.DataAfterTerminal.class, subscriber.error);
    }

    @Test
    void aPartialOpenerFlushesAsTextAtTheEnding() {
        ManualSource source = new ManualSource();
        AnswerStreamProcessor processor = processor(source, new CancellationContext(), ROOMY_EVENT);
        Collecting subscriber = new Collecting();
        processor.subscribe(subscriber);
        subscriber.requestUnbounded();

        source.emit(sse("{\"choices\":[{\"delta\":{\"content\":\"run<citat\"}}]}"));
        source.emit(sse("[DONE]"));
        source.complete();

        assertEquals(
                List.of(new AnswerStreamEvent.Text("run"), new AnswerStreamEvent.Text("<citat")),
                subscriber.events,
                "an opener that never completed flushes as ordinary text at the ending");
        assertNull(subscriber.error);
    }

    /** The armed processor as the composition root assembles it: parser, decoder, and wiring. */
    private static AnswerStreamProcessor processor(
            ManualSource source, CancellationContext cancellation, long maxEventBytes) {
        AnswerStreamProcessor processor =
                new AnswerStreamProcessor(source, cancellation, Clock.systemUTC());
        return processor.armedWith(
                new SseEventParser(processor::onDispatchedEvent, cancellation, ROOMY_LINE, maxEventBytes),
                new AnswerTagDecoder(cancellation, ROOMY_TAG));
    }

    /** The raw publisher under test: records demand and cancellation, emits on the caller's thread. */
    private static final class ManualSource implements Flow.Publisher<byte[]> {

        private final AtomicLong requested = new AtomicLong();
        private volatile boolean cancelled;
        private Flow.Subscriber<? super byte[]> subscriber;

        @Override
        public void subscribe(Flow.Subscriber<? super byte[]> newcomer) {
            newcomer.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    if (n > 0) {
                        requested.addAndGet(n);
                    }
                }

                @Override
                public void cancel() {
                    cancelled = true;
                }
            });
            subscriber = newcomer;
        }

        void emit(byte[] chunk) {
            Flow.Subscriber<? super byte[]> target = subscriber;
            if (target != null && !cancelled) {
                target.onNext(chunk);
            }
        }

        void error(Throwable failure) {
            subscriber.onError(failure);
        }

        void complete() {
            subscriber.onComplete();
        }
    }

    /** Semantic consumer under test: records events, terminals, and callback serialization. */
    private static final class Collecting implements Flow.Subscriber<AnswerStreamEvent> {

        private final List<AnswerStreamEvent> events = new CopyOnWriteArrayList<>();

        private final AtomicInteger terminals = new AtomicInteger();

        private final AtomicInteger inCallback = new AtomicInteger();

        private final AtomicInteger maxConcurrentCallbacks = new AtomicInteger();

        private final CountDownLatch terminalLatch = new CountDownLatch(1);

        private volatile Throwable error;

        private volatile Flow.Subscription subscription;

        private volatile boolean slowCallbacks;

        private volatile RuntimeException throwOnSecondEvent;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
        }

        void requestUnbounded() {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(AnswerStreamEvent event) {
            int inside = inCallback.incrementAndGet();
            maxConcurrentCallbacks.accumulateAndGet(inside, Math::max);
            try {
                if (slowCallbacks) {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (throwOnSecondEvent != null && events.size() == 1) {
                    throw throwOnSecondEvent;
                }
                events.add(event);
            } finally {
                inCallback.decrementAndGet();
            }
        }

        @Override
        public void onError(Throwable thrown) {
            terminals.incrementAndGet();
            error = thrown;
            terminalLatch.countDown();
        }

        @Override
        public void onComplete() {
            terminals.incrementAndGet();
            terminalLatch.countDown();
        }
    }

    /**
     * The wire bytes of one raw chunk carrying the given dispatched payloads back to back, so
     * multi-event chunks exercise the buffered split between one pull's decode and its demand.
     */
    private static byte[] sse(String... datas) {
        java.io.ByteArrayOutputStream chunk = new java.io.ByteArrayOutputStream();
        for (String data : datas) {
            chunk.writeBytes(("data: " + data + "\n\n").getBytes(UTF_8));
        }
        return chunk.toByteArray();
    }
}

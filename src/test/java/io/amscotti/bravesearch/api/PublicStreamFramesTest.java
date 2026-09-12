package io.amscotti.bravesearch.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.bravehttp.json.UpstreamJsonCodec;
import io.amscotti.bravesearch.domain.answer.AnswerDecodeException;
import io.amscotti.bravesearch.domain.answer.AnswerStreamEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

/**
 * The public stream frame projection: answer events arrive as plain public records — text,
 * documented tags with their exact payload text and its parsed tree, unknown tags with their
 * wire envelope — passthrough noise never reaches a subscriber, demand travels through
 * verbatim, a long noise run drains without recursion, the publisher accepts exactly one
 * subscription, a terminal source failure surfaces as the one public stream exception with its
 * stable failure category instead of an internal type, the terminal signal detaches the handle
 * from its owning client, and a subscriber arriving after the terminal signal receives it
 * immediately instead of hanging.
 */
final class PublicStreamFramesTest {

    private static final Function<String, JsonNode> PAYLOAD_READER = UpstreamJsonCodec::readTagPayload;

    @Test
    void answerEventsProjectToPlainPublicFrames() {
        DrivenSource source = new DrivenSource();
        CollectingSubscriber<PublicStreamFrame> subscriber = subscribe(source);

        source.emit(new AnswerStreamEvent.Text("Hel"));
        source.emit(new AnswerStreamEvent.Text("lo"));
        source.emit(new AnswerStreamEvent.Tagged("citation", "{\"url\":\"https://example.test\"}"));
        source.emit(new AnswerStreamEvent.UnknownTag("future", "<future>payload</future>", "future", "id-7", 2500L));
        source.emit(new AnswerStreamEvent.Passthrough("role"));
        source.projection().onComplete();

        assertEquals(
                List.of(
                        new PublicStreamFrame.Text("Hel"),
                        new PublicStreamFrame.Text("lo"),
                        new PublicStreamFrame.Tagged(
                                "citation",
                                "{\"url\":\"https://example.test\"}",
                                PAYLOAD_READER.apply("{\"url\":\"https://example.test\"}")),
                        new PublicStreamFrame.UnknownTag(
                                "future", "<future>payload</future>", "future", "id-7", 2500L)),
                subscriber.received,
                "every answer event projects to its public record; passthrough noise never arrives");
        assertTrue(subscriber.completed, "the terminal completion travels through");
        assertNull(subscriber.failed, "no failure was invented");
    }

    @Test
    void aTaggedFrameCarriesItsParsedPayloadTreeAndKeepsItsExactText() {
        DrivenSource source = new DrivenSource();
        CollectingSubscriber<PublicStreamFrame> subscriber = subscribe(source);

        source.emit(new AnswerStreamEvent.Tagged("entity", "{\"title\":\"Brave\",\"score\":0.5}"));
        source.projection().onComplete();

        PublicStreamFrame.Tagged tagged = (PublicStreamFrame.Tagged) subscriber.received.getFirst();
        assertEquals("{\"title\":\"Brave\",\"score\":0.5}", tagged.payload(), "the exact payload text is kept");
        assertEquals(
                "Brave",
                tagged.payloadTree().path("title").stringValue(),
                "the parsed tree rides along through the api-boundary codec");
    }

    @Test
    void aTaggedPayloadThatDoesNotParseYieldsAMissingTreeWhileTheExactTextSurvives() {
        DrivenSource source = new DrivenSource();
        CollectingSubscriber<PublicStreamFrame> subscriber = subscribe(source);

        source.emit(new AnswerStreamEvent.Tagged("citation", "{\"url\": \"https://example.test\""));
        source.projection().onComplete();

        PublicStreamFrame.Tagged tagged = (PublicStreamFrame.Tagged) subscriber.received.getFirst();
        assertEquals("{\"url\": \"https://example.test\"", tagged.payload(), "the exact payload text is kept");
        assertSame(
                MissingNode.getInstance(),
                tagged.payloadTree(),
                "an unparseable payload parses to empty — documented parse-or-empty");
    }

    @Test
    void demandTravelsThroughVerbatimAndNonPositiveRequestsFailTheSubscription() {
        DrivenSource source = new DrivenSource();
        CollectingSubscriber<PublicStreamFrame> subscriber = subscribe(source);

        subscriber.subscription().request(3);

        assertEquals(3, source.subscription.requested.get(), "request(n) forwards exactly n");

        subscriber.subscription().request(0);

        assertInstanceOf(IllegalArgumentException.class, subscriber.failed, "a non-positive request fails loudly");
        assertEquals(1, source.subscription.cancels.get(), "the failed subscription cancels upstream");
    }

    @Test
    void aPassthroughChunkSpendsItsPullOnATransparentRefill() {
        DrivenSource source = new DrivenSource();
        CollectingSubscriber<PublicStreamFrame> subscriber = subscribe(source);
        subscriber.subscription().request(1);

        source.emit(new AnswerStreamEvent.Passthrough("finish_reason"));

        assertTrue(subscriber.received.isEmpty(), "nothing was delivered for framing noise");
        assertEquals(
                2,
                source.subscription.requested.get(),
                "the noise consumed the outstanding pull and refilled once, transparently");

        source.emit(new AnswerStreamEvent.Text("answer"));

        assertEquals(List.of(new PublicStreamFrame.Text("answer")), subscriber.received, "the next real frame arrives");
    }

    @Test
    void aLongNoiseRunUnderOneDemandDrainsWithoutRecursion() {
        int noiseChunks = 10_000;
        BurstingSource source = new BurstingSource(noiseChunks);
        CollectingSubscriber<PublicStreamFrame> subscriber = new CollectingSubscriber<>();
        new PublicFramePublisher(source, PAYLOAD_READER, () -> {}).subscribe(subscriber);
        source.projection().onSubscribe(source.subscription);

        subscriber.subscription().request(1);
        source.finishWithAnswer();

        assertEquals(
                List.of(new PublicStreamFrame.Text("answer")),
                subscriber.received,
                "the whole noise run spent one demand and still delivered the real frame");
        assertEquals(
                noiseChunks + 1,
                source.subscription.requested.get(),
                "each noise chunk spent its pull on exactly one transparent refill");
        assertTrue(
                source.subscription.maxNestingDepth.get() <= 4,
                "transparent refills drain iteratively, not recursively: observed depth "
                        + source.subscription.maxNestingDepth.get());
    }

    @Test
    void thePublisherAcceptsExactlyOneSubscription() {
        DrivenSource source = new DrivenSource();
        PublicFramePublisher publisher = new PublicFramePublisher(source, PAYLOAD_READER, () -> {});
        CollectingSubscriber<PublicStreamFrame> first = new CollectingSubscriber<>();
        CollectingSubscriber<PublicStreamFrame> second = new CollectingSubscriber<>();
        publisher.subscribe(first);
        publisher.subscribe(second);

        assertEquals(1, source.subscriptions.get(), "the source saw exactly one subscription");
        assertInstanceOf(IllegalStateException.class, second.failed, "a second subscription is refused by contract");
        assertFalse(second.completed, "the refusal is terminal, not completion");
    }

    @Test
    void aSubscriberArrivingAfterAClosedStreamReceivesCompletionImmediately() {
        CountingDetach detach = new CountingDetach();
        PublicFramePublisher publisher = new PublicFramePublisher(new DrivenSource(), PAYLOAD_READER, detach);
        publisher.terminatedWithoutSubscription();
        assertEquals(1, detach.runs.get(), "a stream closed before subscription releases its client reference");

        CollectingSubscriber<PublicStreamFrame> late = new CollectingSubscriber<>();
        publisher.subscribe(late);

        assertTrue(late.completed, "the late subscriber receives the terminal signal immediately");
        assertTrue(late.received.isEmpty(), "a closed stream replays no frames");
        assertNull(late.failed, "a consumer-closed stream is a completion, not a failure");
    }

    @Test
    void aTerminalSourceFailureSurfacesAsTheOnePublicStreamExceptionWithItsKind() {
        DrivenSource source = new DrivenSource();
        CollectingSubscriber<PublicStreamFrame> subscriber = subscribe(source);

        source.projection().onError(new AnswerDecodeException.MalformedChunk("the chunk's delta content is not text"));

        AnswersStreamException failure = assertInstanceOf(AnswersStreamException.class, subscriber.failed);
        assertEquals("the chunk's delta content is not text", failure.getMessage(), "the diagnostic travels");
        assertNull(failure.getCause(), "the internal cause stays internal");
        assertSame(
                io.amscotti.bravesearch.domain.error.FailureKind.MALFORMED,
                failure.kind(),
                "the failure category is programmatically readable");
    }

    @Test
    void theTerminalSignalDetachesTheHandleFromItsOwningClient() {
        DrivenSource source = new DrivenSource();
        CountingDetach detach = new CountingDetach();
        CollectingSubscriber<PublicStreamFrame> subscriber = new CollectingSubscriber<>();
        new PublicFramePublisher(source, PAYLOAD_READER, detach).subscribe(subscriber);
        source.projection().onSubscribe(source.subscription);

        source.emit(new AnswerStreamEvent.Text("done"));
        source.projection().onComplete();

        assertTrue(subscriber.completed);
        assertEquals(1, detach.runs.get(), "a naturally completed stream releases its client reference");
    }

    @Test
    void cancellationCancelsTheSourceAndSilencesEverythingAfter() {
        DrivenSource source = new DrivenSource();
        CollectingSubscriber<PublicStreamFrame> subscriber = subscribe(source);

        subscriber.subscription().cancel();

        assertEquals(1, source.subscription.cancels.get(), "cancel maps to the source's cancellation");
        source.emit(new AnswerStreamEvent.Text("late"));
        source.projection().onComplete();
        assertTrue(subscriber.received.isEmpty(), "a cancelled subscription hears nothing more");
        assertFalse(subscriber.completed, "a cancelled subscription sees no terminal either");
    }

    private static CollectingSubscriber<PublicStreamFrame> subscribe(DrivenSource source) {
        CollectingSubscriber<PublicStreamFrame> subscriber = new CollectingSubscriber<>();
        new PublicFramePublisher(source, PAYLOAD_READER, () -> {}).subscribe(subscriber);
        source.projection().onSubscribe(source.subscription);
        return subscriber;
    }

    /** A fully manual synchronous source publisher the test drives event by event. */
    private static final class DrivenSource implements Flow.Publisher<AnswerStreamEvent> {

        private final AtomicReference<Flow.Subscriber<? super AnswerStreamEvent>> subscriber = new AtomicReference<>();

        final RecordingSubscription subscription = new RecordingSubscription();

        final AtomicLong subscriptions = new AtomicLong();

        @Override
        public void subscribe(Flow.Subscriber<? super AnswerStreamEvent> newcomer) {
            subscriptions.incrementAndGet();
            subscriber.set(newcomer);
        }

        Flow.Subscriber<? super AnswerStreamEvent> projection() {
            return subscriber.get();
        }

        void emit(AnswerStreamEvent event) {
            subscriber.get().onNext(event);
        }
    }

    /**
     * A synchronous source that answers every request with as many buffered events as the credit
     * allows, on the calling thread — the shape a synchronous transport produces under one demand
     * unit. The subscription counts the nesting depth of request calls, so a refill chain that
     * recursed through onNext would show it.
     */
    private static final class BurstingSource implements Flow.Publisher<AnswerStreamEvent> {

        private final AtomicReference<Flow.Subscriber<? super AnswerStreamEvent>> subscriber = new AtomicReference<>();

        private final List<AnswerStreamEvent> pending;

        final BurstingSubscription subscription = new BurstingSubscription();

        BurstingSource(int noiseChunks) {
            List<AnswerStreamEvent> events = new ArrayList<>(noiseChunks + 1);
            for (int index = 0; index < noiseChunks; index++) {
                events.add(new AnswerStreamEvent.Passthrough("framing-noise-" + index));
            }
            this.pending = events;
        }

        void finishWithAnswer() {
            subscriber.get().onNext(new AnswerStreamEvent.Text("answer"));
            subscriber.get().onComplete();
        }

        Flow.Subscriber<? super AnswerStreamEvent> projection() {
            return subscriber.get();
        }

        @Override
        public void subscribe(Flow.Subscriber<? super AnswerStreamEvent> newcomer) {
            subscriber.set(newcomer);
        }

        private final class BurstingSubscription implements Flow.Subscription {

            final AtomicLong requested = new AtomicLong();

            final AtomicLong maxNestingDepth = new AtomicLong();

            private long credit;

            private int delivered;

            private int depth;

            @Override
            public void request(long n) {
                depth++;
                maxNestingDepth.accumulateAndGet(depth, Math::max);
                try {
                    requested.addAndGet(n);
                    credit += n;
                    while (credit > 0 && delivered < pending.size()) {
                        credit--;
                        subscriber.get().onNext(pending.get(delivered++));
                    }
                } finally {
                    depth--;
                }
            }

            @Override
            public void cancel() {}
        }
    }

    private static final class RecordingSubscription implements Flow.Subscription {

        final AtomicLong requested = new AtomicLong();

        final AtomicLong cancels = new AtomicLong();

        @Override
        public void request(long n) {
            requested.addAndGet(n);
        }

        @Override
        public void cancel() {
            cancels.incrementAndGet();
        }
    }

    private static final class CountingDetach implements Runnable {

        final AtomicInteger runs = new AtomicInteger();

        @Override
        public void run() {
            runs.incrementAndGet();
        }
    }

    private static final class CollectingSubscriber<T> implements Flow.Subscriber<T> {

        final List<T> received = new ArrayList<>();

        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();

        volatile boolean completed;

        volatile Throwable failed;

        Flow.Subscription subscription() {
            return subscription.get();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription.set(subscription);
        }

        @Override
        public void onNext(T item) {
            received.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            failed = throwable;
        }

        @Override
        public void onComplete() {
            completed = true;
        }
    }
}

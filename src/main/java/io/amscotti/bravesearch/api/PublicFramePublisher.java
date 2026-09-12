package io.amscotti.bravesearch.api;

import io.amscotti.bravesearch.domain.answer.AnswerStreamEvent;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

/**
 * The single-subscription publisher projecting one exchange's semantic answer events onto
 * the public frame vocabulary.
 *
 * <p>The projection is a transparent relay: demand travels through verbatim (one requested
 * item delivers one projected frame), a passthrough chunk — framing noise that carries no
 * answer content — spends its pull on one transparent refill instead of a delivery, and the
 * refills drain iteratively: a synchronous source that answers every request inline never
 * nests one refill inside another, so an arbitrarily long noise run under one demand unit
 * costs no recursion depth. Cancel maps to the source's cancellation, and the source's
 * serialized-callback and terminal-exactly-once guarantees pass through untouched. A
 * terminal source failure is rewrapped as {@link AnswersStreamException} — carrying the
 * failure's stable category — so no internal failure type crosses the boundary.
 *
 * <p>Terminal state is remembered: the handle's terminal action runs once when the source
 * completes or fails, and a subscriber that arrives before any live subscription but after
 * the terminal — the handle closed the stream up front — receives the terminal signal
 * immediately instead of hanging. A second subscription attempt after a live one is refused
 * per the reactive-streams convention.
 */
final class PublicFramePublisher implements Flow.Publisher<PublicStreamFrame> {

    private final Flow.Publisher<AnswerStreamEvent> source;

    private final Function<String, JsonNode> tagPayloadReader;

    private final Runnable onTerminal;

    private final AtomicBoolean subscribed = new AtomicBoolean();

    private volatile boolean terminated;

    private volatile AnswersStreamException terminalFailure;

    PublicFramePublisher(
            Flow.Publisher<AnswerStreamEvent> source,
            Function<String, JsonNode> tagPayloadReader,
            Runnable onTerminal) {
        this.source = Objects.requireNonNull(source, "source");
        this.tagPayloadReader = Objects.requireNonNull(tagPayloadReader, "tagPayloadReader");
        this.onTerminal = Objects.requireNonNull(onTerminal, "onTerminal");
    }

    /**
     * The handle ended the stream before any subscription existed; the first subscriber now
     * receives completion immediately. A consumer closing their own stream is a normal end,
     * not a failure.
     */
    void terminatedWithoutSubscription() {
        if (!subscribed.get()) {
            terminated = true;
            onTerminal.run();
        }
    }

    @Override
    public void subscribe(Flow.Subscriber<? super PublicStreamFrame> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        if (!subscribed.compareAndSet(false, true)) {
            // the source publisher is single-subscription, so the projection refuses its
            // own second subscriber by the same reactive-streams convention
            subscriber.onSubscribe(NoOpSubscription.INSTANCE);
            subscriber.onError(new IllegalStateException("the frames publisher accepts exactly one subscription"));
            return;
        }
        if (terminated) {
            // no live subscription ever existed and the stream is already over: replay the
            // terminal instead of parking the late subscriber forever
            subscriber.onSubscribe(NoOpSubscription.INSTANCE);
            if (terminalFailure != null) {
                subscriber.onError(terminalFailure);
            } else {
                subscriber.onComplete();
            }
            return;
        }
        source.subscribe(new Projection(subscriber));
    }

    private enum NoOpSubscription implements Flow.Subscription {
        INSTANCE;

        @Override
        public void request(long n) {}

        @Override
        public void cancel() {}
    }

    /** The subscriber of the source and the subscription of the public consumer, in one. */
    private final class Projection implements Flow.Subscriber<AnswerStreamEvent>, Flow.Subscription {

        private final Flow.Subscriber<? super PublicStreamFrame> downstream;

        private volatile Flow.Subscription upstream;

        private volatile boolean cancelled;

        /** True while this thread is already draining transparent refills; a trampoline flag. */
        private boolean draining;

        /** A passthrough arrived while draining; the running drain loop owes one refill. */
        private boolean refillPending;

        Projection(Flow.Subscriber<? super PublicStreamFrame> downstream) {
            this.downstream = downstream;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            upstream = subscription;
            if (cancelled) {
                subscription.cancel();
                return;
            }
            downstream.onSubscribe(this);
        }

        @Override
        public void onNext(AnswerStreamEvent event) {
            if (cancelled) {
                return;
            }
            if (event instanceof AnswerStreamEvent.Passthrough) {
                // framing noise is invisible to the public consumer; pull one refill so the
                // next real frame still arrives under the same outstanding demand, drained
                // iteratively so a synchronous source cannot recurse through this call
                refillPending = true;
                drainRefills();
                return;
            }
            downstream.onNext(project(event));
        }

        @Override
        public void onError(Throwable failure) {
            terminated = true;
            terminalFailure = AnswersStreamException.of(failure);
            onTerminal.run();
            if (!cancelled) {
                downstream.onError(terminalFailure);
            }
        }

        @Override
        public void onComplete() {
            terminated = true;
            onTerminal.run();
            if (!cancelled) {
                downstream.onComplete();
            }
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                downstream.onError(new IllegalArgumentException("non-positive subscription request: " + n));
                cancel();
                return;
            }
            if (!cancelled) {
                upstream.request(n);
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
            Flow.Subscription subscription = upstream;
            if (subscription != null) {
                subscription.cancel();
            }
        }

        /**
         * Refills pending passthrough pulls. A nested entry — a synchronous source whose
         * request delivered another passthrough inside the drain loop — only marks the
         * refill pending and returns, and the already-running loop picks it up, so the
         * refill chain never grows the stack.
         */
        private void drainRefills() {
            if (draining) {
                return;
            }
            draining = true;
            try {
                while (refillPending && !cancelled) {
                    refillPending = false;
                    upstream.request(1);
                }
            } finally {
                draining = false;
            }
        }

        private PublicStreamFrame project(AnswerStreamEvent event) {
            return switch (event) {
                case AnswerStreamEvent.Text text -> new PublicStreamFrame.Text(text.text());
                case AnswerStreamEvent.Tagged tagged -> new PublicStreamFrame.Tagged(
                        tagged.tag(), tagged.payload(), tagPayloadReader.apply(tagged.payload()));
                case AnswerStreamEvent.UnknownTag unknown -> new PublicStreamFrame.UnknownTag(
                        unknown.name(), unknown.rawText(), unknown.sseName(), unknown.sseId(), unknown.sseRetryMillis());
                case AnswerStreamEvent.Passthrough ignored -> throw new IllegalStateException(
                        "passthrough events are never public frames");
            };
        }
    }
}

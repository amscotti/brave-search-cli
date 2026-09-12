package io.amscotti.bravesearch.adapter.bravehttp.sse;

import io.amscotti.bravesearch.adapter.bravehttp.json.AnswerTagDecoder;
import io.amscotti.bravesearch.application.stream.AbruptEofException;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.SseEvent;
import io.amscotti.bravesearch.application.stream.SseException;
import io.amscotti.bravesearch.application.stream.StreamBodyPublisher;
import io.amscotti.bravesearch.domain.answer.AnswerDecodeException;
import io.amscotti.bravesearch.domain.answer.AnswerStreamEvent;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The semantic layer of one Answers stream: a {@link Flow.Processor} standing between the raw
 * decoded-byte publisher and a semantic consumer. Subscribing to it chains it onto the single
 * body publisher — one HTTP exchange therefore serves both representations, because the raw
 * mode subscribes the same body publisher directly and this processor is only ever its one
 * subscriber on the semantic side.
 *
 * <p>Demand travels through both layers: the processor holds at most one outstanding raw
 * request at a time — each pull sized to one publisher chunk, so the semantic layer reads a
 * window's worth of bytes per request instead of a byte per request — and only issues it
 * while its own subscriber still has credit and no buffered events, so raw reads never run
 * more than one chunk ahead of semantic demand. One raw chunk may decode
 * into several events; the surplus waits in a bounded buffer — never larger than what one
 * chunk's bytes can produce — and the terminal signal holds until the buffer drains, so no
 * event is dropped or delivered beyond demand. All state and every subscriber callback run
 * under one monitor, so callbacks are strictly serialized and never concurrent.
 *
 * <p>Ending semantics: the transport parser's terminal marker and an observed completed
 * {@code <usage>} tag are both documented terminal conditions — an end of body without the
 * marker downgrades to normal completion only when the usage tag was observed, and otherwise
 * surfaces as the typed incomplete failure. Every semantic failure — an overflow, a malformed
 * chunk or tag payload, a tag left open — latches the run's subscriber-failure cause (the
 * cancel signal that severs the body) when nothing latched earlier, cancels the raw
 * subscription, and delivers exactly one terminal signal. An upstream I/O break is the typed
 * {@link AbruptEofException} with the transport-failure cause latched; a subscriber callback
 * that throws cancels the raw subscription and fails exactly once.
 */
public final class AnswerStreamProcessor
        implements Flow.Publisher<AnswerStreamEvent>, Flow.Subscriber<byte[]>, Flow.Subscription {

    private static final String TERMINAL_DATA = "[DONE]";

    private static final String ABRUPT_ENDING = "the stream body broke before its terminator";

    private final Flow.Publisher<byte[]> source;
    private final CancellationContext cancellation;
    private final Clock clock;
    private final AtomicReference<Instant> lastSemanticProgress = new AtomicReference<>();

    private final ArrayDeque<AnswerStreamEvent> buffered = new ArrayDeque<>();
    private final AtomicBoolean subscribed = new AtomicBoolean();

    private Flow.Subscriber<? super AnswerStreamEvent> downstream;
    private Flow.Subscription upstream;
    private SseEventParser parser;
    private AnswerTagDecoder decoder;
    private long demand;
    private boolean pullInFlight;
    private boolean upstreamDone;
    private boolean closed;
    private boolean terminalPending;
    private boolean terminalDelivered;
    private Throwable pendingError;
    private boolean usageSeen;
    private AnswerDecodeException decodeFailure;

    /**
     * Creates the unarmed semantic layer over the run's single body publisher; the
     * composition root {@link #armedWith(SseEventParser, AnswerTagDecoder) arms} it with the
     * stateful parser and decoder before any subscription, because only a composition root
     * may instantiate those concrete adapters.
     *
     * @param source the raw decoded-byte publisher of the same exchange
     * @param cancellation the run's shared terminal-cause latch
     * @param clock time source of the semantic-progress marks
     */
    public AnswerStreamProcessor(
            Flow.Publisher<byte[]> source, CancellationContext cancellation, Clock clock) {
        this.source = Objects.requireNonNull(source, "source");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Arms this layer with its stateful parser — built with this processor's
     * {@link #onDispatchedEvent(SseEvent)} as the parser's listener — and its delta decoder;
     * returns this armed processor for a one-expression composition.
     */
    public AnswerStreamProcessor armedWith(SseEventParser armedParser, AnswerTagDecoder armedDecoder) {
        this.parser = Objects.requireNonNull(armedParser, "armedParser");
        this.decoder = Objects.requireNonNull(armedDecoder, "armedDecoder");
        return this;
    }

    /**
     * Registers {@code newcomer} as the single semantic subscriber and chains this processor
     * onto the raw body publisher. A second subscriber is refused per Flow conventions with
     * {@code onSubscribe} followed by {@code onError} and no effect on the live subscription.
     *
     * @throws NullPointerException if {@code newcomer} is {@code null}
     */
    @Override
    public void subscribe(Flow.Subscriber<? super AnswerStreamEvent> newcomer) {
        Objects.requireNonNull(newcomer, "newcomer");
        if (!subscribed.compareAndSet(false, true)) {
            refuse(newcomer);
            return;
        }
        synchronized (this) {
            downstream = newcomer;
            newcomer.onSubscribe(this);
        }
        source.subscribe(this);
    }

    /** Adds credit for the live subscriber; a negative amount fails the subscriber per the Flow rules. */
    @Override
    public void request(long n) {
        synchronized (this) {
            if (closed || terminalDelivered) {
                return;
            }
            if (n < 0) {
                semanticFailure(new IllegalArgumentException("negative request amount: " + n));
                return;
            }
            if (n > 0) {
                demand = demand == Long.MAX_VALUE || n > Long.MAX_VALUE - demand
                        ? Long.MAX_VALUE
                        : demand + n;
                drain();
            }
        }
    }

    /**
     * Ends the semantic subscription: latches the close cause, cancels the raw subscription,
     * and stops every later callback. Repeated calls are no-ops.
     */
    @Override
    public void cancel() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            cancellation.latch(CancellationContext.Cause.CLOSED);
            if (upstream != null) {
                upstream.cancel();
            }
            buffered.clear();
        }
    }

    @Override
    public void onSubscribe(Flow.Subscription upstream) {
        synchronized (this) {
            if (closed) {
                upstream.cancel();
                return;
            }
            this.upstream = upstream;
            drain();
        }
    }

    @Override
    public void onNext(byte[] chunk) {
        synchronized (this) {
            if (closed || upstreamDone) {
                return;
            }
            pullInFlight = false;
            try {
                parser.feed(chunk, 0, chunk.length);
            } catch (SseException typed) {
                // a decode failure stashed earlier in this same feed is the run's real
                // failure: a transport breach behind it never outranks the first cause
                semanticFailure(decodeFailure != null ? decodeFailure : typed);
                return;
            }
            if (decodeFailure != null) {
                semanticFailure(decodeFailure);
                return;
            }
            drain();
        }
    }

    @Override
    public void onError(Throwable thrown) {
        synchronized (this) {
            if (closed || upstreamDone) {
                return;
            }
            upstreamDone = true;
            pendingError = thrown instanceof IOException io
                    ? new AbruptEofException(ABRUPT_ENDING, io)
                    : thrown;
            cancellation.latch(CancellationContext.Cause.TRANSPORT_FAILURE);
            terminalPending = true;
            drain();
        }
    }

    @Override
    public void onComplete() {
        synchronized (this) {
            if (closed || upstreamDone) {
                return;
            }
            upstreamDone = true;
            try {
                parser.endOfStream();
            } catch (SseException.Incomplete incomplete) {
                if (!usageSeen) {
                    pendingError = incomplete;
                    terminalPending = true;
                    drain();
                    return;
                }
                // the observed usage tag is a documented terminal condition: the marker-less
                // ending downgrades to normal completion instead of failing typed
            } catch (SseException typed) {
                semanticFailure(typed);
                return;
            }
            try {
                buffered.addAll(decoder.finish());
            } catch (AnswerDecodeException typed) {
                semanticFailure(typed);
                return;
            }
            pendingError = null;
            terminalPending = true;
            drain();
        }
    }

    /** The instant of the last decoded event, for verbose progress diagnostics; null before any. */
    public Instant lastSemanticProgress() {
        return lastSemanticProgress.get();
    }

    /**
     * The transport listener: decodes one dispatched payload into events. Decoder failures are
     * stashed instead of thrown, because the transport parser wraps a throwing listener in its
     * own type and the semantic layer must surface the decoder's failure, not the wrapping. A
     * stashed failure also silences every later dispatch — the decoder is finished, so
     * re-entering it would only hand the parser a rejection to wrap over the real failure —
     * and it outranks any transport breach the same feed raises behind it. An
     * unknown tag is re-wrapped with the envelope of its block — the event name and the id and
     * retry values current at dispatch — so a preserved upstream event keeps its wire context;
     * a block without any envelope member leaves the decoder's envelope-free form in place.
     */
    public void onDispatchedEvent(SseEvent event) {
        if (decodeFailure != null) {
            // the decoder finished with its typed failure; a later dispatch in the same feed
            // must not re-enter it, or the parser would wrap the finished-decoder rejection
            // and mask the stashed failure with it
            return;
        }
        if (TERMINAL_DATA.equals(event.data())) {
            return;
        }
        try {
            for (AnswerStreamEvent decoded : decoder.decode(event.data())) {
                if (decoded instanceof AnswerStreamEvent.Tagged tagged && "usage".equals(tagged.tag())) {
                    usageSeen = true;
                }
                buffered.add(withEnvelope(decoded, event));
            }
            lastSemanticProgress.set(clock.instant());
        } catch (AnswerDecodeException typed) {
            decodeFailure = typed;
        }
    }

    /** The event as the run buffers it: unknown tags carry their block's SSE envelope when it had one. */
    private static AnswerStreamEvent withEnvelope(AnswerStreamEvent decoded, SseEvent event) {
        if (decoded instanceof AnswerStreamEvent.UnknownTag unknown
                && (event.name() != null || event.lastEventId() != null || event.retryMillis() != null)) {
            return new AnswerStreamEvent.UnknownTag(
                    unknown.name(), unknown.rawText(), event.name(), event.lastEventId(), event.retryMillis());
        }
        return decoded;
    }

    /**
     * Delivers buffered events while demand lasts, then either lands the pending terminal once
     * the buffer is empty or pulls the next raw chunk — never both, and never a pull while
     * events wait, so raw reads stay gated by semantic demand.
     */
    private void drain() {
        if (closed || terminalDelivered || downstream == null) {
            return;
        }
        while (demand > 0 && !buffered.isEmpty()) {
            AnswerStreamEvent event = buffered.poll();
            demand--;
            try {
                downstream.onNext(event);
            } catch (RuntimeException thrown) {
                subscriberBroke(thrown);
                return;
            }
        }
        if (buffered.isEmpty() && terminalPending) {
            terminalDelivered = true;
            if (pendingError != null) {
                deliverTerminalError(pendingError);
            } else {
                try {
                    downstream.onComplete();
                } catch (RuntimeException ignored) {
                    // a terminal callback that throws has no other channel to report to
                }
            }
            return;
        }
        if (buffered.isEmpty() && demand > 0 && !upstreamDone && !pullInFlight && upstream != null) {
            pullInFlight = true;
            // one pull procures one window-sized chunk: the raw publisher weighs credit in
            // bytes, so a chunk's worth keeps the semantic layer one chunk ahead of demand
            // instead of one byte ahead
            upstream.request(StreamBodyPublisher.CHUNK_LIMIT);
        }
    }

    /**
     * The terminal path of every semantic failure: latch the cancel signal when nothing
     * latched earlier, sever the raw subscription, and queue the typed failure for a single
     * delivery.
     */
    private void semanticFailure(Throwable failure) {
        cancellation.latch(CancellationContext.Cause.SUBSCRIBER_FAILURE);
        if (upstream != null) {
            upstream.cancel();
        }
        pendingError = failure;
        terminalPending = true;
        drain();
    }

    /** A subscriber whose callback threw: the run ends with the subscriber-failure cause and one error. */
    private void subscriberBroke(RuntimeException thrown) {
        cancellation.latch(CancellationContext.Cause.SUBSCRIBER_FAILURE);
        if (upstream != null) {
            upstream.cancel();
        }
        terminalDelivered = true;
        deliverTerminalError(thrown);
    }

    private void deliverTerminalError(Throwable failure) {
        try {
            downstream.onError(failure);
        } catch (RuntimeException ignored) {
            // a failing error callback must not mask the reader's own terminal state
        }
    }

    /**
     * Hands a duplicate subscriber a dead subscription and the double-subscription error,
     * using a throwaway JDK submission publisher on the calling thread so this layer never
     * constructs another subscription object itself.
     */
    private static void refuse(Flow.Subscriber<? super AnswerStreamEvent> newcomer) {
        try (SubmissionPublisher<AnswerStreamEvent> refusal = new SubmissionPublisher<>(Runnable::run, 1)) {
            refusal.subscribe(newcomer);
            refusal.closeExceptionally(new IllegalStateException("this publisher accepts a single subscription"));
        }
    }
}

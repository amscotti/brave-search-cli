package io.amscotti.bravesearch.application.stream;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sink policy for one streamed body: every published chunk is written to the target writer and
 * flushed, delivery progress is published for deadline watchdogs, and a write the writer
 * surfaces as an error latches the broken-pipe cause — the in-process form of the EPIPE signal
 * a closed stdout delivers.
 *
 * <p>Chunks are decoded and written as ISO-8859-1 text because that charset maps every byte to
 * exactly one character and back, so the writer receives and emits the payload bytes exactly.
 * Progress marks read the injected {@link Clock}, the same time source the run's deadline
 * machinery uses. The relay requests unbounded demand: its writers are sinks like stdout whose
 * interesting failure modes are cancellation races, not slow consumption.
 */
public final class StreamBodyRelay implements Flow.Subscriber<byte[]> {

    private final PrintWriter out;
    private final CancellationContext cancellation;
    private final AtomicReference<Instant> lastProgress;
    private final Clock clock;
    private Flow.Subscription subscription;

    public StreamBodyRelay(
            PrintWriter out,
            CancellationContext cancellation,
            AtomicReference<Instant> lastProgress,
            Clock clock) {
        this.out = Objects.requireNonNull(out, "out");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.lastProgress = Objects.requireNonNull(lastProgress, "lastProgress");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(byte[] chunk) {
        out.write(new String(chunk, StandardCharsets.ISO_8859_1));
        out.flush();
        lastProgress.set(clock.instant());
        if (out.checkError()) {
            cancellation.latch(CancellationContext.Cause.BROKEN_PIPE);
            subscription.cancel();
        }
    }

    @Override
    public void onError(Throwable thrown) {
        // the publisher latches deadline and subscriber causes before delivering their error;
        // anything still unlatched here is a raw transport failure, and latching it guarantees
        // every terminal path decides a cause — the command awaiting the latch can never hang
        cancellation.latch(CancellationContext.Cause.TRANSPORT_FAILURE);
    }

    @Override
    public void onComplete() {
        // the publisher latches CLOSED when the body is exhausted
    }
}

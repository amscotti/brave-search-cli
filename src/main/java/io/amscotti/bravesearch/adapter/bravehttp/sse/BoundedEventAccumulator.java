package io.amscotti.bravesearch.adapter.bravehttp.sse;

import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.SseEvent;
import io.amscotti.bravesearch.application.stream.SseException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Buffered-mode bound of a decoded event stream: dispatched event payloads may accumulate for a
 * whole-stream rendering — the explicit buffered output mode — but never past the injected byte
 * ceiling. The accumulation counts decoded UTF-8 bytes of the joined form, newline separators
 * included, and a breach latches the run's subscriber-failure cause (the cancel signal that
 * unblocks the body reader) before the typed {@link SseException.Overflow} surfaces, so an
 * endless stream can never be buffered without limit.
 *
 * <p>Instances are not thread-safe: they are fed by the single thread that owns the stream.
 */
public final class BoundedEventAccumulator {

    /** Default ceiling for the accumulated decoded stream. */
    public static final long DEFAULT_MAX_BYTES = 16L * 1024 * 1024;

    private final CancellationContext cancellation;
    private final long maxBytes;

    private final StringBuilder joined = new StringBuilder();
    private long bytes;

    /**
     * Creates an accumulator with an explicitly supplied ceiling; production composition passes
     * {@link #DEFAULT_MAX_BYTES} and contract tests run at kibibyte scale against the same code
     * path.
     *
     * @throws NullPointerException when {@code cancellation} is null
     */
    public BoundedEventAccumulator(CancellationContext cancellation, long maxBytes) {
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.maxBytes = maxBytes;
    }

    /**
     * Accumulates one dispatched event's payload.
     *
     * @throws SseException.Overflow when the joined form would exceed the ceiling; the run's
     *     subscriber-failure cause is latched first, which cancels the stream
     */
    public void add(SseEvent event) {
        Objects.requireNonNull(event, "event");
        long dataBytes = event.data().getBytes(StandardCharsets.UTF_8).length;
        long separator = bytes == 0 ? 0 : 1;
        if (bytes + separator + dataBytes > maxBytes) {
            cancellation.latch(CancellationContext.Cause.SUBSCRIBER_FAILURE);
            throw new SseException.Overflow("the accumulated stream exceeded its byte ceiling of " + maxBytes);
        }
        if (separator == 1) {
            joined.append('\n');
        }
        joined.append(event.data());
        bytes += separator + dataBytes;
    }

    /** The accumulated payloads joined with newlines; empty before the first add. */
    public String joinedData() {
        return joined.toString();
    }

    /** Decoded UTF-8 bytes accumulated so far, separators included. */
    public long accumulatedBytes() {
        return bytes;
    }
}

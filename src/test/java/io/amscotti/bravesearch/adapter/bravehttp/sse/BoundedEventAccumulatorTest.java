package io.amscotti.bravesearch.adapter.bravehttp.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.SseEvent;
import io.amscotti.bravesearch.application.stream.SseException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Buffered-mode bound of a decoded event stream: dispatched event payloads may accumulate for a
 * whole-stream rendering, but never past the injected byte ceiling — the accumulation counts
 * decoded UTF-8 bytes including the newline separators of the joined form, and a breach latches
 * the run's terminal cause before the typed overflow surfaces.
 */
final class BoundedEventAccumulatorTest {

    @Test
    void accumulatesDispatchedDataJoinedWithNewlines() {
        BoundedEventAccumulator accumulator =
                new BoundedEventAccumulator(new CancellationContext(), BoundedEventAccumulator.DEFAULT_MAX_BYTES);

        accumulator.add(new SseEvent(null, "alpha", null, null));
        accumulator.add(new SseEvent(null, "beta\nline", null, null));
        accumulator.add(new SseEvent(null, "", null, null));

        assertEquals("alpha\nbeta\nline\n", accumulator.joinedData());
        assertEquals(16, accumulator.accumulatedBytes());
    }

    @Test
    void breachAtTheInjectedBoundLatchesCancellationAndThrowsTypedOverflow() {
        CancellationContext cancellation = new CancellationContext();
        BoundedEventAccumulator accumulator = new BoundedEventAccumulator(cancellation, 8);

        accumulator.add(new SseEvent(null, "alpha", null, null));
        SseException.Overflow breached =
                assertThrows(SseException.Overflow.class, () -> accumulator.add(new SseEvent(null, "beta", null, null)));

        assertTrue(
                breached.getMessage().contains("8"),
                "the diagnostic must name the breached limit: " + breached.getMessage());
        assertEquals(Optional.of(CancellationContext.Cause.SUBSCRIBER_FAILURE), cancellation.cause());
        assertEquals("alpha", accumulator.joinedData(), "the accumulation before the breach stays observable");
    }
}

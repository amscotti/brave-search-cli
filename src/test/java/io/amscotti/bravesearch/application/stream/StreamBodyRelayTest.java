package io.amscotti.bravesearch.application.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Terminal-cause contracts of the writer relay: a transport failure surfacing as onError ends
 * in a latched cause — so the awaiting command can never hang — while an already decided run
 * keeps its first cause.
 */
final class StreamBodyRelayTest {

    @Test
    void onErrorLatchesTransportFailureWhenCauseAbsent() {
        CancellationContext cancellation = new CancellationContext();

        newRelay(cancellation).onError(new IOException("connection reset"));

        assertEquals(
                CancellationContext.Cause.TRANSPORT_FAILURE,
                cancellation.cause().orElseThrow(),
                "a transport failure with no cause decided yet must latch TRANSPORT_FAILURE");
    }

    @Test
    void onErrorKeepsTheCauseThatWonBeforeIt() {
        CancellationContext cancellation = new CancellationContext();
        cancellation.latch(CancellationContext.Cause.IDLE_TIMEOUT);

        newRelay(cancellation).onError(new IOException("connection reset"));

        assertEquals(
                CancellationContext.Cause.IDLE_TIMEOUT,
                cancellation.cause().orElseThrow(),
                "the relay must never displace the first terminal cause");
    }

    private static StreamBodyRelay newRelay(CancellationContext cancellation) {
        return new StreamBodyRelay(
                new PrintWriter(new StringWriter()),
                cancellation,
                new AtomicReference<>(Instant.EPOCH),
                Clock.systemUTC());
    }
}

package io.amscotti.bravesearch.application.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.amscotti.bravesearch.domain.error.FailureSignal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The bridge from streaming terminal causes to the shared failure-signal vocabulary: the
 * interruption cause carries its own signal, every stream failure is a transport condition,
 * and the two normal ends of a stream are successes, not failures.
 */
final class CauseSignalsTest {

    @Test
    void interruptionMapsToTheInterruptedSignal() {
        assertEquals(
                Optional.of(FailureSignal.INTERRUPTED),
                CauseSignals.failureSignal(CancellationContext.Cause.SIGINT));
    }

    @Test
    void terminationMapsToTheTerminatedSignal() {
        assertEquals(
                Optional.of(FailureSignal.TERMINATED),
                CauseSignals.failureSignal(CancellationContext.Cause.SIGTERM));
    }

    @Test
    void everyStreamFailureMapsToTheTransportSignal() {
        List<CancellationContext.Cause> failures =
                List.of(
                        CancellationContext.Cause.IDLE_TIMEOUT,
                        CancellationContext.Cause.WALL_TIMEOUT,
                        CancellationContext.Cause.SUBSCRIBER_FAILURE,
                        CancellationContext.Cause.TRANSPORT_FAILURE);
        for (CancellationContext.Cause failure : failures) {
            assertEquals(
                    Optional.of(FailureSignal.TRANSPORT),
                    CauseSignals.failureSignal(failure),
                    () -> failure + " is a transport condition");
        }
    }

    @Test
    void brokenPipeAndCleanCloseAreSuccessesWithoutAFailureSignal() {
        assertEquals(Optional.empty(), CauseSignals.failureSignal(CancellationContext.Cause.BROKEN_PIPE));
        assertEquals(Optional.empty(), CauseSignals.failureSignal(CancellationContext.Cause.CLOSED));
    }
}

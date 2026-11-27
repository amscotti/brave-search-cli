package io.amscotti.bravesearch.adapter.cli.exit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.FailureSignal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The exit-status table of every failure category and the special non-failure paths. */
final class ExitCodeMapperTest {

    private static final Map<FailureKind, Integer> EXPECTED_KIND_CODES =
            Map.of(
                    FailureKind.USAGE, 2,
                    FailureKind.LOCAL_CONFIG, 3,
                    FailureKind.AUTHENTICATION, 4,
                    FailureKind.RATE_LIMITED, 5,
                    FailureKind.TRANSPORT, 6,
                    FailureKind.UPSTREAM, 7,
                    FailureKind.MALFORMED, 8,
                    FailureKind.INTERNAL, 70);

    @Test
    void exitCodeTableCoversEveryFailureKind() {
        for (Map.Entry<FailureKind, Integer> expected : EXPECTED_KIND_CODES.entrySet()) {
            assertEquals(
                    expected.getValue(),
                    ExitCodeMapper.forKind(expected.getKey()),
                    () -> "wrong exit status for " + expected.getKey());
        }
        assertEquals(
                EXPECTED_KIND_CODES.size(),
                FailureKind.values().length,
                "the exit table must cover every failure kind exactly");
    }

    @Test
    void signalCodesAgreeWithTheKindTable() {
        for (FailureSignal signal : FailureSignal.values()) {
            if (signal.kind() == null) {
                continue;
            }
            assertEquals(
                    ExitCodeMapper.forKind(signal.kind()),
                    ExitCodeMapper.forSignal(signal),
                    () -> "signal table disagrees with the kind table for " + signal);
        }
    }

    @Test
    void interruptedSignalExitsWithSigintStatus() {
        assertEquals(130, ExitCodeMapper.forSignal(FailureSignal.INTERRUPTED));
        assertEquals(ExitCodeMapper.SIGINT, ExitCodeMapper.forSignal(FailureSignal.INTERRUPTED));
    }

    @Test
    void terminatedSignalExitsWithTheConventionalTerminationStatus() {
        assertEquals(143, ExitCodeMapper.forSignal(FailureSignal.TERMINATED));
        assertEquals(ExitCodeMapper.SIGTERM, ExitCodeMapper.forSignal(FailureSignal.TERMINATED));
    }

    @Test
    void successAndBrokenPipeBothExitZero() {
        assertEquals(0, ExitCodeMapper.SUCCESS);
        assertEquals(0, ExitCodeMapper.BROKEN_PIPE);
    }

    private static final Map<CancellationContext.Cause, Integer> EXPECTED_CAUSE_CODES =
            Map.of(
                    CancellationContext.Cause.SIGINT, 130,
                    CancellationContext.Cause.SIGTERM, 143,
                    CancellationContext.Cause.BROKEN_PIPE, 0,
                    CancellationContext.Cause.CLOSED, 0,
                    CancellationContext.Cause.IDLE_TIMEOUT, 6,
                    CancellationContext.Cause.WALL_TIMEOUT, 6,
                    CancellationContext.Cause.SUBSCRIBER_FAILURE, 6,
                    CancellationContext.Cause.TRANSPORT_FAILURE, 6);

    @Test
    void everyStreamingTerminalCauseReadsItsExitStatusFromThisTable() {
        // this table is the one owner of process exit statuses: the streaming latch names
        // why a run ended and knows nothing of statuses
        for (Map.Entry<CancellationContext.Cause, Integer> expected : EXPECTED_CAUSE_CODES.entrySet()) {
            assertEquals(
                    expected.getValue(),
                    ExitCodeMapper.forCause(expected.getKey()),
                    () -> "wrong exit status for " + expected.getKey());
        }
        assertEquals(
                EXPECTED_CAUSE_CODES.size(),
                CancellationContext.Cause.values().length,
                "the exit table must cover every streaming cancellation cause exactly");
        assertThrows(NullPointerException.class, () -> ExitCodeMapper.forCause(null));
    }
}

package io.amscotti.bravesearch.adapter.cli.exit;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}

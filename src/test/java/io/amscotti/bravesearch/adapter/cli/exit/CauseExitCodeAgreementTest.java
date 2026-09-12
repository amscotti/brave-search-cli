package io.amscotti.bravesearch.adapter.cli.exit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.CauseSignals;
import io.amscotti.bravesearch.domain.error.FailureSignal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The two interruption vocabularies agree: every streaming terminal cause's own exit code is
 * exactly what the exit mapper produces over the cause's mapped signal, so no run can diverge
 * between the streaming latch and the exit table.
 */
final class CauseExitCodeAgreementTest {

    @Test
    void everyCauseExitCodeEqualsTheExitMapperOverItsMappedSignal() {
        for (CancellationContext.Cause cause : CancellationContext.Cause.values()) {
            Optional<FailureSignal> signal = CauseSignals.failureSignal(cause);
            int expected = signal
                    .map(ExitCodeMapper::forSignal)
                    .orElseGet(() -> cause == CancellationContext.Cause.BROKEN_PIPE
                            ? ExitCodeMapper.BROKEN_PIPE
                            : ExitCodeMapper.SUCCESS);
            assertEquals(
                    expected,
                    cause.exitCode(),
                    () -> cause + " diverges between its own exit code and the exit table");
        }
    }
}

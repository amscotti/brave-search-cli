package io.amscotti.bravesearch.adapter.cli.command;

import io.amscotti.bravesearch.adapter.cli.exit.ExitCodeMapper;
import io.amscotti.bravesearch.adapter.cli.presentation.DiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriteFailure;
import io.amscotti.bravesearch.application.service.PaginationService;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.CauseSignals;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.FailureSignal;
import io.amscotti.bravesearch.domain.result.PagedExchange;
import java.io.UncheckedIOException;
import java.util.Optional;

/**
 * The shared exit policy of every sequential multi-request walk — the page walk of
 * {@code --all-pages} and the chunk fan-out of the place enrichment commands — so one
 * walk's process verdicts are written once.
 *
 * <p>Two verdicts live here. A write failure whose cause is the consumer's closed pipe
 * latches the broken-pipe cause and ends the walk as silent success — or by the user's
 * signal when an interrupt or termination had already won the latch — and every other write
 * failure keeps the shared transport verdict of {@link ResultWriteFailure}. A walk that
 * failed while the latch held a user-signal cause renders the mode's transport-failure
 * document with the run's counts but exits by that signal — the conventional 130 of SIGINT
 * or 143 of SIGTERM: the latch, not the rendered kind, decides.
 */
public final class WalkExitPolicy {

    private WalkExitPolicy() {}

    /**
     * The walk's own write-failure verdict: a broken pipe is latched as the run's
     * terminal cause — the latch, not the write, decides the status when a user signal
     * had already won it — and every other write failure keeps the transport verdict.
     */
    public static int exitForWalkWriteFailure(
            UncheckedIOException failure,
            CancellationContext cancellation,
            DiagnosticsSink diagnostics,
            String commandName) {
        if (!ResultWriteFailure.reportsBrokenPipe(failure)) {
            diagnostics.emit(commandName + ": writing the result document failed");
            return ExitCodeMapper.forKind(FailureKind.TRANSPORT);
        }
        cancellation.latch(CancellationContext.Cause.BROKEN_PIPE);
        return exitForLatchedCause(cancellation.cause().orElse(CancellationContext.Cause.BROKEN_PIPE));
    }

    /**
     * The exit status of the user signal that owns a failed walk's latch — the conventional
     * 130 of SIGINT or 143 of SIGTERM — or empty when no user signal owns the failure.
     */
    public static Optional<Integer> latchedSignalExit(
            PaginationService.PagedRun<? extends PagedExchange> run, CancellationContext cancellation) {
        if (run.succeeded()) {
            return Optional.empty();
        }
        return cancellation
                .cause()
                .flatMap(CauseSignals::failureSignal)
                .filter(signal -> signal == FailureSignal.INTERRUPTED || signal == FailureSignal.TERMINATED)
                .map(ExitCodeMapper::forSignal);
    }

    /** The exit of a latched cause on the write-failure path: a user signal's own status, the pipe's silent zero otherwise. */
    private static int exitForLatchedCause(CancellationContext.Cause cause) {
        return switch (cause) {
            case SIGINT -> ExitCodeMapper.SIGINT;
            case SIGTERM -> ExitCodeMapper.SIGTERM;
            default -> ExitCodeMapper.BROKEN_PIPE;
        };
    }
}

package io.amscotti.bravesearch.adapter.cli.presentation;

import io.amscotti.bravesearch.adapter.cli.exit.ExitCodeMapper;
import io.amscotti.bravesearch.domain.error.FailureKind;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Objects;

/**
 * The shared classification of result-write failures, so the single-exchange renderers and
 * the multi-request page walk decide identically: a write failure whose cause chain carries
 * the operating system's broken-pipe report is the documented silent early termination
 * (exit 0), and every other write failure keeps the transport status with exactly one
 * diagnostic line.
 *
 * <p>The page walk additionally records the broken pipe on its run's cancellation latch
 * before consulting this classification's verdict, which is why {@link
 * #reportsBrokenPipe(Throwable)} stays public beside {@link #exitFor}: the latch, not the
 * render, is authoritative for the walk's process status.
 */
public final class ResultWriteFailure {

    private ResultWriteFailure() {}

    /**
     * The exit status of one failed result write: silent success for the broken pipe, the
     * transport status with one diagnostic line for every other write failure.
     *
     * @param command the canonical command name prefixing the diagnostic line
     */
    public static int exitFor(UncheckedIOException failure, DiagnosticsSink diagnostics, String command) {
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(diagnostics, "diagnostics");
        if (reportsBrokenPipe(failure)) {
            return ExitCodeMapper.BROKEN_PIPE;
        }
        diagnostics.emit(command + ": writing the result document failed");
        return ExitCodeMapper.forKind(FailureKind.TRANSPORT);
    }

    /**
     * Whether the failure's cause chain carries the operating system's own broken-pipe
     * report: with the raw stdout byte stream the POSIX write failure genuinely says so.
     */
    public static boolean reportsBrokenPipe(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException && messageReportsBrokenPipe(cause.getMessage())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether an {@code IOException} message spells the broken-pipe condition: the
     * message text comes from the operating system's own error reporting, which is
     * localized under a non-English {@code LC_MESSAGES} on POSIX and carries Windows'
     * own pipe wordings, so the known spellings are matched — never assumed to be
     * exactly the C-locale {@code strerror} text.
     */
    private static boolean messageReportsBrokenPipe(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("broken pipe") // the C-locale POSIX strerror spelling
                // the errno name some runtimes and wrappers embed verbatim
                || lower.contains("epipe")
                // Windows ERROR_NO_DATA (232)
                || lower.contains("pipe is being closed")
                // Windows ERROR_BROKEN_PIPE (109)
                || lower.contains("pipe has been ended");
    }
}

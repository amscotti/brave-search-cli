package io.amscotti.bravesearch.adapter.cli.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.exit.ExitCodeMapper;
import io.amscotti.bravesearch.domain.error.FailureKind;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The shared classification of result-write failures: the operating system's own
 * broken-pipe report — in every spelling this CLI knows a platform or locale produces —
 * is the documented silent success, and every other write failure keeps the transport
 * verdict with exactly one diagnostic line.
 */
final class ResultWriteFailureTest {

    @Test
    void everyKnownBrokenPipeSpellingReportsBrokenPipe() {
        for (String message : new String[] {
            "Broken pipe", // the C-locale POSIX strerror spelling
            "[Errno 32] Broken pipe", // an errno-numbered wrapper spelling
            "[EPIPE] write to stdout failed", // the errno name a wrapper runtime embeds
            "The pipe is being closed", // Windows ERROR_NO_DATA
            "The pipe has been ended" // Windows ERROR_BROKEN_PIPE
        }) {
            assertTrue(
                    ResultWriteFailure.reportsBrokenPipe(new UncheckedIOException(new IOException(message))),
                    message);
        }
    }

    @Test
    void aBrokenPipeDeepInTheCauseChainStillReportsBrokenPipe() {
        UncheckedIOException wrapped =
                new UncheckedIOException(new IOException("write failed", new IOException("Broken pipe")));

        assertTrue(ResultWriteFailure.reportsBrokenPipe(wrapped));
    }

    @Test
    void otherWriteFailuresDoNotReportBrokenPipe() {
        assertFalse(ResultWriteFailure.reportsBrokenPipe(
                new UncheckedIOException(new IOException("No space left on device"))));
        assertFalse(ResultWriteFailure.reportsBrokenPipe(
                new UncheckedIOException(new IOException((String) null))));
        assertFalse(ResultWriteFailure.reportsBrokenPipe(
                new UncheckedIOException(new IOException("Connection reset by peer"))));
    }

    @Test
    void theBrokenPipeExitsSilentlyWhileEveryOtherWriteFailureKeepsTheTransportDiagnostic() {
        DiagnosticsCollector diagnostics = new DiagnosticsCollector();

        assertEquals(
                ExitCodeMapper.BROKEN_PIPE,
                ResultWriteFailure.exitFor(
                        new UncheckedIOException(new IOException("The pipe is being closed")), diagnostics, "web"),
                "the consumer's early departure is the silent success");
        assertEquals(List.of(), diagnostics.lines, "the silent early termination stays silent");

        assertEquals(
                ExitCodeMapper.forKind(FailureKind.TRANSPORT),
                ResultWriteFailure.exitFor(
                        new UncheckedIOException(new IOException("No space left on device")), diagnostics, "web"));
        assertEquals(
                List.of("web: writing the result document failed"),
                diagnostics.lines,
                "one diagnostic line names the failed write");
    }

    /** Collects every emitted diagnostic line. */
    private static final class DiagnosticsCollector implements DiagnosticsSink {

        final List<String> lines = new ArrayList<>();

        @Override
        public void emit(String diagnostic) {
            lines.add(diagnostic);
        }
    }
}

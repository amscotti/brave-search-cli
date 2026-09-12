package io.amscotti.bravesearch.adapter.cli.presentation;

/**
 * The advisory diagnostics channel: always stderr-bound in a real process, never the result
 * channel.
 *
 * <p>Warnings, progress, retry, and rate information travel here in the modes that own
 * stderr diagnostics; the sole failure explanation of a failing run also reaches the user
 * through this channel in human and raw modes. Injectable so presentation stays testable
 * against plain writers.
 */
public interface DiagnosticsSink {

    /**
     * Emits one diagnostic line: the message plus exactly one terminating LF.
     *
     * @throws java.io.UncheckedIOException if the underlying writer fails
     */
    void emit(String diagnostic);
}

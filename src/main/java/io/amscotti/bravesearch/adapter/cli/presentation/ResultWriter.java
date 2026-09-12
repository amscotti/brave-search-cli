package io.amscotti.bravesearch.adapter.cli.presentation;

/**
 * The result-document channel: always stdout-bound in a real process.
 *
 * <p>Documents are bytes because machine output is byte-exact — JSON envelopes, JSONL
 * lines, and raw bodies must reach the consumer without transcoding. Injectable so
 * presentation stays testable against plain streams.
 */
public interface ResultWriter {

    /**
     * Writes one complete document and flushes it.
     *
     * @throws java.io.UncheckedIOException if the underlying stream fails
     */
    void write(byte[] document);
}

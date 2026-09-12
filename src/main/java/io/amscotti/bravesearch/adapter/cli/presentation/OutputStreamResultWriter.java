package io.amscotti.bravesearch.adapter.cli.presentation;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Objects;

/** A {@link ResultWriter} over an injected {@link OutputStream}: writes, then flushes. */
public final class OutputStreamResultWriter implements ResultWriter {

    private final OutputStream stream;

    public OutputStreamResultWriter(OutputStream stream) {
        this.stream = Objects.requireNonNull(stream, "stream");
    }

    @Override
    public void write(byte[] document) {
        Objects.requireNonNull(document, "document");
        try {
            stream.write(document);
            stream.flush();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}

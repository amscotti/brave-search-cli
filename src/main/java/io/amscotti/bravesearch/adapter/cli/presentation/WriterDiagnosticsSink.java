package io.amscotti.bravesearch.adapter.cli.presentation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Objects;

/**
 * A {@link DiagnosticsSink} over an injected {@link Writer}: each diagnostic is one
 * LF-terminated line, flushed immediately so interleaving with result output stays
 * deterministic.
 */
public final class WriterDiagnosticsSink implements DiagnosticsSink {

    private final Writer writer;

    public WriterDiagnosticsSink(Writer writer) {
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    @Override
    public void emit(String diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        try {
            writer.write(diagnostic);
            writer.write("\n");
            writer.flush();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}

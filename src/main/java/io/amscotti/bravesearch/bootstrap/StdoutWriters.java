package io.amscotti.bravesearch.bootstrap;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * Writer factories for the process stdout.
 *
 * <p>Result documents — raw bodies, JSON envelopes, JSONL records, human listings — must
 * carry the operating system's own write-failure identity, because a downstream consumer
 * closing the pipe is only recognizable as EPIPE when the real {@code IOException} (on POSIX
 * carrying "Broken pipe") reaches the presenter's classification. A {@link PrintStream}
 * swallows write failures into its error flag and a {@link PrintWriter} wrapping one
 * swallows even the exception an adapter surfaces, so neither can carry that identity.
 * Document bytes therefore travel through {@link #byteStdout()}: a raw {@link
 * FileOutputStream} over the process's standard file descriptor, whose {@code write} throws
 * the genuine OS failure. The stream wraps an existing descriptor, opens nothing, and is
 * process-lifetime: it must never be closed.
 *
 * <p>{@link #passthroughBytes(PrintStream)} stays for help and version text, where nobody
 * classifies write failures: bytes map one-to-one through the ISO-8859-1 charset, and the
 * error-flag-to-exception adapter keeps the writer chain's failure state observable.
 */
final class StdoutWriters {

    private StdoutWriters() {}

    /**
     * The process stdout as a raw byte stream whose write failures are the operating
     * system's own; the returned stream is process-lifetime and never closed.
     */
    static OutputStream byteStdout() {
        return new FileOutputStream(FileDescriptor.out);
    }

    /**
     * A stdout writer for help and version text; result documents must use {@link #byteStdout()}.
     *
     * <p>The charset stays ISO-8859-1 because this same writer relays raw stream bytes —
     * the relay decodes each payload byte to one character and this encoding emits it back
     * unchanged, so the pairing is what makes the relay byte-lossless. Help and version text
     * therefore carries only characters Latin-1 can encode: anything wider mangles to {@code ?}
     * at run time, which the bootstrap help snapshots surface first because they render
     * through this very writer.
     */
    static PrintWriter passthroughBytes(PrintStream target) {
        return new PrintWriter(new OutputStreamWriter(new ErrorSurfacingOutput(target), StandardCharsets.ISO_8859_1));
    }

    /** Redirects byte writes to a print stream and converts its trapped error flag into an exception. */
    private static final class ErrorSurfacingOutput extends OutputStream {

        private final PrintStream target;

        ErrorSurfacingOutput(PrintStream target) {
            this.target = target;
        }

        @Override
        public void write(int b) throws IOException {
            target.write(b);
            failIfErrored();
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            target.write(b, off, len);
            failIfErrored();
        }

        @Override
        public void flush() throws IOException {
            target.flush();
            failIfErrored();
        }

        private void failIfErrored() throws IOException {
            if (target.checkError()) {
                throw new IOException("write to stdout failed");
            }
        }
    }
}

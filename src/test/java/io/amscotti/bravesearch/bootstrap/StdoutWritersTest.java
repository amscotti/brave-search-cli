package io.amscotti.bravesearch.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import org.junit.jupiter.api.Test;

/**
 * Failure observability of the help-and-version writer chain: a stdout that refuses bytes —
 * the closed-pipe shape — must stay visible as a writer error instead of vanishing into a
 * print stream's error flag.
 */
final class StdoutWritersTest {

    @Test
    void stdoutWriteFailuresStayObservable() {
        PrintWriter writer = StdoutWriters.passthroughBytes(new PrintStream(new RefusingStream()));

        writer.write("help");
        writer.flush();

        assertTrue(writer.checkError(), "a refused stdout write must surface on the writer");
    }

    @Test
    void healthyStdoutReportsNoError() {
        PrintWriter writer = StdoutWriters.passthroughBytes(new PrintStream(OutputStream.nullOutputStream()));

        writer.write("help");
        writer.flush();

        assertFalse(writer.checkError(), "a working stdout must not report an error");
    }

    /** A stdout that refuses every byte the way a closed pipe does. */
    private static final class RefusingStream extends OutputStream {

        @Override
        public void write(int ignored) throws IOException {
            throw new IOException("broken pipe");
        }
    }
}

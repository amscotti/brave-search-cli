package io.amscotti.bravesearch.adapter.config;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.config.LocalConfigException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The secret-input contract of {@code config set-key}: the no-echo console path validates without
 * echoing, and the {@code --stdin} path accepts exactly one nonempty UTF-8 line terminated by LF
 * or CRLF — stripping only that terminator — while rejecting end of file before the terminator,
 * control characters, invalid UTF-8, lines above the byte bound, and any non-whitespace after
 * the line. Every failure is the typed secret-input configuration error and never carries the
 * offered token material.
 */
final class SecretInputTest {

    private static final int MAX_LINE_BYTES = Credential.MAX_BYTES;

    @Test
    void consolePasswordBecomesTheCredentialUntouched() {
        char[] secret = "BSA-console-sentinel".toCharArray();

        Credential read = new SecretInput(console(secret), stdin("")).read(false);

        assertEquals("BSA-console-sentinel", new String(read.tokenBytes(), UTF_8));
    }

    @Test
    void consoleInputIsEncodedAsUtf8Bytes() {
        Credential read = new SecretInput(console("ключ-キー-\uD83E\uDDEA".toCharArray()), stdin("")).read(false);

        assertEquals("ключ-キー-\uD83E\uDDEA", new String(read.tokenBytes(), UTF_8));
    }

    @Test
    void consoleSecretThatCannotBeEncodedAsUtf8IsAValidationFailure() {
        LocalConfigException failure = assertThrows(
                LocalConfigException.class,
                () -> new SecretInput(console(new char[] {'a', '\uD800', 'b'}), stdin("")).read(false));

        assertEquals(LocalConfigException.Reason.SECRET_INPUT, failure.reason());
        assertTrue(failure.getMessage().contains("UTF-8"), () -> failure.getMessage());
    }

    @Test
    void missingConsoleWithoutStdinIsTheTypedSecretInputFailure() {
        LocalConfigException failure =
                assertThrows(LocalConfigException.class, () -> new SecretInput(console(null), stdin("")).read(false));

        assertEquals(LocalConfigException.Reason.SECRET_INPUT, failure.reason());
        assertTrue(failure.getMessage().contains("console"), () -> failure.getMessage());
        assertTrue(failure.getMessage().contains("--stdin"), () -> failure.getMessage());
    }

    @Test
    void invalidConsoleSecretFailsTypedWithoutEchoingIt() {
        String sentinel = sentinel();

        LocalConfigException failure = assertThrows(
                LocalConfigException.class,
                () -> new SecretInput(console(("\u0007" + sentinel).toCharArray()), stdin("")).read(false));

        assertEquals(LocalConfigException.Reason.SECRET_INPUT, failure.reason());
        assertFalse(failure.getMessage().contains(sentinel), "the failure must not carry token material");
    }

    @Test
    void whitespaceOnlyConsoleSecretIsRejected() {
        LocalConfigException failure = assertThrows(
                LocalConfigException.class,
                () -> new SecretInput(console(" \t ".toCharArray()), stdin("")).read(false));

        assertEquals(LocalConfigException.Reason.SECRET_INPUT, failure.reason());
    }

    @Test
    void stdinAcceptsExactlyOneLfTerminatedLine() {
        String sentinel = sentinel();

        Credential read = new SecretInput(console(null), stdin(sentinel + "\n")).read(true);

        assertArrayEquals(sentinel.getBytes(UTF_8), read.tokenBytes());
    }

    @Test
    void stdinAcceptsCrlfAndStripsOnlyTheTerminator() {
        Credential read = new SecretInput(console(null), stdin("sentinel-line\r\n")).read(true);

        assertEquals("sentinel-line", new String(read.tokenBytes(), UTF_8));
    }

    @Test
    void stdinPreservesWhitespaceAroundAndInsideTheLine() {
        Credential read = new SecretInput(console(null), stdin(" two words \n")).read(true);

        assertEquals(" two words ", new String(read.tokenBytes(), UTF_8));
    }

    @Test
    void stdinEofBeforeTheTerminatorIsRejected() {
        LocalConfigException failure = assertThrows(
                LocalConfigException.class,
                () -> new SecretInput(console(null), stdin("sentinel-no-newline")).read(true));

        assertEquals(LocalConfigException.Reason.SECRET_INPUT, failure.reason());
        assertTrue(failure.getMessage().contains("terminat"), () -> failure.getMessage());
    }

    @Test
    void stdinRejectsNonWhitespaceAfterTheLine() {
        assertStdinRejected("first\nsecond\n");
        assertStdinRejected("first\n tail");
    }

    @Test
    void stdinAcceptsBlankLinesAndWhitespaceAfterTheLine() {
        assertEquals(
                "sentinel-line",
                new String(new SecretInput(console(null), stdin("sentinel-line\n\n\n")).read(true)
                        .tokenBytes(),
                        UTF_8));
        assertEquals(
                "sentinel-line",
                new String(new SecretInput(console(null), stdin("sentinel-line\r\n \t\n")).read(true)
                        .tokenBytes(),
                        UTF_8));
    }

    @Test
    void stdinRejectsControlCharactersInsideTheLine() {
        String sentinel = sentinel();

        assertArrayEquals(new byte[0], new byte[0]);
        LocalConfigException withNul = assertThrows(
                LocalConfigException.class,
                () -> new SecretInput(console(null), stdin("bad\u0000" + sentinel + "\n")).read(true));
        LocalConfigException withBel = assertThrows(
                LocalConfigException.class,
                () -> new SecretInput(console(null), stdin("bad\u0007" + sentinel + "\n")).read(true));
        LocalConfigException withBareCr = assertThrows(
                LocalConfigException.class,
                () -> new SecretInput(console(null), stdin("bad\rrest\n")).read(true));

        assertEquals(LocalConfigException.Reason.SECRET_INPUT, withNul.reason());
        assertEquals(LocalConfigException.Reason.SECRET_INPUT, withBel.reason());
        assertEquals(LocalConfigException.Reason.SECRET_INPUT, withBareCr.reason());
        assertFalse(withNul.getMessage().contains(sentinel));
        assertFalse(withBel.getMessage().contains(sentinel));
    }

    @Test
    void stdinRejectsInvalidUtf8() {
        LocalConfigException failure = assertThrows(
                LocalConfigException.class,
                () -> new SecretInput(
                                console(null),
                                stdin(new byte[] {(byte) 0xC3, 0x28, (byte) 0xA9, 0x0A}))
                        .read(true));

        assertEquals(LocalConfigException.Reason.SECRET_INPUT, failure.reason());
        assertTrue(failure.getMessage().contains("UTF-8"), () -> failure.getMessage());
    }

    @Test
    void stdinRejectsALineAboveTheByteBoundButAcceptsTheBoundItself() {
        StringBuilder oversized = new StringBuilder();
        oversized.repeat('a', MAX_LINE_BYTES + 1);

        LocalConfigException failure =
                assertThrows(LocalConfigException.class, () -> stdinRead(oversized + "\n"));

        assertEquals(LocalConfigException.Reason.SECRET_INPUT, failure.reason());

        StringBuilder atBound = new StringBuilder();
        atBound.repeat('a', MAX_LINE_BYTES);
        assertEquals(
                MAX_LINE_BYTES,
                new String(stdinRead(atBound + "\n").tokenBytes(), UTF_8).length());
    }

    @Test
    void stdinTreatsTheCrlfCrAsTerminatorAtTheByteBound() {
        StringBuilder atBound = new StringBuilder();
        atBound.repeat('a', MAX_LINE_BYTES);

        assertEquals(
                MAX_LINE_BYTES,
                new String(stdinRead(atBound + "\r\n").tokenBytes(), UTF_8).length());

        StringBuilder oneByteMore = new StringBuilder();
        oneByteMore.repeat('a', MAX_LINE_BYTES + 1);

        LocalConfigException failure =
                assertThrows(LocalConfigException.class, () -> stdinRead(oneByteMore + "\r\n"));

        assertEquals(LocalConfigException.Reason.SECRET_INPUT, failure.reason());
    }

    @Test
    void stdinRejectsAnEmptyLine() {
        LocalConfigException failure = assertThrows(LocalConfigException.class, () -> stdinRead("\n"));

        assertEquals(LocalConfigException.Reason.SECRET_INPUT, failure.reason());
    }

    @Test
    void stdinReadFailureIsTheTypedSecretInputFailure() {
        LocalConfigException failure = assertThrows(
                LocalConfigException.class,
                () -> new SecretInput(
                                console(null),
                                new InputStream() {
                                    @Override
                                    public int read() throws IOException {
                                        throw new IOException("sentinel-io-message");
                                    }
                                })
                        .read(true));

        assertEquals(LocalConfigException.Reason.SECRET_INPUT, failure.reason());
        assertFalse(failure.getMessage().contains("sentinel-io-message"), "the stream's own message must not surface");
    }

    @Test
    void interruptedStdinReadRestoresTheInterruptFlagAndFailsTyped() {
        Thread.currentThread().interrupt();

        LocalConfigException failure = assertThrows(
                LocalConfigException.class,
                () -> new SecretInput(
                                console(null),
                                new InputStream() {
                                    @Override
                                    public int read() throws IOException {
                                        throw new InterruptedIOException();
                                    }
                                })
                        .read(true));

        assertTrue(Thread.interrupted(), "the interrupt flag must be restored for the caller");
        assertEquals(LocalConfigException.Reason.SECRET_INPUT, failure.reason());
    }

    @Test
    void noFailureMessageEverCarriesTheOfferedToken() {
        String sentinel = sentinel();

        LocalConfigException terminatorMissing = assertThrows(
                LocalConfigException.class, () -> stdinRead(sentinel));
        LocalConfigException nonWhitespaceAfter = assertThrows(
                LocalConfigException.class, () -> stdinRead(sentinel + "\nmore"));
        LocalConfigException oversized = null;
        try {
            stdinRead(sentinel.repeat(200) + "\n");
        } catch (LocalConfigException expected) {
            oversized = expected;
        }

        assertFalse(terminatorMissing.getMessage().contains(sentinel));
        assertFalse(nonWhitespaceAfter.getMessage().contains(sentinel));
        assertTrue(oversized != null || sentinel.isEmpty(), "the oversized line must be rejected");
        assertFalse(oversized.getMessage().contains(sentinel));
    }

    private static Credential stdinRead(String text) {
        return new SecretInput(console(null), stdin(text)).read(true);
    }

    private static void assertStdinRejected(String raw) {
        LocalConfigException failure = assertThrows(
                LocalConfigException.class, () -> new SecretInput(console(null), stdin(raw)).read(true));
        assertEquals(LocalConfigException.Reason.SECRET_INPUT, failure.reason());
    }

    private static Credential stdinRead(byte[] bytes) {
        return new SecretInput(console(null), new ByteArrayInputStream(bytes)).read(true);
    }

    private static SecretInput.ConsoleAccess console(char[] password) {
        return () -> Optional.ofNullable(password);
    }

    private static InputStream stdin(String text) {
        return stdin(text.getBytes(StandardCharsets.UTF_8));
    }

    private static InputStream stdin(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    /**
     * A unique random-looking sentinel per invocation: it is embedded in token material under
     * test, so any appearance in a message or rendering is a leak of the token itself.
     */
    private static String sentinel() {
        return "BSK" + Long.toUnsignedString(UUID.randomUUID().getMostSignificantBits(), 36)
                + Long.toUnsignedString(UUID.randomUUID().getLeastSignificantBits(), 36);
    }
}

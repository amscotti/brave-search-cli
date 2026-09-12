package io.amscotti.bravesearch.adapter.cli.presentation;

/**
 * The terminal-safety rule of human documents: every upstream-derived string is sanitized
 * before it reaches stdout, because a hostile response can otherwise forge results —
 * clickable OSC-8 hyperlinks, cursor movement, erased lines — through the text it serves.
 *
 * <p>The rule, decided once and documented in the CLI contract: a complete escape sequence
 * — CSI ({@code ESC [ …} final byte), OSC ({@code ESC ] …} terminated by BEL or
 * {@code ESC \}, including OSC-8 hyperlinks), and the two-character {@code ESC} forms — is
 * removed wholly, escape byte and payload together, so a forged sequence leaves nothing
 * behind; every other C0 or C1 control character except the line feed is replaced by
 * U+FFFD, so a lone carriage return or BEL still shows the reader that a character was
 * served without moving the cursor or ringing the bell; and the line feed alone survives,
 * because it carries the document's line structure. An unterminated OSC sequence runs to
 * the end of the string: its remainder is attacker payload by definition.
 *
 * <p>Machine documents never pass through here — their codecs escape control characters by
 * construction — and raw output relays the served bytes untouched.
 */
public final class TerminalSafeText {

    private static final char ESC = '\u001b';
    private static final char BEL = '\u0007';
    private static final char REPLACEMENT = '\ufffd';

    private TerminalSafeText() {}

    /**
     * The text as a human document renders it: complete escape sequences removed wholly,
     * every other control except the line feed replaced by U+FFFD, all other characters
     * unchanged.
     */
    public static String sanitize(String text) {
        java.util.Objects.requireNonNull(text, "text");
        if (text.isEmpty()) {
            return text;
        }
        StringBuilder safe = new StringBuilder(text.length());
        int i = 0;
        int length = text.length();
        while (i < length) {
            char c = text.charAt(i);
            if (c == ESC) {
                i = skipEscapeSequence(text, i);
            } else if (isControl(c)) {
                safe.append(REPLACEMENT);
                i++;
            } else {
                safe.append(c);
                i++;
            }
        }
        return safe.toString();
    }

    /**
     * The index just past the escape sequence starting at {@code start}: CSI consumes its
     * parameter, intermediate, and final bytes; OSC consumes everything through its BEL or
     * ST terminator or the end of the string; every other printable follower forms the
     * two-character escape with the ESC; a control follower is left to its own rule.
     */
    private static int skipEscapeSequence(String text, int start) {
        int afterEscape = start + 1;
        if (afterEscape >= text.length()) {
            return afterEscape;
        }
        char follower = text.charAt(afterEscape);
        if (follower == '[') {
            return skipCsi(text, afterEscape + 1);
        }
        if (follower == ']') {
            return skipOsc(text, afterEscape + 1);
        }
        if (follower >= 0x20 && follower <= 0x7e) {
            return afterEscape + 1;
        }
        // not a sequence follower (another control or a C1 byte): drop only the ESC
        return afterEscape;
    }

    /** The index past the CSI final byte: parameters (0x30-0x3F), intermediates (0x20-0x2F), final (0x40-0x7E). */
    private static int skipCsi(String text, int from) {
        int i = from;
        while (i < text.length()) {
            char c = text.charAt(i);
            if ((c >= 0x30 && c <= 0x3f) || (c >= 0x20 && c <= 0x2f)) {
                i++;
            } else if (c >= 0x40 && c <= 0x7e) {
                return i + 1;
            } else {
                // a malformed CSI ends at the first byte that cannot belong to it
                return i;
            }
        }
        return i;
    }

    /**
     * The index past the OSC terminator: BEL or the ST of {@code ESC \}; an unterminated
     * OSC consumes the rest of the string, because everything after it is the sequence's
     * own payload.
     */
    private static int skipOsc(String text, int from) {
        int i = from;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == BEL) {
                return i + 1;
            }
            if (c == ESC && i + 1 < text.length() && text.charAt(i + 1) == '\\') {
                return i + 2;
            }
            i++;
        }
        return i;
    }

    /** Whether {@code c} is a C0 control (except LF), DEL, or a C1 control. */
    private static boolean isControl(char c) {
        return (c <= '\u001f' && c != '\n') || c == '\u007f' || (c >= '\u0080' && c <= '\u009f');
    }
}

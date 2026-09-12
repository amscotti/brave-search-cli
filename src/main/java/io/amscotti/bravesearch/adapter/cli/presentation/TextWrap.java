package io.amscotti.bravesearch.adapter.cli.presentation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The word-wrapping rule of human document text: lines are filled by whole words up to a
 * width counted in code points — an astral character is one column, never two — a word
 * longer than the width overflows on its own line instead of breaking, and the text's own
 * line feeds stay hard breaks the wrapper never joins.
 *
 * <p>The wrapper measures plain text only: every caller sanitizes upstream text first
 * (the terminal-safety rule) and styles after wrapping, so no escape sequence is ever
 * part of a measurement and no measured byte is invisible to a terminal. Urls are never
 * wrapped at all — a url that exceeds the width overflows whole.
 */
public final class TextWrap {

    private TextWrap() {}

    /**
     * The text as whole-word lines of at most {@code width} code points each, with the
     * text's own line feeds kept as hard breaks and a word too long for the width placed
     * alone on its own overflowing line.
     *
     * @param text the plain text to wrap; never {@code null}
     * @param width the maximum code points per line; must be positive
     */
    public static List<String> words(String text, int width) {
        Objects.requireNonNull(text, "text");
        if (width < 1) {
            throw new IllegalArgumentException("wrap width must be positive: " + width);
        }
        List<String> lines = new ArrayList<>();
        String[] segments = text.split("\n", -1);
        for (int segment = 0; segment < segments.length; segment++) {
            if (segments[segment].isBlank()) {
                // an empty segment is a hard break the upstream text carried; a trailing
                // one adds no trailing blank line
                if (segment != segments.length - 1 && !lines.isEmpty()) {
                    lines.add("");
                }
                continue;
            }
            wrapSegment(segments[segment], width, lines);
        }
        return lines;
    }

    private static void wrapSegment(String segment, int width, List<String> lines) {
        StringBuilder current = null;
        for (String word : segment.split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            if (current == null) {
                current = new StringBuilder(word);
                continue;
            }
            if (codePoints(current) + 1 + codePoints(word) <= width) {
                current.append(' ').append(word);
            } else {
                lines.add(current.toString());
                current = new StringBuilder(word);
            }
        }
        if (current != null) {
            lines.add(current.toString());
        }
    }

    private static int codePoints(CharSequence text) {
        return (int) text.codePoints().count();
    }
}

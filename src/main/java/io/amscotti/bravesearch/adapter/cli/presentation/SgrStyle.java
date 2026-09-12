package io.amscotti.bravesearch.adapter.cli.presentation;

import io.amscotti.bravesearch.domain.output.OutputRequest;
import java.util.Objects;

/**
 * The SGR style vocabulary of human documents: the two intensities the CLI styles with —
 * bold (SGR 1) and dim (SGR 2) — each emitted only when the invocation's render context
 * is colorable, and passed through untouched otherwise, so a redirected stream can never
 * receive an escape byte. The reset that closes a wrap is SGR 0.
 *
 * <p>The helper styles plain text only. Callers sanitize their text first and wrap it
 * before styling, so no escape sequence is ever measured by layout code and no style is
 * ever nested inside another; the heading wrap of {@link HeadingStyle} is built on the
 * bold here, keeping its own bytes unchanged in both render contexts.
 */
public final class SgrStyle {

    private static final String BOLD = "\u001b[1m";
    private static final String DIM = "\u001b[2m";
    private static final String RESET = "\u001b[0m";

    private SgrStyle() {}

    /**
     * The text in the bold intensity when {@code output} is colorable, and the text
     * itself otherwise.
     *
     * @param text the plain text to style; never {@code null}
     * @param output the invocation's parsed output state carrying the color decision
     */
    public static String bold(String text, OutputRequest output) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(output, "output");
        return output.colorable() ? BOLD + text + RESET : text;
    }

    /**
     * The text in the dim intensity when {@code output} is colorable, and the text
     * itself otherwise.
     *
     * @param text the plain text to style; never {@code null}
     * @param output the invocation's parsed output state carrying the color decision
     */
    public static String dim(String text, OutputRequest output) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(output, "output");
        return output.colorable() ? DIM + text + RESET : text;
    }
}

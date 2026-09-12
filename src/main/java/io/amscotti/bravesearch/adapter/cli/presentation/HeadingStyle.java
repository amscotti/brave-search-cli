package io.amscotti.bravesearch.adapter.cli.presentation;

import io.amscotti.bravesearch.domain.output.OutputRequest;
import java.util.Objects;

/**
 * The heading wrap of human documents: the heading text passes the terminal-safety rule
 * first — its documented precondition of carrying no escapes is upheld for every query
 * spelling — and then renders bold through the shared SGR style exactly when the
 * invocation's output state enabled color, and plain otherwise. Stderr diagnostics carry
 * no heading, so a redirected stream can never receive an escape byte from here.
 */
public final class HeadingStyle {

    private HeadingStyle() {}

    /**
     * The heading as the invocation's output state renders it on stdout: the heading text
     * passes the terminal-safety rule first, and only the shared bold wrap ever styles
     * the line.
     *
     * @param heading the plain heading text
     * @param output the invocation's parsed output state carrying the color decision
     */
    public static String bold(String heading, OutputRequest output) {
        Objects.requireNonNull(heading, "heading");
        Objects.requireNonNull(output, "output");
        return SgrStyle.bold(TerminalSafeText.sanitize(heading), output);
    }
}

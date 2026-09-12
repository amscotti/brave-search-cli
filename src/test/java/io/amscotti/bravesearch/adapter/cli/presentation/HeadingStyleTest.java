package io.amscotti.bravesearch.adapter.cli.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.amscotti.bravesearch.domain.output.OutputMode;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import org.junit.jupiter.api.Test;

/**
 * The one styling decision of human documents: a heading carries the ANSI bold intensity
 * escape exactly when the invocation's output state enabled color, and renders plain
 * otherwise. Machine channels never reach this decision, because only human documents
 * have headings.
 */
final class HeadingStyleTest {

    @Test
    void aPlainInvocationKeepsTheHeadingBytePlain() {
        assertEquals(
                "Web results for: three word query",
                HeadingStyle.bold("Web results for: three word query", plain()));
    }

    @Test
    void aColorInvocationWrapsTheHeadingInBoldIntensityOnly() {
        assertEquals(
                "\u001b[1mWeb results for: three word query\u001b[0m",
                HeadingStyle.bold("Web results for: three word query", colored()));
    }

    private static OutputRequest plain() {
        return new OutputRequest(false, OutputMode.HUMAN, false, false, false);
    }

    private static OutputRequest colored() {
        return new OutputRequest(false, OutputMode.HUMAN, false, false, false, true);
    }
}

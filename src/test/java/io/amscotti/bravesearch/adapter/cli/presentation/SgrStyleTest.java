package io.amscotti.bravesearch.adapter.cli.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.amscotti.bravesearch.domain.output.OutputMode;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import org.junit.jupiter.api.Test;

/**
 * The SGR style vocabulary of human documents: bold and dim wrap their text in the
 * intensity escapes exactly when the invocation's output state is colorable, and pass the
 * text through untouched otherwise, so a redirected stream can never receive an escape
 * byte. The helper styles plain text only — callers sanitize and wrap first, so no escape
 * is ever measured or nested.
 */
final class SgrStyleTest {

    @Test
    void boldWrapsTheTextInTheBoldIntensityEscapeWhenColorable() {
        assertEquals("\u001b[1mFirst Title\u001b[0m", SgrStyle.bold("First Title", colorable()));
    }

    @Test
    void dimWrapsTheTextInTheDimIntensityEscapeWhenColorable() {
        assertEquals("\u001b[2mhttps://example.com/first\u001b[0m", SgrStyle.dim("https://example.com/first", colorable()));
    }

    @Test
    void aPlainRenderContextPassesTheTextThroughUntouched() {
        assertEquals("First Title", SgrStyle.bold("First Title", plain()));
        assertEquals("https://example.com/first", SgrStyle.dim("https://example.com/first", plain()));
    }

    @Test
    void astralTextTravelsUnchangedInsideTheStyleWrap() {
        String emoji = "\ud83d\udc1d\ud83d\udc1d bee";
        assertEquals("\u001b[1m" + emoji + "\u001b[0m", SgrStyle.bold(emoji, colorable()));
        assertEquals(emoji, SgrStyle.dim(emoji, plain()));
    }

    private static OutputRequest plain() {
        return new OutputRequest(false, OutputMode.HUMAN, false, false, false, false, 100);
    }

    private static OutputRequest colorable() {
        return new OutputRequest(false, OutputMode.HUMAN, false, false, false, true, 100);
    }
}

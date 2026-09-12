package io.amscotti.bravesearch.adapter.cli.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The terminal-safety rule of every upstream-derived string a human document renders:
 * complete escape sequences — CSI, OSC (including OSC-8 hyperlinks), and two-character
 * escapes — are removed wholly, every other C0 or C1 control except the line feed is
 * replaced by U+FFFD so the reader sees that something was there, and the line feed keeps
 * the document's line structure. Plain text, including every non-control Unicode
 * character, passes through unchanged.
 */
final class TerminalSafeTextTest {

    @Test
    void plainTextPassesThroughUnchanged() {
        assertEquals("café ☕ déjà vu", TerminalSafeText.sanitize("café ☕ déjà vu"));
        assertEquals("", TerminalSafeText.sanitize(""));
    }

    @Test
    void aTabBecomesAReplacementCharacterLikeEveryOtherControl() {
        assertEquals("a\ufffdb", TerminalSafeText.sanitize("a\tb"));
    }

    @Test
    void anOscEightHyperlinkIsRemovedWholly() {
        String forged = "click \u001b]8;;https://evil.example\u001b\\here\u001b]8;;\u001b\\ please";
        assertEquals("click here please", TerminalSafeText.sanitize(forged));
    }

    @Test
    void anOscHyperlinkTerminatedByBelIsRemovedWholly() {
        String forged = "a\u001b]8;;https://evil.example\u0007b";
        assertEquals("ab", TerminalSafeText.sanitize(forged));
    }

    @Test
    void csiSequencesAreRemovedWholly() {
        String forged = "x\u001b[2Jy\u001b[10;5Hz\u001b[?25lw";
        assertEquals("xyzw", TerminalSafeText.sanitize(forged));
    }

    @Test
    void carriageReturnsAndOtherC0ControlsBecomeReplacementCharacters() {
        assertEquals("a\ufffdb", TerminalSafeText.sanitize("a\rb"));
        assertEquals("a\ufffdb", TerminalSafeText.sanitize("a\u0000b"));
        assertEquals("a\ufffdb", TerminalSafeText.sanitize("a\u0007b"));
        assertEquals("a\ufffdb", TerminalSafeText.sanitize("a\u007fb"));
    }

    @Test
    void c1ControlsBecomeReplacementCharacters() {
        assertEquals("a\ufffdb", TerminalSafeText.sanitize("a\u009bb"));
        assertEquals("a\ufffdb", TerminalSafeText.sanitize("a\u0085b"));
    }

    @Test
    void theLineFeedSurvives() {
        assertEquals("a\nb", TerminalSafeText.sanitize("a\nb"));
    }

    @Test
    void aLoneEscapeAtTheEndIsRemoved() {
        assertEquals("ab", TerminalSafeText.sanitize("ab\u001b"));
    }

    @Test
    void anEscapeBeforeAControlLeavesOnlyTheReplacementCharacter() {
        assertEquals("a\ufffdb", TerminalSafeText.sanitize("a\u001b\rb"));
    }

    @Test
    void anUnterminatedOscSequenceIsRemovedToTheEnd() {
        assertEquals("a", TerminalSafeText.sanitize("a\u001b]8;;still open"));
    }
}

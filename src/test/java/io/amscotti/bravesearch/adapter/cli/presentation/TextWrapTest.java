package io.amscotti.bravesearch.adapter.cli.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The word-wrapping rule of human document text: lines are filled by whole words up to a
 * width counted in code points — an astral character is one column, never two — a word
 * longer than the width overflows on its own line instead of breaking, and the text's own
 * line feeds stay hard breaks. The wrapper measures plain text only: callers sanitize
 * before wrapping, so no escape sequence is ever part of a measurement.
 *
 * <p>The parameterized edge pins hold the wrapper to word integrity across writing
 * systems: a spaceless-script run (CJK) is one word that overflows whole rather than
 * breaking at the width, and a combining mark or a zero-width joiner never separates
 * from the cluster it belongs to — the wrapper only ever breaks at spaces, so a wrapped
 * line can never open with an orphaned mark or a half-dismantled emoji sequence.
 */
final class TextWrapTest {

    /** One emoji family joined by zero-width joiners: three astral pairs and two joiners. */
    private static final String ZWJ_FAMILY = "\ud83d\udc68\u200d\ud83d\udc69\u200d\ud83d\udc66";

    @Test
    void plainTextFillsLinesByWholeWordsUpToTheWidth() {
        assertEquals(List.of("aaa bbb", "ccc"), TextWrap.words("aaa bbb ccc", 7));
    }

    @Test
    void aLineThatFitsExactlyStaysOneLine() {
        assertEquals(List.of("aaaa bbb"), TextWrap.words("aaaa bbb", 8));
    }

    @Test
    void aWordLongerThanTheWidthOverflowsOnItsOwnLineUnbroken() {
        assertEquals(List.of("abcdefghij", "xy"), TextWrap.words("abcdefghij xy", 4));
    }

    @Test
    void wrappingCountsCodePointsSoAstralCharactersAreSingleColumns() {
        String fiveEmoji = "\ud83d\udc1d\ud83d\udc1d\ud83d\udc1d\ud83d\udc1d\ud83d\udc1d";
        assertEquals(List.of(fiveEmoji, "next"), TextWrap.words(fiveEmoji + " next", 5));
    }

    @Test
    void theTextsOwnLineFeedsStayHardBreaks() {
        assertEquals(List.of("one", "two"), TextWrap.words("one\ntwo", 10));
        assertEquals(List.of("aaa", "bbb ccc"), TextWrap.words("aaa\nbbb ccc", 7));
    }

    @Test
    void aTrailingLineFeedAddsNoTrailingWrappedLine() {
        assertEquals(List.of("abc"), TextWrap.words("abc\n", 10));
    }

    @Test
    void blankInputWrapsToNothing() {
        assertEquals(List.of(), TextWrap.words("", 10));
    }

    @Test
    void runsOfSpacesAreSingleBreakPoints() {
        assertEquals(List.of("aaa bbb", "ccc"), TextWrap.words("aaa  bbb   ccc", 7));
    }

    @Test
    void aNonPositiveWidthIsRejectedAsACallerBug() {
        assertThrows(IllegalArgumentException.class, () -> TextWrap.words("abc", 0));
    }

    static Stream<Arguments> scriptAndClusterEdgeTexts() {
        return Stream.of(
                Arguments.of("東京都渋谷区", 3, List.of("東京都渋谷区"), "a spaceless-script run is one word that overflows whole"),
                Arguments.of(
                        "東京都渋谷区 次の文",
                        3,
                        List.of("東京都渋谷区", "次の文"),
                        "each spaceless run wraps as its own overflowing word"),
                Arguments.of(
                        "cafe\u0301 next",
                        8,
                        List.of("cafe\u0301", "next"),
                        "a combining mark stays on its base's side of the break"),
                Arguments.of(
                        "cafe\u0301 next",
                        9,
                        List.of("cafe\u0301", "next"),
                        "the straddle that discriminates the measure: nine display columns would fit"
                                + " the pair whole, nine code points do not, and the count is code points"),
                Arguments.of(
                        "abcdef\u0301 tail",
                        6,
                        List.of("abcdef\u0301", "tail"),
                        "an over-wide word carries its combining mark along, never split"),
                Arguments.of(
                        ZWJ_FAMILY + " tail",
                        8,
                        List.of(ZWJ_FAMILY, "tail"),
                        "a zero-width-joined sequence is one word at its exact code-point count"),
                Arguments.of(
                        ZWJ_FAMILY + " tail",
                        7,
                        List.of(ZWJ_FAMILY, "tail"),
                        "a joined sequence that fits alone but not beside its neighbor keeps its own"
                                + " whole line, never split at a joiner"));
    }

    @ParameterizedTest(name = "[{index}] {4}")
    @MethodSource("scriptAndClusterEdgeTexts")
    void scriptAndClusterEdgesKeepWordIntegrity(String text, int width, List<String> expected, String behavior) {
        assertEquals(expected, TextWrap.words(text, width), behavior);
    }
}

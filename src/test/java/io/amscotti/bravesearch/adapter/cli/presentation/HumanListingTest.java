package io.amscotti.bravesearch.adapter.cli.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.amscotti.bravesearch.domain.metadata.RateLimitWindow;
import io.amscotti.bravesearch.domain.output.HumanMember;
import io.amscotti.bravesearch.domain.output.OutputMode;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The shared entry layout of every human listing: the two-space-gutter index column
 * right-aligned to the widest index, the title bold and wrapped with continuations
 * aligned under it, the url dim and never wrapped, body text wrapped at the render
 * width, metadata as the final dim lines, and the advisory quota footer computed from
 * the exchange's rate-limit windows — one trailing dim line exactly when the most
 * restrictive applicable window is nearly exhausted and quiet did not suppress it.
 */
final class HumanListingTest {

    @Test
    void theIndexIsRightAlignedToTheWidestIndexInTheListing() {
        assertEquals("  1  ", HumanListing.indexPrefix(1, 10));
        assertEquals(" 10  ", HumanListing.indexPrefix(10, 10));
        assertEquals("   1  ", HumanListing.indexPrefix(1, 112));
        assertEquals(" 112  ", HumanListing.indexPrefix(112, 112));
    }

    @Test
    void aSingleDigitListingKeepsItsNarrowIndexColumn() {
        assertEquals(" 1  ", HumanListing.indexPrefix(1, 3));
        assertEquals(" 3  ", HumanListing.indexPrefix(3, 3));
        assertEquals(4, HumanListing.memberIndent(3));
        assertEquals(5, HumanListing.memberIndent(10));
    }

    @Test
    void theTitleWrapsAtTheWidthMinusTheGutterWithContinuationsAlignedUnderIt() {
        StringBuilder document = new StringBuilder();
        HumanListing.appendTitle(document, 1, 3, WRAPS_AT_THIRTY_SIX, narrow());
        assertEquals(" 1  aaaaaaaaaaaa bbbbbbbbbbbb\n    cccccccccccc dddddddddddd\n", document.toString());
    }

    @Test
    void theTitleIsBoldOnEveryWrappedLineWhenTheRenderContextIsColorable() {
        StringBuilder document = new StringBuilder();
        HumanListing.appendTitle(document, 1, 3, WRAPS_AT_THIRTY_SIX, narrowColorable());
        assertEquals(
                " 1  \u001b[1maaaaaaaaaaaa bbbbbbbbbbbb\u001b[0m\n    \u001b[1mcccccccccccc dddddddddddd\u001b[0m\n",
                document.toString());
    }

    @Test
    void aTitleThatSanitizesAwayToNothingFallsBackToTheUntitledPlaceholder() {
        StringBuilder document = new StringBuilder();
        HumanListing.appendTitle(document, 1, 3, "\u001b[2J", plain());
        assertEquals(" 1  (no title)\n", document.toString());
    }

    @Test
    void theUrlLineIsDimAndNeverWrappedEvenBeyondTheRenderWidth() {
        String longUrl = "https://example.com/" + "a".repeat(60);
        StringBuilder document = new StringBuilder();
        HumanListing.appendUrlLine(document, 3, longUrl, narrow());
        assertEquals("    " + longUrl + "\n", document.toString());

        StringBuilder colored = new StringBuilder();
        HumanListing.appendUrlLine(colored, 3, longUrl, narrowColorable());
        assertEquals("    \u001b[2m" + longUrl + "\u001b[0m\n", colored.toString());
    }

    @Test
    void bodyTextWrapsAtTheWidthMinusTheGutterAtDefaultIntensity() {
        StringBuilder document = new StringBuilder();
        HumanListing.appendTextLines(document, 3, WRAPS_AT_THIRTY_SIX, narrow());
        assertEquals("    aaaaaaaaaaaa bbbbbbbbbbbb\n    cccccccccccc dddddddddddd\n", document.toString());
    }

    @Test
    void metadataRendersAsDimLines() {
        StringBuilder document = new StringBuilder();
        HumanListing.appendMetaLines(document, 3, "2 hours ago", narrowColorable());
        assertEquals("    \u001b[2m2 hours ago\u001b[0m\n", document.toString());
    }

    @Test
    void membersRenderInEncounterOrderExceptMetadataWhichClosesTheBlock() {
        List<HumanMember> members = List.of(
                new HumanMember("https://example.com/first", HumanMember.Kind.URL),
                new HumanMember("First description text.", HumanMember.Kind.TEXT),
                new HumanMember("2 hours ago", HumanMember.Kind.META));
        StringBuilder document = new StringBuilder();
        HumanListing.appendMembers(document, 3, members, plain());
        assertEquals(
                "    https://example.com/first\n    First description text.\n    2 hours ago\n",
                document.toString());
    }

    @Test
    void sanitizingHappensBeforeWrappingSoEscapeBytesNeverMoveABreak() {
        // the cursor-wipe sequence is removed wholly first, so the wrap measures
        // "abcdef ghijklmnopq rst uvwxyz abcdefghij" — not the escape's characters
        StringBuilder document = new StringBuilder();
        HumanListing.appendTextLines(document, 3, "abc\u001b[2Jdef ghijklmnopq rst uvwxyz abcdefghij", narrow());
        assertEquals("    abcdef ghijklmnopq rst uvwxyz\n    abcdefghij\n", document.toString());
    }

    @Test
    void theQuotaFooterNamesTheMostRestrictiveNearlyExhaustedWindow() {
        assertEquals(
                "quota: 2 of 10 remaining (window resets in 3s)",
                HumanListing.quotaFooter(List.of(new RateLimitWindow("minute", 10, 2, Duration.ofSeconds(3))), plain()));
        assertEquals(
                "quota: 0 of 1 remaining (window resets in 1s)",
                HumanListing.quotaFooter(
                        List.of(
                                new RateLimitWindow("minute", 15, 14, Duration.ofSeconds(42)),
                                new RateLimitWindow("request", 1, 0, Duration.ofSeconds(1))),
                        plain()));
    }

    @Test
    void aSubSecondResetReportsItsWholeSeconds() {
        assertEquals(
                "quota: 0 of 1 remaining (window resets in 1s)",
                HumanListing.quotaFooter(List.of(new RateLimitWindow("request", 1, 0, Duration.ofMillis(1500))), plain()));
    }

    @Test
    void anEquallyExhaustedTieNamesTheFirstObservedWindow() {
        assertEquals(
                "quota: 2 of 10 remaining (window resets in 5s)",
                HumanListing.quotaFooter(
                        List.of(
                                new RateLimitWindow("minute", 10, 2, Duration.ofSeconds(5)),
                                new RateLimitWindow("hour", 5, 2, Duration.ofSeconds(300))),
                        plain()),
                "two windows holding the same remaining count name the first observed one");
    }

    @Test
    void anAlreadyElapsedResetReportsZeroSeconds() {
        assertEquals(
                "quota: 1 of 10 remaining (window resets in 0s)",
                HumanListing.quotaFooter(List.of(new RateLimitWindow("minute", 10, 1, Duration.ZERO)), plain()));
    }

    @Test
    void theQuotaFooterIsDimWhenTheRenderContextIsColorable() {
        assertEquals(
                "\u001b[2mquota: 2 of 10 remaining (window resets in 3s)\u001b[0m",
                HumanListing.quotaFooter(List.of(new RateLimitWindow("minute", 10, 2, Duration.ofSeconds(3))), colorable()));
    }

    @Test
    void healthyWindowsMissingWindowsAndUnlimitedWindowsRenderNoQuotaFooter() {
        assertEquals(
                "",
                HumanListing.quotaFooter(List.of(new RateLimitWindow("minute", 10, 3, Duration.ofSeconds(3))), plain()),
                "a window with more than two remaining is not advisory");
        assertEquals("", HumanListing.quotaFooter(List.of(), plain()));
        assertEquals("", HumanListing.quotaFooter(null, plain()));
        assertEquals(
                "",
                HumanListing.quotaFooter(List.of(new RateLimitWindow("request", 0, 0, Duration.ofSeconds(1))), plain()),
                "a limit of zero means unlimited, not exhausted");
    }

    @Test
    void quietSuppressesTheQuotaFooterLikeEveryAdvisoryLine() {
        assertEquals(
                "",
                HumanListing.quotaFooter(List.of(new RateLimitWindow("minute", 10, 2, Duration.ofSeconds(3))), quiet()));
    }

    private static OutputRequest plain() {
        return new OutputRequest(false, OutputMode.HUMAN, false, false, false, false, 100);
    }

    private static OutputRequest quiet() {
        return new OutputRequest(false, OutputMode.HUMAN, false, false, true, false, 100);
    }

    private static OutputRequest colorable() {
        return new OutputRequest(false, OutputMode.HUMAN, false, false, false, true, 100);
    }

    private static OutputRequest narrow() {
        return new OutputRequest(false, OutputMode.HUMAN, false, false, false, false, 40);
    }

    private static OutputRequest narrowColorable() {
        return new OutputRequest(false, OutputMode.HUMAN, false, false, false, true, 40);
    }

    /** A body whose whole-word lines break exactly at the narrow width minus the gutter. */
    private static final String WRAPS_AT_THIRTY_SIX =
            "aaaaaaaaaaaa bbbbbbbbbbbb cccccccccccc dddddddddddd";
}

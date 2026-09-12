package io.amscotti.bravesearch.domain.goggles;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.UsageValidationError;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Inline-definition and site-shortcut validation of the goggle compiler: the documented
 * instruction-count, code-point, wildcard, and caret limits; the control-character rule
 * (newline and tab are the only admitted controls); the URL-reference discriminator; and
 * the injection battery every site shortcut must survive before it can join compiled DSL
 * text. No rejection may quote definition or site content — usage text names the rule.
 */
final class GoggleCompilerTest {

    /** One supplementary code point (a musical symbol) carried as a surrogate pair. */
    private static final String SUPPLEMENTARY = "\uD834\uDD1E";

    @Test
    void urlReferencesDistinguishThemselvesFromInlineDefinitions() {
        Goggle https = GoggleCompiler.fromUrlOrInline("https://example.com/goggles/tech.goggle");
        Goggle http = GoggleCompiler.fromUrlOrInline("http://example.org/rules");

        assertInstanceOf(Goggle.UrlReference.class, https);
        assertEquals("https://example.com/goggles/tech.goggle", ((Goggle.UrlReference) https).url());
        assertInstanceOf(Goggle.UrlReference.class, http);
    }

    @Test
    void anAbsoluteHttpUrlWithoutAHostIsNotAUrlReference() {
        // a hostless absolute spelling fails the URL-reference shape and falls to the
        // inline-definition rules instead of riding the wire as a URL
        assertInstanceOf(Goggle.Inline.class, GoggleCompiler.fromUrlOrInline("https:/missing-host"));
    }

    @Test
    void anyOtherValueCompilesAsAnInlineDefinition() {
        Goggle.Inline inline = assertInstanceOf(
                Goggle.Inline.class, GoggleCompiler.fromUrlOrInline("!name: local rules\n+site:example.com"));

        assertEquals("!name: local rules\n+site:example.com", inline.definition());
    }

    @Test
    void inlineDefinitionsAreValidatedAtConstruction() {
        // control characters other than newline and tab can never ride a definition
        assertThrows(UsageValidationError.class, () -> new Goggle.Inline("+site:example.com\r\nother"));
        assertThrows(UsageValidationError.class, () -> new Goggle.Inline("nul\u0000"));
        assertThrows(UsageValidationError.class, () -> new Goggle.Inline("delete\u007F"));
        assertDoesNotThrow(() -> new Goggle.Inline("+site:example.com\nother\trule"));
    }

    @Test
    void aBlankInlineDefinitionIsRejected() {
        assertThrows(UsageValidationError.class, () -> new Goggle.Inline(""));
        assertThrows(UsageValidationError.class, () -> new Goggle.Inline(" \n\t "));
    }

    @Test
    void commentAndBlankLinesAreNotInstructions() {
        String definition = "!name: counted\n\n! a comment line\n+site:example.com\n\n-site:spam.example";
        Goggle.Inline inline = new Goggle.Inline(definition);

        assertEquals(definition, inline.definition());
    }

    @Test
    void commentAndBlankLinesDoNotCountTowardTheInstructionLimit() {
        String filler = "! not an instruction\n\n".repeat(50);

        assertDoesNotThrow(() -> new Goggle.Inline(filler + "+site:example.com"));
    }

    @Test
    void atMostOneHundredThousandInstructionsAreAccepted() {
        String ninetyNine = ("+site:example.com\n").repeat(99_999) + "+site:last.example";

        assertDoesNotThrow(() -> new Goggle.Inline(ninetyNine));
        assertThrows(UsageValidationError.class, () -> new Goggle.Inline(ninetyNine + "\n+site:one.too.many"));
    }

    @Test
    void eachInstructionCarriesAtMostFiveHundredCodePoints() {
        assertDoesNotThrow(() -> new Goggle.Inline("+" + "a".repeat(499)));
        assertThrows(UsageValidationError.class, () -> new Goggle.Inline("+" + "a".repeat(500)));

        // supplementary code points count once: 499 pairs are 499 code points plus the
        // operator and pass; 500 pairs are 501 and are rejected
        assertDoesNotThrow(() -> new Goggle.Inline("+" + SUPPLEMENTARY.repeat(499)));
        assertThrows(UsageValidationError.class, () -> new Goggle.Inline("+" + SUPPLEMENTARY.repeat(500)));
    }

    @Test
    void eachInstructionCarriesAtMostThreeWildcards() {
        assertDoesNotThrow(() -> new Goggle.Inline("+a*b*c*d"));
        assertThrows(UsageValidationError.class, () -> new Goggle.Inline("+a*b*c*d*e"));
    }

    @Test
    void eachInstructionCarriesAtMostThreeCarets() {
        assertDoesNotThrow(() -> new Goggle.Inline("+a^b^c^d"));
        assertThrows(UsageValidationError.class, () -> new Goggle.Inline("+a^b^c^d^e"));
    }

    @Test
    void everyRejectionIsATypedUsageFailure() {
        UsageValidationError rejected =
                assertThrows(UsageValidationError.class, () -> new Goggle.Inline("+site:*\u0000.example"));

        assertEquals(FailureKind.USAGE, rejected.kind());
    }

    @Test
    void siteShortcutsCompileIntoOneInlineGoggleOfBoostAndDiscardSiteRules() {
        Goggle.Inline compiled = GoggleCompiler.compileSiteShortcuts(
                List.of("example.com", "münchen.de"), List.of("spam.example", "ads.example"));

        assertEquals(
                "+site:example.com\n+site:xn--mnchen-3ya.de\n-site:spam.example\n-site:ads.example",
                compiled.definition());
    }

    @Test
    void anAlreadyNormalizedDomainStaysNormalized() {
        Goggle.Inline compiled = GoggleCompiler.compileSiteShortcuts(
                List.of("XN--MNCHEN-3YA.DE", "EXAMPLE.COM"), List.of("PUNYCODE.XN--MNCHEN-3YA.DE"));

        assertEquals(
                "+site:xn--mnchen-3ya.de\n+site:example.com\n-site:punycode.xn--mnchen-3ya.de",
                compiled.definition());
    }

    @Test
    void compiledSiteRulesPassTheSameInlineValidation() {
        // the compiled definition is itself a validated inline goggle, proven by the
        // constructor of the returned instance
        assertInstanceOf(Goggle.Inline.class, GoggleCompiler.compileSiteShortcuts(List.of("example.com"), List.of()));
    }

    @Test
    void siteShortcutsWithoutAnySiteAreRejected() {
        assertThrows(
                UsageValidationError.class,
                () -> GoggleCompiler.compileSiteShortcuts(Collections.emptyList(), Collections.emptyList()));
    }

    @Test
    void theSiteInjectionBatteryIsRejectedBeforeAnyCompilation() {
        for (String injected : new String[] {
            "example.com$include everything",
            "$strict",
            "a,b",
            "http://x",
            "https://x/path",
            "x/path",
            "x:8080",
            "x y",
            "x\ny",
            "x\ry",
            "x\u0000y",
            "",
            " ",
            "under_score.example",
            "a..b",
            "trailing.",
            "-leading-hyphen.example"
        }) {
            assertThrows(
                    UsageValidationError.class,
                    () -> GoggleCompiler.compileSiteShortcuts(List.of(injected), List.of()),
                    "site shortcut must be rejected: <" + injected + ">");
            assertThrows(
                    UsageValidationError.class,
                    () -> GoggleCompiler.compileSiteShortcuts(List.of(), List.of(injected)),
                    "excluded site shortcut must be rejected: <" + injected + ">");
        }
    }

    @Test
    void aNullSiteShortcutIsRejected() {
        assertThrows(
                UsageValidationError.class,
                () -> GoggleCompiler.compileSiteShortcuts(java.util.Arrays.asList((String) null), List.of()));
        assertThrows(
                UsageValidationError.class,
                () -> GoggleCompiler.compileSiteShortcuts(List.of(), java.util.Arrays.asList((String) null)));
    }

    @Test
    void aNullUrlOrInlineValueIsRejected() {
        assertThrows(UsageValidationError.class, () -> GoggleCompiler.fromUrlOrInline(null));
        assertThrows(UsageValidationError.class, () -> GoggleCompiler.fromUrlOrInline(""));
        assertThrows(UsageValidationError.class, () -> GoggleCompiler.fromUrlOrInline("   "));
    }

    @Test
    void aNullDefinitionIsRejected() {
        assertThrows(UsageValidationError.class, () -> new Goggle.Inline(null));
    }
}

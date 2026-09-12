package io.amscotti.bravesearch.domain.goggles;

import io.amscotti.bravesearch.domain.error.UsageValidationError;
import java.net.IDN;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Compiles the goggle input strategies into {@link Goggle} instances: a {@code --goggle}
 * value discriminates itself into a URL reference or a validated inline definition, file
 * content compiles as an inline definition, and the site shortcuts compile into exactly
 * one inline goggle — a {@code +site:} boost rule per included domain followed by a
 * {@code -site:} discard rule per excluded domain, the site-rule spellings of the goggle
 * dialect — so the upstream maximum of three goggles stays enforceable. The checked-in
 * upstream document fixes the one-goggle compilation and its safety rules without
 * spelling the rule syntax, so this class pins it.
 *
 * <p>Site values must be IDNA-normalizable domains and are normalized with strict
 * {@link IDN#toASCII(String, int)} under {@link IDN#USE_STD3_ASCII_RULES} (no unassigned
 * code points admitted), lowercased for determinism. Everything that could smuggle DSL
 * structure is rejected up front — schemes, paths, and ports (any {@code : / \ ? # @}),
 * commas, dollar signs, whitespace, control characters, and empty labels — so a site
 * value can only ever contribute one inert domain token to the compiled text.
 */
public final class GoggleCompiler {

    private GoggleCompiler() {}

    /**
     * Compiles one {@code --goggle} value: an absolute http(s) URL spelling becomes a URL
     * reference, every other value a validated inline definition.
     *
     * @throws UsageValidationError when the value is blank or breaks an inline limit
     */
    public static Goggle fromUrlOrInline(String value) {
        if (value == null || value.isBlank()) {
            throw new UsageValidationError("--goggle requires a URL reference or an inline definition");
        }
        if (Goggle.isUrlReferenceShape(value)) {
            return new Goggle.UrlReference(value);
        }
        return new Goggle.Inline(value);
    }

    /**
     * Compiles loaded file content as an inline definition.
     *
     * @throws UsageValidationError when the content breaks a documented inline limit
     */
    public static Goggle.Inline compileInline(String definition) {
        return new Goggle.Inline(definition);
    }

    /**
     * Compiles the site shortcuts into one inline goggle: the included domains as boost
     * site rules in their given order, then the excluded domains as discard site rules in
     * their given order.
     *
     * @throws UsageValidationError when a site value is not an IDNA-normalizable domain
     *     or no site was supplied at all
     */
    public static Goggle.Inline compileSiteShortcuts(List<String> includeSites, List<String> excludeSites) {
        List<String> includes = includeSites == null ? List.of() : includeSites;
        List<String> excludes = excludeSites == null ? List.of() : excludeSites;
        if (includes.isEmpty() && excludes.isEmpty()) {
            throw new UsageValidationError("site shortcuts require at least one --include-site or --exclude-site value");
        }
        List<String> rules = new ArrayList<>();
        for (String site : includes) {
            rules.add("+site:" + normalizedSite(site, "--include-site"));
        }
        for (String site : excludes) {
            rules.add("-site:" + normalizedSite(site, "--exclude-site"));
        }
        return new Goggle.Inline(String.join("\n", rules));
    }

    /** One inert IDNA domain token, or a usage rejection naming the option and the rule. */
    private static String normalizedSite(String site, String optionName) {
        if (site == null || site.isBlank()) {
            throw new UsageValidationError(optionName + " requires a domain value");
        }
        requireInertDomainText(site, optionName);
        String ascii;
        try {
            ascii = IDN.toASCII(site, IDN.USE_STD3_ASCII_RULES);
        } catch (IllegalArgumentException notADomain) {
            throw new UsageValidationError(optionName + " must be an IDNA-normalized domain");
        }
        if (ascii.isBlank()) {
            throw new UsageValidationError(optionName + " must be an IDNA-normalized domain");
        }
        return ascii.toLowerCase(Locale.ROOT);
    }

    /**
     * Rejects every character class through which goggle DSL structure could enter: the
     * URI separators of schemes, paths, and ports; the list and DSL control characters
     * comma and dollar; whitespace and newlines; every control character; and empty
     * domain labels.
     */
    private static void requireInertDomainText(String site, String optionName) {
        for (int index = 0; index < site.length(); ) {
            int codePoint = site.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint)
                    || Character.isWhitespace(codePoint)
                    || Character.isSpaceChar(codePoint)) {
                throw new UsageValidationError(
                        optionName + " must be an IDNA-normalized domain without whitespace or control characters");
            }
            switch (codePoint) {
                case ':', '/', '\\', '?', '#', '@', ',', '$' -> throw new UsageValidationError(optionName
                        + " must be an IDNA-normalized domain without scheme, path, port, comma, or DSL characters");
                default -> {
                    // every other code point is left to the strict IDNA normalization
                }
            }
        }
        for (String label : site.split("\\.", -1)) {
            if (label.isEmpty()) {
                throw new UsageValidationError(optionName + " must not carry an empty domain label");
            }
        }
    }
}

package io.amscotti.bravesearch.adapter.cli.option;

import io.amscotti.bravesearch.domain.error.UsageValidationError;
import io.amscotti.bravesearch.domain.request.CommonSearchBinding;
import io.amscotti.bravesearch.domain.request.Freshness;
import io.amscotti.bravesearch.domain.request.SafeSearch;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import picocli.CommandLine.Option;

/**
 * The endpoint-shared search options every search vertical offers a subset of:
 * {@code --country}, {@code --search-lang}, {@code --ui-lang}, {@code --safe-search},
 * {@code --freshness}, {@code --count}, {@code --page}, and the tri-state
 * {@code --spellcheck}.
 *
 * <p>One superset mixin is the picocli-native shape: mixin contents are declared by the
 * annotated fields, so per-command presence would need programmatic spec assembly that
 * fights the annotation model and the ahead-of-time compilation of the command line. A
 * command whose endpoint documents only a subset (news, videos, images) still mixes this
 * one instance and rejects the endpoint-inapplicable spellings in its own local validation
 * as a usage error, so the shared grammar keeps one spelling set across every vertical.
 * That guard is a complement: {@link #ALL_OPTION_NAMES} inventories the whole grammar and
 * {@link #rejectUndocumentedExcept(String...)} refuses every supplied spelling the calling
 * command did not name, so a spelling added here later is refused by every command until
 * that command accepts it — silence can never swallow a new option. The instance is
 * constructed and injected by a composition root, never by the commands reading it;
 * {@code --spellcheck} stays tri-state ({@code null} when absent) so an unset flag is
 * omitted from the wire instead of coerced to an upstream default.
 */
public final class CommonSearchOptions {

    /**
     * Every shared option spelling of this mixin, in declaration order: the inventory the
     * complement guard walks. Co-located with the annotated fields and pinned to them by
     * the model-equivalence test that enumerates the mixin's grammar through picocli, so
     * the two can never drift apart.
     */
    public static final List<String> ALL_OPTION_NAMES =
            List.of(
                    "--country",
                    "--search-lang",
                    "--ui-lang",
                    "--safe-search",
                    "--freshness",
                    "--count",
                    "--page",
                    "--spellcheck");

    @Option(names = "--country", paramLabel = "<code>", description = "Two-letter result country code.")
    String country;

    @Option(names = "--search-lang", paramLabel = "<tag>", description = "Result language tag.")
    String searchLang;

    @Option(names = "--ui-lang", paramLabel = "<tag>", description = "Interface language tag.")
    String uiLang;

    @Option(
            names = "--safe-search",
            converter = SafeSearchConverter.class,
            completionCandidates = SafeSearchCandidates.class,
            description = "off, moderate, or strict.")
    SafeSearch safeSearch;

    @Option(
            names = "--freshness",
            converter = FreshnessConverter.class,
            description = "pd, pw, pm, py, or YYYY-MM-DDtoYYYY-MM-DD.")
    Freshness freshness;

    @Option(names = "--count", paramLabel = "<n>", description = "Result count within the endpoint's documented range.")
    Integer count;

    @Option(names = "--page", paramLabel = "<1..10>", description = "User-facing page; sent as its zero-based offset.")
    Integer page;

    @Option(names = "--spellcheck", negatable = true, description = "Ask upstream to correct the query spelling.")
    Boolean spellcheck;

    /** The two-letter result country code, or null when none was supplied. */
    public String country() {
        return country;
    }

    /** The result language tag, or null when none was supplied. */
    public String searchLang() {
        return searchLang;
    }

    /** The interface language tag, or null when none was supplied. */
    public String uiLang() {
        return uiLang;
    }

    /** The parsed safe-search level, or null when none was supplied. */
    public SafeSearch safeSearch() {
        return safeSearch;
    }

    /** The parsed freshness window, or null when none was supplied. */
    public Freshness freshness() {
        return freshness;
    }

    /** The result count, or null when none was supplied. */
    public Integer count() {
        return count;
    }

    /** The user-facing page, or null when none was supplied. */
    public Integer page() {
        return page;
    }

    /** The tri-state spellcheck flag: true, false, or null when the flag never appeared. */
    public Boolean spellcheck() {
        return spellcheck;
    }

    /**
     * Binds every shared spelling this invocation actually supplied onto {@code builder},
     * in the mixin's declaration order; an absent spelling leaves the builder untouched,
     * so an unset flag stays omitted from the wire instead of coerced to an upstream
     * default. The builder of an endpoint that documents the whole shared grammar accepts
     * exactly these setters through {@link CommonSearchBinding}.
     *
     * @param builder the request builder of a command whose endpoint documents the whole
     *     shared grammar
     */
    public void applyTo(CommonSearchBinding builder) {
        if (country != null) {
            builder.country(country);
        }
        if (searchLang != null) {
            builder.searchLang(searchLang);
        }
        if (uiLang != null) {
            builder.uiLang(uiLang);
        }
        if (safeSearch != null) {
            builder.safeSearch(safeSearch);
        }
        if (freshness != null) {
            builder.freshness(freshness);
        }
        if (count != null) {
            builder.count(count);
        }
        if (page != null) {
            builder.page(page);
        }
        if (spellcheck != null) {
            builder.spellcheck(spellcheck);
        }
    }

    /**
     * Rejects every shared spelling this invocation actually supplied that the calling
     * command does not document, naming every one of them — the complement guard a
     * command runs in its request assembly, before anything is dispatched. The default is
     * rejection: a spelling from {@link #ALL_OPTION_NAMES} is refused until a command
     * names it here, so a spelling added to the mixin cannot be silently swallowed by a
     * command whose accepted list was never widened.
     *
     * @param acceptedNames shared spellings the calling command's endpoint documents
     * @throws UsageValidationError naming every supplied spelling outside the accepted set
     */
    public void rejectUndocumentedExcept(String... acceptedNames) throws UsageValidationError {
        Set<String> accepted = Set.of(acceptedNames);
        List<String> supplied = new ArrayList<>();
        for (String optionName : ALL_OPTION_NAMES) {
            if (!accepted.contains(optionName) && wasSupplied(optionName)) {
                supplied.add(optionName);
            }
        }
        if (!supplied.isEmpty()) {
            throw new UsageValidationError(
                    String.join(", ", supplied)
                            + (supplied.size() == 1 ? " is not accepted by this command" : " are not accepted by this command"));
        }
    }

    private boolean wasSupplied(String optionName) {
        return switch (optionName) {
            case "--country" -> country != null;
            case "--search-lang" -> searchLang != null;
            case "--ui-lang" -> uiLang != null;
            case "--safe-search" -> safeSearch != null;
            case "--freshness" -> freshness != null;
            case "--count" -> count != null;
            case "--page" -> page != null;
            case "--spellcheck" -> spellcheck != null;
            default -> throw new IllegalArgumentException("unknown shared option " + optionName);
        };
    }
}

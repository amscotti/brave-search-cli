package io.amscotti.bravesearch.domain.request;

import io.amscotti.bravesearch.domain.error.UsageValidationError;
import io.amscotti.bravesearch.domain.goggles.Goggle;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, fully validated request for the Brave news search endpoint.
 *
 * <p>Every optional field is null when absent and travels as an omitted wire field; the
 * booleans are tri-state for exactly that reason — a Brave default must never be coerced
 * into an explicit wire value. The query keeps the shared search-option rules: between 1
 * and 400 Unicode code points, at most 50 whitespace-delimited words, never all
 * whitespace, and never normalized or trimmed. The page is the user-facing 1..10 page and
 * {@link #upstreamOffset()} exposes the zero-based offset the endpoint expects.
 *
 * <p>The country accepts exactly the spellings the news endpoint documents: a two-letter
 * code or the region-wide {@code ALL}. The endpoint documents no location header group and
 * no timezone, so the request carries none.
 */
public record NewsSearchRequest(
        String query,
        String country,
        String searchLang,
        String uiLang,
        SafeSearch safeSearch,
        Freshness freshness,
        Integer count,
        Integer page,
        Boolean spellcheck,
        Boolean extraSnippets,
        Boolean includeFetchMetadata,
        Boolean operators,
        List<Goggle> goggles) {

    /** The largest query, in Unicode code points. */
    public static final int MAX_QUERY_CODE_POINTS = QueryText.MAX_QUERY_CODE_POINTS;

    /** The largest number of whitespace-delimited words of a query. */
    public static final int MAX_QUERY_WORDS = QueryText.MAX_QUERY_WORDS;

    /** The smallest news result count. */
    public static final int MIN_COUNT = 1;

    /** The largest news result count, the documented endpoint maximum. */
    public static final int MAX_COUNT = 50;

    /** The smallest user-facing page. */
    public static final int MIN_PAGE = 1;

    /** The largest user-facing page (the endpoint's zero-based offset tops out at 9). */
    public static final int MAX_PAGE = 10;

    /** The largest number of goggles of one request, the documented upstream maximum. */
    public static final int MAX_GOGGLES = 3;

    /** The region-wide country spelling, shared through {@link CountryCode#ALL}. */
    public static final String COUNTRY_ALL = CountryCode.ALL;

    public NewsSearchRequest {
        QueryText.requireValid(query);
        CountryCode.requireDocumented(country);
        requireNonblankWhenPresent("search-lang", searchLang);
        requireNonblankWhenPresent("ui-lang", uiLang);
        if (count != null && (count < MIN_COUNT || count > MAX_COUNT)) {
            throw new UsageValidationError("count must be between " + MIN_COUNT + " and " + MAX_COUNT);
        }
        if (page != null && (page < MIN_PAGE || page > MAX_PAGE)) {
            throw new UsageValidationError("page must be between " + MIN_PAGE + " and " + MAX_PAGE);
        }
        goggles = goggles == null ? List.of() : validatedGoggles(goggles);
    }

    /** Starts a builder carrying the required query; every other field starts absent. */
    public static Builder builder(String query) {
        return new Builder(query);
    }

    /** The zero-based upstream offset of the user-facing page, or null when no page was set. */
    public Integer upstreamOffset() {
        return page == null ? null : page - 1;
    }

    /**
     * The same request pinned to {@code replacement} — the form sequential pagination uses
     * to walk the pages of one invocation without rebuilding every member.
     */
    public NewsSearchRequest withPage(int replacement) {
        return new NewsSearchRequest(
                query,
                country,
                searchLang,
                uiLang,
                safeSearch,
                freshness,
                count,
                replacement,
                spellcheck,
                extraSnippets,
                includeFetchMetadata,
                operators,
                goggles);
    }

    private static void requireNonblankWhenPresent(String optionName, String value) {
        if (value != null && value.isBlank()) {
            throw new UsageValidationError(optionName + " must not be blank when supplied");
        }
    }

    private static List<Goggle> validatedGoggles(List<Goggle> goggles) {
        if (goggles.size() > MAX_GOGGLES) {
            throw new UsageValidationError("at most " + MAX_GOGGLES + " goggles ride one request");
        }
        for (Goggle goggle : goggles) {
            Objects.requireNonNull(goggle, "goggle");
        }
        return List.copyOf(goggles);
    }

    /** Collects the optional fields of one request; the query is required up front. */
    public static final class Builder implements CommonSearchBinding {

        private final String query;
        private String country;
        private String searchLang;
        private String uiLang;
        private SafeSearch safeSearch;
        private Freshness freshness;
        private Integer count;
        private Integer page;
        private Boolean spellcheck;
        private Boolean extraSnippets;
        private Boolean includeFetchMetadata;
        private Boolean operators;
        private List<Goggle> goggles;

        private Builder(String query) {
            this.query = Objects.requireNonNull(query, "query");
        }

        public Builder country(String country) {
            this.country = country;
            return this;
        }

        public Builder searchLang(String searchLang) {
            this.searchLang = searchLang;
            return this;
        }

        public Builder uiLang(String uiLang) {
            this.uiLang = uiLang;
            return this;
        }

        public Builder safeSearch(SafeSearch safeSearch) {
            this.safeSearch = safeSearch;
            return this;
        }

        public Builder freshness(Freshness freshness) {
            this.freshness = freshness;
            return this;
        }

        public Builder count(int count) {
            this.count = count;
            return this;
        }

        public Builder page(int page) {
            this.page = page;
            return this;
        }

        public Builder spellcheck(boolean spellcheck) {
            this.spellcheck = spellcheck;
            return this;
        }

        public Builder extraSnippets(boolean extraSnippets) {
            this.extraSnippets = extraSnippets;
            return this;
        }

        public Builder includeFetchMetadata(boolean includeFetchMetadata) {
            this.includeFetchMetadata = includeFetchMetadata;
            return this;
        }

        public Builder operators(boolean operators) {
            this.operators = operators;
            return this;
        }

        public Builder goggles(List<Goggle> goggles) {
            this.goggles = goggles;
            return this;
        }

        public NewsSearchRequest build() {
            return new NewsSearchRequest(
                    query,
                    country,
                    searchLang,
                    uiLang,
                    safeSearch,
                    freshness,
                    count,
                    page,
                    spellcheck,
                    extraSnippets,
                    includeFetchMetadata,
                    operators,
                    goggles);
        }
    }
}

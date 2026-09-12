package io.amscotti.bravesearch.domain.request;

import io.amscotti.bravesearch.domain.error.UsageValidationError;

/**
 * Immutable, fully validated request for the Brave suggest endpoint.
 *
 * <p>Every optional field is null when absent and travels as an omitted wire field; the
 * rich boolean is tri-state for exactly that reason — Brave's documented defaults (count
 * 5, rich false) must never be coerced into explicit wire values, because the unsupplied
 * request keeps upstream's own decision. The query keeps the rule the suggest reference
 * states for its own endpoint — not empty, at most 400 characters, at most 50 words —
 * which is exactly the shared search-option rule, so the shared {@link QueryText}
 * validation applies unchanged.
 *
 * <p>The suggest endpoint maps its language option to the wire name {@code lang} — never
 * the search verticals' {@code search_lang} — and documents no SafeSearch, freshness,
 * interface language, or page, so the request carries none of those members at all. The
 * country accepts the spellings the suggest reference documents: a two-letter code or the
 * region-wide {@code ALL}.
 */
public record SuggestRequest(String query, String country, String lang, Integer count, Boolean rich) {

    /** The largest query, in Unicode code points. */
    public static final int MAX_QUERY_CODE_POINTS = QueryText.MAX_QUERY_CODE_POINTS;

    /** The largest number of whitespace-delimited words of a query. */
    public static final int MAX_QUERY_WORDS = QueryText.MAX_QUERY_WORDS;

    /** The smallest suggest result count. */
    public static final int MIN_COUNT = 1;

    /** The largest suggest result count, the documented endpoint maximum. */
    public static final int MAX_COUNT = 20;

    public SuggestRequest {
        QueryText.requireValid(query);
        CountryCode.requireDocumented(country);
        requireNonblankWhenPresent("lang", lang);
        if (count != null && (count < MIN_COUNT || count > MAX_COUNT)) {
            throw new UsageValidationError("count must be between " + MIN_COUNT + " and " + MAX_COUNT);
        }
    }

    /** Starts a builder carrying the required query; every other field starts absent. */
    public static Builder builder(String query) {
        return new Builder(query);
    }

    private static void requireNonblankWhenPresent(String optionName, String value) {
        if (value != null && value.isBlank()) {
            throw new UsageValidationError(optionName + " must not be blank when supplied");
        }
    }

    /** Collects the optional fields of one request; the query is required up front. */
    public static final class Builder {

        private final String query;
        private String country;
        private String lang;
        private Integer count;
        private Boolean rich;

        private Builder(String query) {
            this.query = java.util.Objects.requireNonNull(query, "query");
        }

        public Builder country(String country) {
            this.country = country;
            return this;
        }

        public Builder lang(String lang) {
            this.lang = lang;
            return this;
        }

        public Builder count(int count) {
            this.count = count;
            return this;
        }

        public Builder rich(boolean rich) {
            this.rich = rich;
            return this;
        }

        public SuggestRequest build() {
            return new SuggestRequest(query, country, lang, count, rich);
        }
    }
}

package io.amscotti.bravesearch.domain.request;

import io.amscotti.bravesearch.domain.error.UsageValidationError;

/**
 * Immutable, fully validated request for the Brave spellcheck endpoint.
 *
 * <p>The spellcheck endpoint documents exactly three fields — the query, the country, and
 * the language hint — so the request carries exactly those members and nothing else: no
 * count, no SafeSearch, no freshness, and no page member exists. Every optional field is
 * null when absent and travels as an omitted wire field. The query keeps the rule the
 * spellcheck reference states for its own endpoint — not empty, at most 400 characters,
 * at most 50 words — which is exactly the shared search-option rule, so the shared {@link
 * QueryText} validation applies unchanged.
 *
 * <p>The language option maps to the wire name {@code lang} — never the search
 * verticals' {@code search_lang} — and the country accepts the spellings the spellcheck
 * reference documents: a two-letter code or the region-wide {@code ALL}.
 */
public record SpellcheckRequest(String query, String country, String lang) {

    /** The largest query, in Unicode code points. */
    public static final int MAX_QUERY_CODE_POINTS = QueryText.MAX_QUERY_CODE_POINTS;

    /** The largest number of whitespace-delimited words of a query. */
    public static final int MAX_QUERY_WORDS = QueryText.MAX_QUERY_WORDS;

    public SpellcheckRequest {
        QueryText.requireValid(query);
        CountryCode.requireDocumented(country);
        if (lang != null && lang.isBlank()) {
            throw new UsageValidationError("lang must not be blank when supplied");
        }
    }

    /** Starts a builder carrying the required query; every other field starts absent. */
    public static Builder builder(String query) {
        return new Builder(query);
    }

    public static final class Builder {

        private final String query;
        private String country;
        private String lang;

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

        public SpellcheckRequest build() {
            return new SpellcheckRequest(query, country, lang);
        }
    }
}

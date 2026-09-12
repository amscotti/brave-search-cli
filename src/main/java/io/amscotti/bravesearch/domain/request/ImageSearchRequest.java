package io.amscotti.bravesearch.domain.request;

import io.amscotti.bravesearch.domain.error.UsageValidationError;

/**
 * Immutable, fully validated request for the Brave images search endpoint.
 *
 * <p>Every optional field is null when absent and travels as an omitted wire field; the
 * spellcheck boolean is tri-state for exactly that reason — a Brave default must never be
 * coerced into an explicit wire value. The query keeps the shared search-option rules:
 * between 1 and 400 Unicode code points, at most 50 whitespace-delimited words, never all
 * whitespace, and never normalized or trimmed.
 *
 * <p>The images endpoint is the one search vertical with its own grammar subset: the
 * count reaches 1..200 — wider than every other vertical — while the interface language,
 * the freshness window, and any page notion are absent entirely, because the endpoint
 * documents none of them: one request is one non-paginated exchange whose result breadth
 * comes from the count alone. The country accepts exactly the spellings the images
 * endpoint documents — a two-letter code or the region-wide {@code ALL} — and the
 * SafeSearch level is narrowed through {@link ImageSafeSearch}, which rejects the
 * undocumented {@code moderate} spelling as a usage failure.
 */
public record ImageSearchRequest(
        String query, String country, String searchLang, ImageSafeSearch safeSearch, Integer count, Boolean spellcheck) {

    /** The largest query, in Unicode code points. */
    public static final int MAX_QUERY_CODE_POINTS = QueryText.MAX_QUERY_CODE_POINTS;

    /** The largest number of whitespace-delimited words of a query. */
    public static final int MAX_QUERY_WORDS = QueryText.MAX_QUERY_WORDS;

    /** The smallest images result count. */
    public static final int MIN_COUNT = 1;

    /** The largest images result count, the documented endpoint maximum. */
    public static final int MAX_COUNT = 200;

    /** The region-wide country spelling, shared through {@link CountryCode#ALL}. */
    public static final String COUNTRY_ALL = CountryCode.ALL;

    public ImageSearchRequest {
        QueryText.requireValid(query);
        CountryCode.requireDocumented(country);
        requireNonblankWhenPresent("search-lang", searchLang);
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

    /**
     * Collects the optional fields of one request; the query is required up front. The
     * builder stays outside {@link CommonSearchBinding} on purpose: the images endpoint
     * documents only a subset of the shared grammar, and a setter a request cannot carry
     * must never exist merely for uniform binding.
     */
    public static final class Builder {

        private final String query;
        private String country;
        private String searchLang;
        private ImageSafeSearch safeSearch;
        private Integer count;
        private Boolean spellcheck;

        private Builder(String query) {
            this.query = java.util.Objects.requireNonNull(query, "query");
        }

        public Builder country(String country) {
            this.country = country;
            return this;
        }

        public Builder searchLang(String searchLang) {
            this.searchLang = searchLang;
            return this;
        }

        /**
         * Sets the level the shared grammar parsed, narrowed onto the images pair.
         *
         * @throws UsageValidationError when {@code level} is {@code moderate}
         */
        public Builder safeSearch(SafeSearch level) {
            this.safeSearch = ImageSafeSearch.of(level);
            return this;
        }

        public Builder count(int count) {
            this.count = count;
            return this;
        }

        public Builder spellcheck(boolean spellcheck) {
            this.spellcheck = spellcheck;
            return this;
        }

        public ImageSearchRequest build() {
            return new ImageSearchRequest(query, country, searchLang, safeSearch, count, spellcheck);
        }
    }
}

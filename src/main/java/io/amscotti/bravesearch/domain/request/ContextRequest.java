package io.amscotti.bravesearch.domain.request;

import io.amscotti.bravesearch.domain.error.UsageValidationError;
import java.util.Objects;

/**
 * Immutable, fully validated request for the Brave LLM context endpoint.
 *
 * <p>Every optional field is null when absent and travels as an omitted wire field — the
 * documented Brave defaults (count, the per-url and per-request token and snippet budgets)
 * are never coerced into explicit wire values, because an omitted field is what lets Brave
 * calibrate them. The query keeps the shared {@link QueryText} rules; the country is
 * exactly two letters ({@code ALL} is not documented for this endpoint); the search
 * language accepts documented subtags of any length from two characters up.
 *
 * <p>The location group carries exactly the seven documented location members — latitude
 * and longitude paired inside their ranges, city, state, state name, country, and postal
 * code — and deliberately has no timezone: the LLM context endpoint documents none, so the
 * flag never exists on this request.
 */
public record ContextRequest(
        String query,
        String country,
        String searchLang,
        SafeSearch safeSearch,
        Freshness freshness,
        Integer count,
        Integer maxUrls,
        Integer maxTokens,
        Integer maxSnippets,
        Integer maxTokensPerUrl,
        Integer maxSnippetsPerUrl,
        ContextThreshold threshold,
        Boolean sourceMetadata,
        Boolean enableLocal,
        Location location) {

    /** The smallest context result count. */
    public static final int MIN_COUNT = 1;

    /** The largest context result count. */
    public static final int MAX_COUNT = 50;

    /** The smallest per-request URL budget. */
    public static final int MIN_MAX_URLS = 1;

    /** The largest per-request URL budget. */
    public static final int MAX_MAX_URLS = 50;

    /** The smallest per-request token budget. */
    public static final int MIN_MAX_TOKENS = 1024;

    /** The largest per-request token budget. */
    public static final int MAX_MAX_TOKENS = 32768;

    /** The smallest per-request snippet budget. */
    public static final int MIN_MAX_SNIPPETS = 1;

    /** The largest per-request snippet budget. */
    public static final int MAX_MAX_SNIPPETS = 256;

    /** The smallest per-url token budget. */
    public static final int MIN_MAX_TOKENS_PER_URL = 512;

    /** The largest per-url token budget. */
    public static final int MAX_MAX_TOKENS_PER_URL = 8192;

    /** The smallest per-url snippet budget. */
    public static final int MIN_MAX_SNIPPETS_PER_URL = 1;

    /** The largest per-url snippet budget. */
    public static final int MAX_MAX_SNIPPETS_PER_URL = 100;

    public ContextRequest {
        QueryText.requireValid(query);
        requireTwoLetterCountry(country);
        requireLanguageTag(searchLang);
        requireBounded("count", count, MIN_COUNT, MAX_COUNT);
        requireBounded("max-urls", maxUrls, MIN_MAX_URLS, MAX_MAX_URLS);
        requireBounded("max-tokens", maxTokens, MIN_MAX_TOKENS, MAX_MAX_TOKENS);
        requireBounded("max-snippets", maxSnippets, MIN_MAX_SNIPPETS, MAX_MAX_SNIPPETS);
        requireBounded("max-tokens-per-url", maxTokensPerUrl, MIN_MAX_TOKENS_PER_URL, MAX_MAX_TOKENS_PER_URL);
        requireBounded("max-snippets-per-url", maxSnippetsPerUrl, MIN_MAX_SNIPPETS_PER_URL, MAX_MAX_SNIPPETS_PER_URL);
    }

    /** Starts a builder carrying the required query; every other field starts absent. */
    public static Builder builder(String query) {
        return new Builder(query);
    }

    private static void requireTwoLetterCountry(String country) {
        if (country == null) {
            return;
        }
        if (country.length() != 2 || !isAsciiLetter(country.charAt(0)) || !isAsciiLetter(country.charAt(1))) {
            throw new UsageValidationError("country must be a two-letter code");
        }
    }

    private static boolean isAsciiLetter(char letter) {
        return (letter >= 'A' && letter <= 'Z') || (letter >= 'a' && letter <= 'z');
    }

    private static void requireLanguageTag(String searchLang) {
        if (searchLang == null) {
            return;
        }
        if (searchLang.isBlank() || searchLang.codePointCount(0, searchLang.length()) < 2) {
            throw new UsageValidationError("search-lang must be a language tag of at least two characters");
        }
    }

    private static void requireBounded(String optionName, Integer value, int minimum, int maximum) {
        if (value != null && (value < minimum || value > maximum)) {
            throw new UsageValidationError(optionName + " must be between " + minimum + " and " + maximum);
        }
    }

    /**
     * The LLM context endpoint's location context, rendered as its seven documented X-Loc
     * headers on the wire. Timezone is not a member: this endpoint documents no timezone
     * header, and the shared pairing and range rules live in one place.
     */
    public record Location(
            Double latitude,
            Double longitude,
            String city,
            String state,
            String stateName,
            String country,
            String postalCode) {

        public Location {
            LocationRules.requirePairedCoordinates(latitude, longitude);
            LocationRules.requireLatitudeInRange(latitude);
            LocationRules.requireLongitudeInRange(longitude);
            LocationRules.requireNonblankWhenPresent("loc-city", city);
            LocationRules.requireNonblankWhenPresent("loc-state-name", stateName);
            LocationRules.requireNonblankWhenPresent("loc-postal-code", postalCode);
            LocationRules.requireTwoLetterCode("loc-state", state);
            LocationRules.requireTwoLetterCode("loc-country", country);
        }
    }

    /** Collects the optional fields of one request; the query is required up front. */
    public static final class Builder {

        private final String query;
        private String country;
        private String searchLang;
        private SafeSearch safeSearch;
        private Freshness freshness;
        private Integer count;
        private Integer maxUrls;
        private Integer maxTokens;
        private Integer maxSnippets;
        private Integer maxTokensPerUrl;
        private Integer maxSnippetsPerUrl;
        private ContextThreshold threshold;
        private Boolean sourceMetadata;
        private Boolean enableLocal;
        private Location location;

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

        public Builder maxUrls(int maxUrls) {
            this.maxUrls = maxUrls;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder maxSnippets(int maxSnippets) {
            this.maxSnippets = maxSnippets;
            return this;
        }

        public Builder maxTokensPerUrl(int maxTokensPerUrl) {
            this.maxTokensPerUrl = maxTokensPerUrl;
            return this;
        }

        public Builder maxSnippetsPerUrl(int maxSnippetsPerUrl) {
            this.maxSnippetsPerUrl = maxSnippetsPerUrl;
            return this;
        }

        public Builder threshold(ContextThreshold threshold) {
            this.threshold = threshold;
            return this;
        }

        public Builder sourceMetadata(boolean sourceMetadata) {
            this.sourceMetadata = sourceMetadata;
            return this;
        }

        public Builder enableLocal(Boolean enableLocal) {
            this.enableLocal = enableLocal;
            return this;
        }

        public Builder location(Location location) {
            this.location = location;
            return this;
        }

        public ContextRequest build() {
            return new ContextRequest(
                    query,
                    country,
                    searchLang,
                    safeSearch,
                    freshness,
                    count,
                    maxUrls,
                    maxTokens,
                    maxSnippets,
                    maxTokensPerUrl,
                    maxSnippetsPerUrl,
                    threshold,
                    sourceMetadata,
                    enableLocal,
                    location);
        }
    }
}

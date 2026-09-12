package io.amscotti.bravesearch.domain.request;

import io.amscotti.bravesearch.domain.error.UsageValidationError;
import java.math.BigDecimal;

/**
 * Immutable, fully validated request for the Brave place search endpoint.
 *
 * <p>The query is always optional — a request without it but with an anchor is Explore
 * mode, and a request without query and anchor both is a valid broad global search the
 * CLI never rejects locally — and a present query keeps the shared search-option text
 * rules. The anchor is exactly one of the three documented spellings: the coordinates
 * pair, the place-name string, or none; see {@link PlaceAnchor} for the pairing,
 * conflict, range, and header-safety rules the anchor owns.
 *
 * <p>Every optional field is null when absent and travels as an omitted wire field, so
 * Brave's documented defaults — count 20, safesearch strict, spellcheck true — stay
 * upstream decisions. The {@code geoloc} hint validates both components and serializes
 * exactly {@code latitudexlongitude}; the radius accepts every finite plain decimal at
 * zero or above — no exponent spelling, inside the shared fraction-scale and digit
 * bounds, so its wire rendering stays bounded — and is a ranking bias, not a hard
 * boundary, a distinction the human output states; the count budgets the total across
 * every response bucket, not only
 * {@code results}. The country accepts exactly a two-letter code, because the place
 * endpoint documents no region-wide spelling.
 */
public record PlaceSearchRequest(
        String query,
        PlaceAnchor anchor,
        BigDecimal radius,
        Integer count,
        GeoLoc geoloc,
        Units units,
        String country,
        String searchLang,
        String uiLang,
        SafeSearch safeSearch,
        Boolean spellcheck) {

    /** The largest query, in Unicode code points. */
    public static final int MAX_QUERY_CODE_POINTS = QueryText.MAX_QUERY_CODE_POINTS;

    /** The largest number of whitespace-delimited words of a query. */
    public static final int MAX_QUERY_WORDS = QueryText.MAX_QUERY_WORDS;

    /** The smallest place count. */
    public static final int MIN_COUNT = 1;

    /**
     * The largest place count, the documented endpoint maximum — the total budget
     * across every response bucket, not only the {@code results} bucket.
     */
    public static final int MAX_COUNT = 100;

    public PlaceSearchRequest {
        if (query != null) {
            QueryText.requireValid(query);
        }
        if (radius != null) {
            if (radius.signum() < 0) {
                throw new UsageValidationError("radius must be zero or greater");
            }
            DecimalRules.requirePlainDecimal("radius", radius);
        }
        if (count != null && (count < MIN_COUNT || count > MAX_COUNT)) {
            throw new UsageValidationError("count must be between " + MIN_COUNT + " and " + MAX_COUNT);
        }
        requireTwoLetterCountry(country);
        requireNonblankWhenPresent("search-lang", searchLang);
        requireNonblankWhenPresent("ui-lang", uiLang);
    }

    /** Starts a builder with every field absent — the query is as optional as the rest. */
    public static Builder builder() {
        return new Builder();
    }

    private static void requireNonblankWhenPresent(String optionName, String value) {
        if (value != null && value.isBlank()) {
            throw new UsageValidationError(optionName + " must not be blank when supplied");
        }
    }

    private static void requireTwoLetterCountry(String country) {
        if (country != null
                && (country.length() != 2 || !isAsciiLetter(country.charAt(0)) || !isAsciiLetter(country.charAt(1)))) {
            throw new UsageValidationError("country must be a two-letter code");
        }
    }

    private static boolean isAsciiLetter(char letter) {
        return (letter >= 'A' && letter <= 'Z') || (letter >= 'a' && letter <= 'z');
    }

    /** Collects every field of one request; all of them start absent. */
    public static final class Builder {

        private String query;
        private PlaceAnchor anchor;
        private BigDecimal radius;
        private Integer count;
        private GeoLoc geoloc;
        private Units units;
        private String country;
        private String searchLang;
        private String uiLang;
        private SafeSearch safeSearch;
        private Boolean spellcheck;

        public Builder query(String query) {
            this.query = query;
            return this;
        }

        public Builder anchor(PlaceAnchor anchor) {
            this.anchor = anchor;
            return this;
        }

        public Builder radius(BigDecimal radius) {
            this.radius = radius;
            return this;
        }

        public Builder count(int count) {
            this.count = count;
            return this;
        }

        public Builder geoloc(GeoLoc geoloc) {
            this.geoloc = geoloc;
            return this;
        }

        public Builder units(Units units) {
            this.units = units;
            return this;
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

        public Builder spellcheck(boolean spellcheck) {
            this.spellcheck = spellcheck;
            return this;
        }

        public PlaceSearchRequest build() {
            return new PlaceSearchRequest(
                    query, anchor, radius, count, geoloc, units, country, searchLang, uiLang, safeSearch, spellcheck);
        }
    }
}

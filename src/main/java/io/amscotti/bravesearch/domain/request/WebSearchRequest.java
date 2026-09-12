package io.amscotti.bravesearch.domain.request;

import io.amscotti.bravesearch.domain.error.UsageValidationError;
import io.amscotti.bravesearch.domain.goggles.Goggle;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, fully validated request for the Brave web search endpoint.
 *
 * <p>Every optional field is null when absent and travels as an omitted wire field; the
 * booleans are tri-state for exactly that reason — a Brave default must never be coerced
 * into an explicit wire value. The query keeps the shared search-option rules: between 1
 * and 400 Unicode code points, at most 50 whitespace-delimited words, never all
 * whitespace, and never normalized or trimmed — valid input is transmitted byte-for-byte
 * as given. The country is exactly the two letters the endpoint documents, so the
 * region-wide {@code ALL} spelling of other endpoints is refused here. The page is the
 * user-facing 1..10 page and {@link #upstreamOffset()} exposes
 * the zero-based offset the endpoint expects.
 *
 * <p>Location values follow the shared location contract: latitude and longitude arrive
 * together inside their documented ranges, the state and country codes are two uppercase
 * letters, the timezone is a real IANA zone, and every present string is nonblank.
 */
public record WebSearchRequest(
        String query,
        String country,
        String searchLang,
        String uiLang,
        SafeSearch safeSearch,
        Freshness freshness,
        Integer count,
        Integer page,
        Boolean spellcheck,
        Boolean textDecorations,
        List<String> resultFilters,
        Units units,
        Boolean extraSnippets,
        Boolean includeFetchMetadata,
        Boolean operators,
        Boolean enableRichCallback,
        List<Goggle> goggles,
        Location location) {

    /** The largest query, in Unicode code points. */
    public static final int MAX_QUERY_CODE_POINTS = QueryText.MAX_QUERY_CODE_POINTS;

    /** The largest number of whitespace-delimited words of a query. */
    public static final int MAX_QUERY_WORDS = QueryText.MAX_QUERY_WORDS;

    /** The smallest web result count. */
    public static final int MIN_COUNT = 1;

    /** The largest web result count. */
    public static final int MAX_COUNT = 20;

    /** The smallest user-facing page. */
    public static final int MIN_PAGE = 1;

    /** The largest user-facing page. */
    public static final int MAX_PAGE = 10;

    /** The largest number of goggles of one request, the documented upstream maximum. */
    public static final int MAX_GOGGLES = 3;

    public WebSearchRequest {
        validateQuery(query);
        requireTwoLetterCountry(country);
        requireNonblankWhenPresent("search-lang", searchLang);
        requireNonblankWhenPresent("ui-lang", uiLang);
        if (count != null && (count < MIN_COUNT || count > MAX_COUNT)) {
            throw new UsageValidationError("count must be between " + MIN_COUNT + " and " + MAX_COUNT);
        }
        if (page != null && (page < MIN_PAGE || page > MAX_PAGE)) {
            throw new UsageValidationError("page must be between " + MIN_PAGE + " and " + MAX_PAGE);
        }
        resultFilters = resultFilters == null ? List.of() : validatedFilters(resultFilters);
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
    public WebSearchRequest withPage(int replacement) {
        return new WebSearchRequest(
                query,
                country,
                searchLang,
                uiLang,
                safeSearch,
                freshness,
                count,
                replacement,
                spellcheck,
                textDecorations,
                resultFilters,
                units,
                extraSnippets,
                includeFetchMetadata,
                operators,
                enableRichCallback,
                goggles,
                location);
    }

    private static void validateQuery(String query) {
        QueryText.requireValid(query);
    }

    private static void requireNonblankWhenPresent(String optionName, String value) {
        if (value != null && value.isBlank()) {
            throw new UsageValidationError(optionName + " must not be blank when supplied");
        }
    }

    /**
     * The web endpoint documents a two-letter country code, so the region-wide {@code ALL}
     * spelling of other endpoints is a usage error here rather than a wire value.
     */
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

    private static List<String> validatedFilters(List<String> filters) {
        for (String filter : filters) {
            Objects.requireNonNull(filter, "result filter");
            if (filter.isBlank()) {
                throw new UsageValidationError("result-filter must not be blank when supplied");
            }
        }
        return List.copyOf(filters);
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

    /** The web endpoint's location context, rendered as the X-Loc header group on the wire. */
    public record Location(
            Double latitude,
            Double longitude,
            String city,
            String state,
            String stateName,
            String country,
            String postalCode,
            String timezone) {

        public Location {
            LocationRules.requirePairedCoordinates(latitude, longitude);
            LocationRules.requireLatitudeInRange(latitude);
            LocationRules.requireLongitudeInRange(longitude);
            LocationRules.requireNonblankWhenPresent("loc-city", city);
            LocationRules.requireNonblankWhenPresent("loc-state-name", stateName);
            LocationRules.requireNonblankWhenPresent("loc-postal-code", postalCode);
            LocationRules.requireTwoLetterCode("loc-state", state);
            LocationRules.requireTwoLetterCode("loc-country", country);
            if (timezone != null) {
                try {
                    ZoneId.of(timezone);
                } catch (DateTimeException unknownZone) {
                    throw new UsageValidationError("loc-timezone must be a valid IANA zone");
                }
            }
        }
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
        private Boolean textDecorations;
        private List<String> resultFilters;
        private Units units;
        private Boolean extraSnippets;
        private Boolean includeFetchMetadata;
        private Boolean operators;
        private Boolean enableRichCallback;
        private List<Goggle> goggles;
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

        public Builder textDecorations(boolean textDecorations) {
            this.textDecorations = textDecorations;
            return this;
        }

        public Builder resultFilters(List<String> resultFilters) {
            this.resultFilters = resultFilters;
            return this;
        }

        public Builder units(Units units) {
            this.units = units;
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

        public Builder enableRichCallback(boolean enableRichCallback) {
            this.enableRichCallback = enableRichCallback;
            return this;
        }

        public Builder goggles(List<Goggle> goggles) {
            this.goggles = goggles;
            return this;
        }

        public Builder location(Location location) {
            this.location = location;
            return this;
        }

        public WebSearchRequest build() {
            return new WebSearchRequest(
                    query,
                    country,
                    searchLang,
                    uiLang,
                    safeSearch,
                    freshness,
                    count,
                    page,
                    spellcheck,
                    textDecorations,
                    resultFilters,
                    units,
                    extraSnippets,
                    includeFetchMetadata,
                    operators,
                    enableRichCallback,
                    goggles,
                    location);
        }
    }
}

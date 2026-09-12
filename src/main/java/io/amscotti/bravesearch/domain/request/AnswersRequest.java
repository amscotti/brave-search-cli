package io.amscotti.bravesearch.domain.request;

import io.amscotti.bravesearch.domain.error.UsageValidationError;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Immutable, fully validated request for the Brave Answers chat-completions endpoint.
 *
 * <p>The question is the single {@code user} message content. The endpoint documents no
 * question limits of its own, so the shared {@link QueryText} rules apply unchanged — one
 * to 400 code points, at most 50 words, never all whitespace, never normalized — because
 * they are this CLI's one boundary for free-text search input.
 *
 * <p>Every optional field is null when absent and travels as an omitted wire field. The
 * model stays an opaque pass-through string — {@code brave}, {@code brave-pro}, and any
 * future spelling ride unchanged, and the unsupplied request lets Brave select its own
 * default. {@code maxCompletionTokens} must only be positive (the endpoint documents no
 * upper bound), {@code seed} accepts every integer, and the two stream budgets are inert
 * for a blocking request: they exist for the streaming exchange roles and stay absent
 * until supplied.
 *
 * <p>The research family follows the endpoint contract: every {@code research-*} member
 * requires the research flag, the research flag requires streaming, and enabled
 * citations, enabled entities, research, and — through research — research thinking are
 * local usage errors on a blocking request, rejected before anything is dispatched.
 * Explicitly disabled flags ({@code --no-citations}, {@code --no-entities}) stay valid
 * blocking requests, because a documented {@code false} needs no streaming. The country
 * keeps the web family's exactly-two-letter rule — the search controls ride the
 * web-search options container, whose own country spelling documents no region-wide
 * {@code ALL}.
 */
public record AnswersRequest(
        String question,
        String model,
        boolean stream,
        Integer maxCompletionTokens,
        Integer seed,
        Boolean citations,
        Boolean entities,
        Boolean research,
        Boolean researchAllowThinking,
        Integer researchMaximumTokensPerQuery,
        Integer researchMaximumQueries,
        Integer researchMaximumIterations,
        Integer researchMaximumSeconds,
        Integer researchMaximumResultsPerQuery,
        String country,
        String language,
        SafeSearch safeSearch,
        Map<String, String> metadata,
        Duration idleTimeout,
        Duration streamTimeout) {

    /** The smallest research token budget per query. */
    public static final int MIN_RESEARCH_TOKENS_PER_QUERY = 1024;

    /** The largest research token budget per query. */
    public static final int MAX_RESEARCH_TOKENS_PER_QUERY = 16384;

    /** The smallest research query budget. */
    public static final int MIN_RESEARCH_QUERIES = 1;

    /** The largest research query budget. */
    public static final int MAX_RESEARCH_QUERIES = 50;

    /** The smallest research iteration budget. */
    public static final int MIN_RESEARCH_ITERATIONS = 1;

    /** The largest research iteration budget. */
    public static final int MAX_RESEARCH_ITERATIONS = 5;

    /** The smallest research wall-clock budget, in seconds. */
    public static final int MIN_RESEARCH_SECONDS = 1;

    /** The largest research wall-clock budget, in seconds. */
    public static final int MAX_RESEARCH_SECONDS = 300;

    /** The smallest research results-per-query budget. */
    public static final int MIN_RESEARCH_RESULTS_PER_QUERY = 1;

    /** The largest research results-per-query budget; the 2026 endpoint default is 30. */
    public static final int MAX_RESEARCH_RESULTS_PER_QUERY = 60;

    public AnswersRequest {
        QueryText.requireValid(question);
        requireNonblankWhenPresent("model", model);
        if (maxCompletionTokens != null && maxCompletionTokens <= 0) {
            throw new UsageValidationError("max-completion-tokens must be a positive count");
        }
        requireBounded(
                "research-tokens-per-query",
                researchMaximumTokensPerQuery,
                MIN_RESEARCH_TOKENS_PER_QUERY,
                MAX_RESEARCH_TOKENS_PER_QUERY);
        requireBounded("research-queries", researchMaximumQueries, MIN_RESEARCH_QUERIES, MAX_RESEARCH_QUERIES);
        requireBounded(
                "research-iterations", researchMaximumIterations, MIN_RESEARCH_ITERATIONS, MAX_RESEARCH_ITERATIONS);
        requireBounded("research-seconds", researchMaximumSeconds, MIN_RESEARCH_SECONDS, MAX_RESEARCH_SECONDS);
        requireBounded(
                "research-results-per-query",
                researchMaximumResultsPerQuery,
                MIN_RESEARCH_RESULTS_PER_QUERY,
                MAX_RESEARCH_RESULTS_PER_QUERY);
        requireTwoLetterCountry(country);
        requireLanguageTag(language);
        requirePositiveWhenPresent("idle-timeout", idleTimeout);
        requirePositiveWhenPresent("stream-timeout", streamTimeout);
        requireResearchMembersCarryResearch(
                research,
                researchAllowThinking,
                researchMaximumTokensPerQuery,
                researchMaximumQueries,
                researchMaximumIterations,
                researchMaximumSeconds,
                researchMaximumResultsPerQuery);
        requireStreamingControls(stream, research, citations, entities);
        metadata = metadata == null ? null : Collections.unmodifiableMap(new TreeMap<>(metadata));
    }

    /** Starts a builder carrying the required question; every other field starts absent. */
    public static Builder builder(String question) {
        return new Builder(question);
    }

    private static void requireNonblankWhenPresent(String optionName, String value) {
        if (value != null && value.isBlank()) {
            throw new UsageValidationError(optionName + " must not be blank when supplied");
        }
    }

    private static void requireBounded(String optionName, Integer value, int minimum, int maximum) {
        if (value != null && (value < minimum || value > maximum)) {
            throw new UsageValidationError(optionName + " must be between " + minimum + " and " + maximum);
        }
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

    private static void requireLanguageTag(String language) {
        if (language == null) {
            return;
        }
        if (language.isBlank() || language.codePointCount(0, language.length()) < 2) {
            throw new UsageValidationError("language must be a language tag of at least two characters");
        }
    }

    private static void requirePositiveWhenPresent(String optionName, Duration budget) {
        if (budget != null && (budget.isZero() || budget.isNegative())) {
            throw new UsageValidationError(optionName + " must be positive when supplied");
        }
    }

    private static void requireResearchMembersCarryResearch(
            Boolean research,
            Boolean researchAllowThinking,
            Integer researchMaximumTokensPerQuery,
            Integer researchMaximumQueries,
            Integer researchMaximumIterations,
            Integer researchMaximumSeconds,
            Integer researchMaximumResultsPerQuery) {
        boolean researchEnabled = Boolean.TRUE.equals(research);
        if (!researchEnabled && researchAllowThinking != null) {
            throw new UsageValidationError("--research-thinking requires --research");
        }
        if (!researchEnabled && researchMaximumTokensPerQuery != null) {
            throw new UsageValidationError("--research-tokens-per-query requires --research");
        }
        if (!researchEnabled && researchMaximumQueries != null) {
            throw new UsageValidationError("--research-queries requires --research");
        }
        if (!researchEnabled && researchMaximumIterations != null) {
            throw new UsageValidationError("--research-iterations requires --research");
        }
        if (!researchEnabled && researchMaximumSeconds != null) {
            throw new UsageValidationError("--research-seconds requires --research");
        }
        if (!researchEnabled && researchMaximumResultsPerQuery != null) {
            throw new UsageValidationError("--research-results-per-query requires --research");
        }
    }

    private static void requireStreamingControls(boolean stream, Boolean research, Boolean citations, Boolean entities) {
        if (!stream) {
            if (Boolean.TRUE.equals(research)) {
                throw new UsageValidationError("--research requires --stream");
            }
            if (Boolean.TRUE.equals(citations)) {
                throw new UsageValidationError("--citations requires --stream");
            }
            if (Boolean.TRUE.equals(entities)) {
                throw new UsageValidationError("--entities requires --stream");
            }
        }
    }

    /** Collects the optional fields of one request; the question is required up front. */
    public static final class Builder {

        private final String question;
        private String model;
        private boolean stream;
        private Integer maxCompletionTokens;
        private Integer seed;
        private Boolean citations;
        private Boolean entities;
        private Boolean research;
        private Boolean researchAllowThinking;
        private Integer researchMaximumTokensPerQuery;
        private Integer researchMaximumQueries;
        private Integer researchMaximumIterations;
        private Integer researchMaximumSeconds;
        private Integer researchMaximumResultsPerQuery;
        private String country;
        private String language;
        private SafeSearch safeSearch;
        private Map<String, String> metadata;
        private Duration idleTimeout;
        private Duration streamTimeout;

        private Builder(String question) {
            this.question = Objects.requireNonNull(question, "question");
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        public Builder maxCompletionTokens(int maxCompletionTokens) {
            this.maxCompletionTokens = maxCompletionTokens;
            return this;
        }

        public Builder seed(int seed) {
            this.seed = seed;
            return this;
        }

        public Builder citations(boolean citations) {
            this.citations = citations;
            return this;
        }

        public Builder entities(boolean entities) {
            this.entities = entities;
            return this;
        }

        public Builder research(boolean research) {
            this.research = research;
            return this;
        }

        public Builder researchAllowThinking(boolean researchAllowThinking) {
            this.researchAllowThinking = researchAllowThinking;
            return this;
        }

        public Builder researchMaximumTokensPerQuery(int researchMaximumTokensPerQuery) {
            this.researchMaximumTokensPerQuery = researchMaximumTokensPerQuery;
            return this;
        }

        public Builder researchMaximumQueries(int researchMaximumQueries) {
            this.researchMaximumQueries = researchMaximumQueries;
            return this;
        }

        public Builder researchMaximumIterations(int researchMaximumIterations) {
            this.researchMaximumIterations = researchMaximumIterations;
            return this;
        }

        public Builder researchMaximumSeconds(int researchMaximumSeconds) {
            this.researchMaximumSeconds = researchMaximumSeconds;
            return this;
        }

        public Builder researchMaximumResultsPerQuery(int researchMaximumResultsPerQuery) {
            this.researchMaximumResultsPerQuery = researchMaximumResultsPerQuery;
            return this;
        }

        public Builder country(String country) {
            this.country = country;
            return this;
        }

        public Builder language(String language) {
            this.language = language;
            return this;
        }

        public Builder safeSearch(SafeSearch safeSearch) {
            this.safeSearch = safeSearch;
            return this;
        }

        public Builder metadata(Map<String, String> metadata) {
            this.metadata = metadata == null ? null : new LinkedHashMap<>(metadata);
            return this;
        }

        /**
         * Convenience setter for the documented {@code metadata.user_id} rider: merges the
         * caller's stable user identifier into the metadata map, with the later call winning
         * whenever this and {@link #metadata(Map)} both write the {@code user_id} key.
         */
        public Builder userId(String userId) {
            Objects.requireNonNull(userId, "userId");
            Map<String, String> merged =
                    this.metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(this.metadata);
            merged.put("user_id", userId);
            this.metadata = merged;
            return this;
        }

        public Builder idleTimeout(Duration idleTimeout) {
            this.idleTimeout = idleTimeout;
            return this;
        }

        public Builder streamTimeout(Duration streamTimeout) {
            this.streamTimeout = streamTimeout;
            return this;
        }

        public AnswersRequest build() {
            return new AnswersRequest(
                    question,
                    model,
                    stream,
                    maxCompletionTokens,
                    seed,
                    citations,
                    entities,
                    research,
                    researchAllowThinking,
                    researchMaximumTokensPerQuery,
                    researchMaximumQueries,
                    researchMaximumIterations,
                    researchMaximumSeconds,
                    researchMaximumResultsPerQuery,
                    country,
                    language,
                    safeSearch,
                    metadata,
                    idleTimeout,
                    streamTimeout);
        }
    }
}

package io.amscotti.bravesearch.domain.request;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.domain.error.UsageValidationError;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Validation contracts of one Answers request: the question keeps the shared query rules
 * (the endpoint documents none of its own), the model stays an opaque pass-through string,
 * every optional member is omitted rather than coerced, each research bound is enforced at
 * both edges, every research-member requires the research flag, research and the enabled
 * citations and entities flags require streaming, and the two stream budgets stay absent
 * until supplied.
 */
final class AnswersRequestTest {

    private static final String QUESTION = "what is the brave search api";

    @Test
    void theMinimalBlockingRequestCarriesOnlyQuestionAndStream() {
        AnswersRequest request = AnswersRequest.builder(QUESTION).stream(false).build();

        assertEquals(QUESTION, request.question());
        assertNull(request.model());
        assertFalse(request.stream());
        assertNull(request.maxCompletionTokens());
        assertNull(request.seed());
        assertNull(request.citations());
        assertNull(request.entities());
        assertNull(request.research());
        assertNull(request.researchAllowThinking());
        assertNull(request.researchMaximumTokensPerQuery());
        assertNull(request.researchMaximumQueries());
        assertNull(request.researchMaximumIterations());
        assertNull(request.researchMaximumSeconds());
        assertNull(request.researchMaximumResultsPerQuery());
        assertNull(request.country());
        assertNull(request.language());
        assertNull(request.safeSearch());
        assertNull(request.metadata());
        assertNull(request.idleTimeout());
        assertNull(request.streamTimeout());
    }

    @Test
    void theModelStaysOpaquePassThroughBraveSpellingsAndUnknownAlike() {
        assertEquals("brave", AnswersRequest.builder(QUESTION).stream(false).model("brave").build().model());
        assertEquals(
                "brave-pro", AnswersRequest.builder(QUESTION).stream(false).model("brave-pro").build().model());
        assertEquals(
                "future-model-x", AnswersRequest.builder(QUESTION).stream(false).model("future-model-x").build().model());
    }

    @Test
    void theQuestionKeepsTheSharedQueryRules() {
        assertThrows(UsageValidationError.class, () -> AnswersRequest.builder("   ").stream(false).build());
        assertThrows(
                UsageValidationError.class,
                () -> AnswersRequest.builder("q".repeat(401)).stream(false).build());
        String fiftyOneWords = ("word ").repeat(50) + "extra";
        assertThrows(UsageValidationError.class, () -> AnswersRequest.builder(fiftyOneWords).stream(false).build());
        assertDoesNotThrow(() -> AnswersRequest.builder("word ".repeat(50).strip()).stream(false).build());
    }

    @Test
    void completionTokensMustBePositiveAndSeedAcceptsEveryInteger() {
        assertThrows(
                UsageValidationError.class,
                () -> AnswersRequest.builder(QUESTION).stream(false).maxCompletionTokens(0).build());
        assertThrows(
                UsageValidationError.class,
                () -> AnswersRequest.builder(QUESTION).stream(false).maxCompletionTokens(-1).build());
        assertEquals(
                1, AnswersRequest.builder(QUESTION).stream(false).maxCompletionTokens(1).build().maxCompletionTokens());
        assertEquals(-7, (int) AnswersRequest.builder(QUESTION).stream(false).seed(-7).build().seed());
        assertEquals(2147483647, (int) AnswersRequest.builder(QUESTION).stream(false).seed(2147483647).build().seed());
    }

    @Test
    void everyResearchBoundIsEnforcedAtBothEdges() {
        int[] tokensPerQuery = {1024, 16384};
        assertDoesNotThrow(() -> researchTokensPerQuery(tokensPerQuery[0]));
        assertDoesNotThrow(() -> researchTokensPerQuery(tokensPerQuery[1]));
        assertThrows(UsageValidationError.class, () -> researchTokensPerQuery(1023));
        assertThrows(UsageValidationError.class, () -> researchTokensPerQuery(16385));

        assertDoesNotThrow(() -> researchQueries(1));
        assertDoesNotThrow(() -> researchQueries(50));
        assertThrows(UsageValidationError.class, () -> researchQueries(0));
        assertThrows(UsageValidationError.class, () -> researchQueries(51));

        assertDoesNotThrow(() -> researchIterations(1));
        assertDoesNotThrow(() -> researchIterations(5));
        assertThrows(UsageValidationError.class, () -> researchIterations(0));
        assertThrows(UsageValidationError.class, () -> researchIterations(6));

        assertDoesNotThrow(() -> researchSeconds(1));
        assertDoesNotThrow(() -> researchSeconds(300));
        assertThrows(UsageValidationError.class, () -> researchSeconds(0));
        assertThrows(UsageValidationError.class, () -> researchSeconds(301));

        assertDoesNotThrow(() -> researchResultsPerQuery(1));
        assertDoesNotThrow(() -> researchResultsPerQuery(60));
        assertThrows(UsageValidationError.class, () -> researchResultsPerQuery(0));
        assertThrows(UsageValidationError.class, () -> researchResultsPerQuery(61));
    }

    @Test
    void everyResearchMemberRequiresTheResearchFlag() {
        String[][] loneMembers = {
            {"thinking", "research-thinking"},
            {"tokens", "research-tokens-per-query"},
            {"queries", "research-queries"},
            {"iterations", "research-iterations"},
            {"seconds", "research-seconds"},
            {"results", "research-results-per-query"},
        };
        for (String[] member : loneMembers) {
            UsageValidationError rejected = assertThrows(
                    UsageValidationError.class, () -> researchWith(member[0], 1, false, true));
            assertTrue(
                    rejected.getMessage().contains(member[1]),
                    "the rejection must name the member: " + rejected.getMessage());
        }
    }

    @Test
    void researchRequiresStreaming() {
        UsageValidationError rejected =
                assertThrows(UsageValidationError.class, () -> researchWith("none", 0, true, false));
        assertTrue(rejected.getMessage().contains("--research"), rejected.getMessage());
    }

    @Test
    void enabledCitationsAndEntitiesRequireStreamingButExplicitFalseStayValidBlocking() {
        assertThrows(
                UsageValidationError.class,
                () -> AnswersRequest.builder(QUESTION).stream(false).citations(true).build());
        assertThrows(
                UsageValidationError.class, () -> AnswersRequest.builder(QUESTION).stream(false).entities(true).build());
        AnswersRequest explicitFalse = AnswersRequest.builder(QUESTION)
                .stream(false)
                .citations(false)
                .entities(false)
                .build();
        assertEquals(Boolean.FALSE, explicitFalse.citations());
        assertEquals(Boolean.FALSE, explicitFalse.entities());
    }

    @Test
    void researchThinkingIsRequiredByResearchAndBlocksTooThroughThatRule() {
        assertThrows(
                UsageValidationError.class,
                () -> AnswersRequest.builder(QUESTION)
                        .stream(true)
                        .researchAllowThinking(true)
                        .build());
        assertThrows(
                UsageValidationError.class,
                () -> AnswersRequest.builder(QUESTION)
                        .stream(false)
                        .research(true)
                        .researchAllowThinking(true)
                        .build());
        AnswersRequest valid = AnswersRequest.builder(QUESTION)
                .stream(true)
                .research(true)
                .researchAllowThinking(false)
                .build();
        assertEquals(Boolean.FALSE, valid.researchAllowThinking());
    }

    @Test
    void localeMembersValidateLikeTheWebFamily() {
        assertThrows(UsageValidationError.class, () -> AnswersRequest.builder(QUESTION).stream(false).country("DEU").build());
        assertThrows(
                UsageValidationError.class, () -> AnswersRequest.builder(QUESTION).stream(false).country("ALL").build());
        assertEquals("DE", AnswersRequest.builder(QUESTION).stream(false).country("DE").build().country());
        assertThrows(
                UsageValidationError.class, () -> AnswersRequest.builder(QUESTION).stream(false).language("d").build());
        assertEquals("de", AnswersRequest.builder(QUESTION).stream(false).language("de").build().language());
        assertEquals(
                SafeSearch.STRICT,
                AnswersRequest.builder(QUESTION).stream(false).safeSearch(SafeSearch.STRICT).build().safeSearch());
    }

    @Test
    void metadataTravelsAsAnImmutablePassThroughMap() {
        AnswersRequest request = AnswersRequest.builder(QUESTION)
                .stream(false)
                .metadata(Map.of("user_id", "agent-1"))
                .build();
        assertEquals(Map.of("user_id", "agent-1"), request.metadata());
        assertThrows(UnsupportedOperationException.class, () -> request.metadata().put("later", "value"));
    }

    @Test
    void userIdSeedsTheMetadataMapAndMergesWithExplicitEntries() {
        assertEquals(Map.of("user_id", "agent-7"), AnswersRequest.builder(QUESTION)
                .stream(false)
                .userId("agent-7")
                .build()
                .metadata());
        assertEquals(
                Map.of("user_id", "agent-9", "session", "nightly"),
                AnswersRequest.builder(QUESTION)
                        .stream(false)
                        .metadata(Map.of("user_id", "agent-1", "session", "nightly"))
                        .userId("agent-9")
                        .build()
                        .metadata());
        assertEquals(
                Map.of("user_id", "kept-by-later-call"),
                AnswersRequest.builder(QUESTION)
                        .stream(false)
                        .userId("overwritten-by-later-call")
                        .metadata(Map.of("user_id", "kept-by-later-call"))
                        .build()
                        .metadata());
        assertThrows(
                NullPointerException.class, () -> AnswersRequest.builder(QUESTION).userId(null));
    }

    @Test
    void streamBudgetsStayAbsentUntilSuppliedAndMustBePositive() {
        assertNull(AnswersRequest.builder(QUESTION).stream(true).build().idleTimeout());
        assertNull(AnswersRequest.builder(QUESTION).stream(true).build().streamTimeout());
        assertEquals(
                Duration.ofSeconds(60),
                AnswersRequest.builder(QUESTION)
                        .stream(true)
                        .idleTimeout(Duration.ofSeconds(60))
                        .build()
                        .idleTimeout());
        assertEquals(
                Duration.ofSeconds(330),
                AnswersRequest.builder(QUESTION)
                        .stream(true)
                        .streamTimeout(Duration.ofSeconds(330))
                        .build()
                        .streamTimeout());
        assertThrows(
                UsageValidationError.class,
                () -> AnswersRequest.builder(QUESTION).stream(true).idleTimeout(Duration.ZERO).build());
        assertThrows(
                UsageValidationError.class,
                () -> AnswersRequest.builder(QUESTION)
                        .stream(true)
                        .streamTimeout(Duration.ofSeconds(-1))
                        .build());
    }

    private static AnswersRequest researchTokensPerQuery(int value) {
        return baseResearch().researchMaximumTokensPerQuery(value).build();
    }

    private static AnswersRequest researchQueries(int value) {
        return baseResearch().researchMaximumQueries(value).build();
    }

    private static AnswersRequest researchIterations(int value) {
        return baseResearch().researchMaximumIterations(value).build();
    }

    private static AnswersRequest researchSeconds(int value) {
        return baseResearch().researchMaximumSeconds(value).build();
    }

    private static AnswersRequest researchResultsPerQuery(int value) {
        return baseResearch().researchMaximumResultsPerQuery(value).build();
    }

    /**
     * @param member the research member to set, or {@code "none"}; {@code "thinking"} sets the
     *     tri-state flag instead of a bound
     */
    private static AnswersRequest researchWith(String member, int value, boolean research, boolean stream) {
        AnswersRequest.Builder builder = AnswersRequest.builder(QUESTION).stream(stream);
        if (research) {
            builder.research(true);
        }
        switch (member) {
            case "thinking" -> builder.researchAllowThinking(value > 0);
            case "tokens" -> builder.researchMaximumTokensPerQuery(value);
            case "queries" -> builder.researchMaximumQueries(value);
            case "iterations" -> builder.researchMaximumIterations(value);
            case "seconds" -> builder.researchMaximumSeconds(value);
            case "results" -> builder.researchMaximumResultsPerQuery(value);
            case "none" -> {
                // research alone, no member
            }
            default -> throw new IllegalArgumentException("unknown member " + member);
        }
        return builder.build();
    }

    private static AnswersRequest.Builder baseResearch() {
        return AnswersRequest.builder(QUESTION).stream(true).research(true);
    }

}

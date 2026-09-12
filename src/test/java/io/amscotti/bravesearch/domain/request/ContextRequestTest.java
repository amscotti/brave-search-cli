package io.amscotti.bravesearch.domain.request;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.amscotti.bravesearch.domain.error.UsageValidationError;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Validation contracts of the LLM context request: every documented numeric bound enforced
 * at both edges, defaults never invented locally (an unsupplied optional stays null and is
 * omitted from the wire), the threshold and local-recall modes parsing exactly their
 * documented tokens, the shared query rules, and the location group pairing coordinates
 * inside their ranges without any timezone member.
 */
final class ContextRequestTest {

    @Test
    void everyNumericBoundIsEnforcedAtBothEdges() {
        assertBound("count", 1, 50, ContextRequest.MIN_COUNT, ContextRequest.MAX_COUNT);
        assertBound("max-urls", 1, 50, ContextRequest.MIN_MAX_URLS, ContextRequest.MAX_MAX_URLS);
        assertBound("max-tokens", 1024, 32768, ContextRequest.MIN_MAX_TOKENS, ContextRequest.MAX_MAX_TOKENS);
        assertBound("max-snippets", 1, 256, ContextRequest.MIN_MAX_SNIPPETS, ContextRequest.MAX_MAX_SNIPPETS);
        assertBound(
                "max-tokens-per-url",
                512,
                8192,
                ContextRequest.MIN_MAX_TOKENS_PER_URL,
                ContextRequest.MAX_MAX_TOKENS_PER_URL);
        assertBound(
                "max-snippets-per-url",
                1,
                100,
                ContextRequest.MIN_MAX_SNIPPETS_PER_URL,
                ContextRequest.MAX_MAX_SNIPPETS_PER_URL);
    }

    @Test
    void everyNumericOptionIsOptionalAndDefaultsToOmitted() {
        ContextRequest bare = ContextRequest.builder("q").build();

        assertNull(bare.count());
        assertNull(bare.maxUrls());
        assertNull(bare.maxTokens());
        assertNull(bare.maxSnippets());
        assertNull(bare.maxTokensPerUrl());
        assertNull(bare.maxSnippetsPerUrl());
        assertNull(bare.country());
        assertNull(bare.searchLang());
        assertNull(bare.safeSearch());
        assertNull(bare.freshness());
        assertNull(bare.threshold());
        assertNull(bare.sourceMetadata());
        assertNull(bare.enableLocal());
        assertNull(bare.location());
    }

    @Test
    void countryAcceptsExactlyTwoLetterCodesSoAllIsRefused() {
        assertDoesNotThrow(() -> ContextRequest.builder("q").country("DE").build());
        assertDoesNotThrow(() -> ContextRequest.builder("q").country("us").build());
        for (String refused : new String[] {"D", "ALL", "DEU", "1A", "D-", " ", "Usa"}) {
            assertEquals(
                    "country must be a two-letter code",
                    assertThrows(
                                    UsageValidationError.class,
                                    () -> ContextRequest.builder("q").country(refused).build(),
                                    "country must be refused: " + refused)
                            .getMessage(),
                    "the rejection names the rule: " + refused);
        }
    }

    @Test
    void searchLangAcceptsDocumentedSubtagsWithoutForcingLengthTwo() {
        assertDoesNotThrow(() -> ContextRequest.builder("q").searchLang("en").build());
        assertDoesNotThrow(() -> ContextRequest.builder("q").searchLang("en-US").build());
        assertDoesNotThrow(() -> ContextRequest.builder("q").searchLang("zh-Hans").build());
        assertThrows(UsageValidationError.class, () -> ContextRequest.builder("q").searchLang("e").build());
        assertThrows(UsageValidationError.class, () -> ContextRequest.builder("q").searchLang(" ").build());
    }

    @Test
    void thresholdParsesExactlyTheFourDocumentedModes() {
        assertEquals(ContextThreshold.STRICT, ContextThreshold.parse("strict"));
        assertEquals(ContextThreshold.BALANCED, ContextThreshold.parse("balanced"));
        assertEquals(ContextThreshold.LENIENT, ContextThreshold.parse("lenient"));
        assertEquals(ContextThreshold.DISABLED, ContextThreshold.parse("disabled"));
        assertEquals("balanced", ContextThreshold.BALANCED.wireName());
        for (String refused : new String[] {"STRICT", "Balanced", " strict", "aggressive", ""}) {
            assertEquals(
                    "threshold must be strict, balanced, lenient, or disabled",
                    assertThrows(UsageValidationError.class, () -> ContextThreshold.parse(refused), "refuse " + refused)
                            .getMessage());
        }
    }

    @Test
    void localRecallParsesAutoOnOffAndOmitsAutoFromTheWire() {
        assertEquals(LocalRecall.AUTO, LocalRecall.parse("auto"));
        assertEquals(LocalRecall.ON, LocalRecall.parse("on"));
        assertEquals(LocalRecall.OFF, LocalRecall.parse("off"));
        assertNull(LocalRecall.AUTO.enableLocal(), "auto-detection is the omitted wire state");
        assertEquals(Boolean.TRUE, LocalRecall.ON.enableLocal());
        assertEquals(Boolean.FALSE, LocalRecall.OFF.enableLocal());
        for (String refused : new String[] {"AUTO", "yes", "on ", ""}) {
            assertEquals(
                    "local must be auto, on, or off",
                    assertThrows(UsageValidationError.class, () -> LocalRecall.parse(refused), "refuse " + refused)
                            .getMessage());
        }
    }

    @Test
    void coordinatesMustArrivePaired() {
        assertEquals(
                "loc-lat and loc-long must be supplied together",
                assertThrows(
                                UsageValidationError.class,
                                () -> location(40.5, null))
                        .getMessage());
        assertThrows(UsageValidationError.class, () -> location(null, -73.5));
        assertDoesNotThrow(() -> location(40.5, -73.5));
    }

    @Test
    void coordinatesStayInsideTheirDocumentedRangesIncludingTheEdges() {
        assertDoesNotThrow(() -> location(90.0, -180.0));
        assertDoesNotThrow(() -> location(-90.0, 180.0));
        assertEquals(
                "loc-lat must be between -90 and 90",
                assertThrows(UsageValidationError.class, () -> location(90.01, 0.0)).getMessage());
        assertEquals(
                "loc-long must be between -180 and 180",
                assertThrows(UsageValidationError.class, () -> location(0.0, -180.5)).getMessage());
    }

    @Test
    void coordinatesRejectTheNonFiniteDoublesARangeComparisonCannotJudge() {
        assertEquals(
                "loc-lat must be between -90 and 90",
                assertThrows(UsageValidationError.class, () -> location(Double.NaN, 0.0)).getMessage());
        assertEquals(
                "loc-long must be between -180 and 180",
                assertThrows(UsageValidationError.class, () -> location(0.0, Double.NaN)).getMessage());
        assertThrows(UsageValidationError.class, () -> location(Double.POSITIVE_INFINITY, 0.0));
        assertThrows(UsageValidationError.class, () -> location(0.0, Double.NEGATIVE_INFINITY));
    }

    @Test
    void locationTextMembersAreNonblankAndCodesAreTwoUppercaseLetters() {
        assertEquals(
                "loc-city must not be blank when supplied",
                assertThrows(UsageValidationError.class, () -> textLocation(" ", null, null, null, null)).getMessage());
        assertEquals(
                "loc-state must be a two-letter uppercase code",
                assertThrows(UsageValidationError.class, () -> textLocation(null, "qc", null, null, null)).getMessage());
        assertEquals(
                "loc-country must be a two-letter uppercase code",
                assertThrows(UsageValidationError.class, () -> textLocation(null, null, null, "ca", null)).getMessage());
        assertEquals(
                "loc-state-name must not be blank when supplied",
                assertThrows(UsageValidationError.class, () -> textLocation(null, null, " ", null, null)).getMessage());
        assertEquals(
                "loc-postal-code must not be blank when supplied",
                assertThrows(UsageValidationError.class, () -> textLocation(null, null, null, null, " ")).getMessage());
        assertDoesNotThrow(() -> textLocation("Seattle", "WA", "Washington", "US", "98101"));
    }

    @Test
    void theQuerySharesTheCommonSearchValidation() {
        assertThrows(UsageValidationError.class, () -> ContextRequest.builder("   ").build());
        assertThrows(UsageValidationError.class, () -> ContextRequest.builder("a".repeat(401)).build());
        assertThrows(
                UsageValidationError.class,
                () -> ContextRequest.builder(String.join(" ", java.util.Collections.nCopies(51, "word"))).build());
    }

    @Test
    void aFullRequestKeepsEverySuppliedMember() {
        ContextRequest full = ContextRequest.builder("hello world")
                .country("DE")
                .searchLang("de")
                .safeSearch(SafeSearch.STRICT)
                .freshness(new Freshness.DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28)))
                .count(7)
                .maxUrls(10)
                .maxTokens(4096)
                .maxSnippets(100)
                .maxTokensPerUrl(2048)
                .maxSnippetsPerUrl(50)
                .threshold(ContextThreshold.BALANCED)
                .sourceMetadata(true)
                .enableLocal(false)
                .location(new ContextRequest.Location(
                        47.6062, -122.3321, "Seattle", "WA", "Washington", "US", "98101"))
                .build();

        assertEquals("hello world", full.query());
        assertEquals("DE", full.country());
        assertEquals("de", full.searchLang());
        assertEquals(SafeSearch.STRICT, full.safeSearch());
        assertEquals(new Freshness.DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28)), full.freshness());
        assertEquals(7, full.count());
        assertEquals(10, full.maxUrls());
        assertEquals(4096, full.maxTokens());
        assertEquals(100, full.maxSnippets());
        assertEquals(2048, full.maxTokensPerUrl());
        assertEquals(50, full.maxSnippetsPerUrl());
        assertEquals(ContextThreshold.BALANCED, full.threshold());
        assertEquals(Boolean.TRUE, full.sourceMetadata());
        assertEquals(Boolean.FALSE, full.enableLocal());
        assertEquals(
                new ContextRequest.Location(47.6062, -122.3321, "Seattle", "WA", "Washington", "US", "98101"),
                full.location());
    }

    @Test
    void boundRejectionsNameTheOptionAndBothEdges() {
        assertEquals(
                "max-tokens must be between 1024 and 32768",
                assertThrows(UsageValidationError.class, () -> ContextRequest.builder("q").maxTokens(1023).build())
                        .getMessage());
        assertEquals(
                "count must be between 1 and 50",
                assertThrows(UsageValidationError.class, () -> ContextRequest.builder("q").count(51).build())
                        .getMessage());
    }

    /** Asserts one bounded option refuses one below its floor and one above its ceiling and keeps both edges. */
    private static void assertBound(String setter, int min, int max, int declaredMin, int declaredMax) {
        assertEquals(min, declaredMin, setter + " floor is the documented one");
        assertEquals(max, declaredMax, setter + " ceiling is the documented one");
        assertDoesNotThrow(() -> set(ContextRequest.builder("q"), setter, min));
        assertDoesNotThrow(() -> set(ContextRequest.builder("q"), setter, max));
        assertThrows(UsageValidationError.class, () -> set(ContextRequest.builder("q"), setter, min - 1), setter + " below the floor");
        assertThrows(UsageValidationError.class, () -> set(ContextRequest.builder("q"), setter, max + 1), setter + " above the ceiling");
    }

    private static ContextRequest set(ContextRequest.Builder builder, String setter, int value) {
        ContextRequest.Builder filled = switch (setter) {
            case "count" -> builder.count(value);
            case "max-urls" -> builder.maxUrls(value);
            case "max-tokens" -> builder.maxTokens(value);
            case "max-snippets" -> builder.maxSnippets(value);
            case "max-tokens-per-url" -> builder.maxTokensPerUrl(value);
            case "max-snippets-per-url" -> builder.maxSnippetsPerUrl(value);
            default -> throw new IllegalArgumentException("unknown setter " + setter);
        };
        return filled.build();
    }

    private static void location(Double latitude, Double longitude) {
        ContextRequest.builder("q")
                .location(new ContextRequest.Location(latitude, longitude, null, null, null, null, null))
                .build();
    }

    private static void textLocation(String city, String state, String stateName, String country, String postalCode) {
        ContextRequest.builder("q")
                .location(new ContextRequest.Location(null, null, city, state, stateName, country, postalCode))
                .build();
    }

}

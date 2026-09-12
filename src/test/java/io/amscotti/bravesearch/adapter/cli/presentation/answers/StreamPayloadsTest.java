package io.amscotti.bravesearch.adapter.cli.presentation.answers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.domain.metadata.Usage;
import io.amscotti.bravesearch.domain.result.Projection;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The tolerant member mapping of streamed tag payloads: the usage payload's documented and
 * chat-completions spellings land on the usage value at exact decimal scale with unparsed
 * members noted rather than guessed, and the citation and entity payloads project exactly
 * the members each record family carries, never inventing one.
 */
final class StreamPayloadsTest {

    private static final JsonMappers MAPPERS = new JsonMappers();

    @Test
    void usageReadsTheDocumentedSpellingAtExactDecimalScale() {
        JsonNode payload = tree("""
                {"requests":1,"queries":2,"tokens_in":900,"tokens_out":120,
                 "requests_cost":"0.0003","queries_cost":"0.0004",
                 "tokens_in_cost":0.000000009,"tokens_out_cost":0.000000011,
                 "total_cost":"0.0042","future_meter":"held"}""");

        Usage usage = StreamPayloads.usage(payload);

        assertEquals(1L, usage.requests());
        assertEquals(2L, usage.queries());
        assertEquals(900L, usage.tokensIn());
        assertEquals(120L, usage.tokensOut());
        assertEquals(new BigDecimal("0.0003"), usage.requestsCost());
        assertEquals(new BigDecimal("0.0004"), usage.queriesCost());
        assertEquals(new BigDecimal("9E-9"), usage.tokensInCost());
        assertEquals(new BigDecimal("1.1E-8"), usage.tokensOutCost());
        assertEquals(new BigDecimal("0.0042"), usage.totalCost());
        assertEquals("held", usage.unknownFields().get("future_meter"));
        assertTrue(usage.notes().isEmpty(), () -> "every member parsed: " + usage.notes());
    }

    @Test
    void usageAcceptsTheChatCompletionsTokenSpellings() {
        JsonNode payload = tree("{\"prompt_tokens\":15,\"completion_tokens\":7}");

        Usage usage = StreamPayloads.usage(payload);

        assertEquals(15L, usage.tokensIn());
        assertEquals(7L, usage.tokensOut());
    }

    @Test
    void theDocumentedTokenSpellingBeatsAnAliasInEitherOrder() {
        assertEquals(9L, StreamPayloads.usage(tree("{\"input_tokens\":5,\"tokens_in\":9}")).tokensIn());
        assertEquals(9L, StreamPayloads.usage(tree("{\"tokens_in\":9,\"input_tokens\":5}")).tokensIn());
        assertEquals(6L, StreamPayloads.usage(tree("{\"completion_tokens\":4,\"tokens_out\":6}")).tokensOut());
        assertEquals(6L, StreamPayloads.usage(tree("{\"tokens_out\":6,\"completion_tokens\":4}")).tokensOut());
    }

    @Test
    void anUnparseableDocumentedTokenMemberStillBeatsItsAlias() {
        Usage usage = StreamPayloads.usage(tree("{\"input_tokens\":5,\"tokens_in\":\"many\"}"));

        assertNull(usage.tokensIn(), "the documented member's failed parse is not repaired by an alias");
        assertEquals(1, usage.notes().size(), () -> "the failed member alone left a note: " + usage.notes());
    }

    @Test
    void anIntegralCounterBeyondTheLongRangeDropsWithANoteLikeItsTextualSpelling() {
        JsonNode payload = tree(
                "{\"tokens_in\":18446744073709551617,\"queries\":\"18446744073709551617\"}");

        Usage usage = StreamPayloads.usage(payload);

        assertNull(usage.tokensIn(), "a counter beyond the long range would wrap under narrowing");
        assertNull(usage.queries());
        assertEquals(2, usage.notes().size(), () -> "each oversized spelling left a note: " + usage.notes());
    }

    @Test
    void unparseableUsageMembersDropAloneAndLeaveANote() {
        JsonNode payload = tree(
                "{\"requests\":\"many\",\"queries\":2.5,\"total_cost\":-1,\"tokens_in\":4}");

        Usage usage = StreamPayloads.usage(payload);

        assertNull(usage.requests());
        assertNull(usage.queries(), "a fractional counter is not a counter");
        assertNull(usage.totalCost());
        assertEquals(4L, usage.tokensIn());
        assertEquals(3, usage.notes().size(), () -> "the unparsable members each left a note: " + usage.notes());
        assertTrue(usage.notes().stream().allMatch(note -> !note.contains("many")), "notes never quote values");
    }

    @Test
    void aNonObjectUsagePayloadKeepsEveryFieldAbsent() {
        Usage usage = StreamPayloads.usage(tree("[1,2]"));

        assertNull(usage.requests());
        assertNull(usage.totalCost());
        assertTrue(usage.unknownFields().isEmpty());
    }

    @Test
    void citationProjectsExactlyTheCarriedMembers() {
        JsonNode payload = tree(
                "{\"number\":1,\"url\":\"https://search.brave.com/\",\"snippet\":\"an index\",\"future\":\"dropped\"}");

        List<Projection.Field> fields = StreamPayloads.citation(payload);

        assertEquals(
                List.of(
                        new Projection.Field("number", new Projection.Decimal(BigDecimal.ONE)),
                        new Projection.Field("url", new Projection.Text("https://search.brave.com/")),
                        new Projection.Field("snippet", new Projection.Text("an index"))),
                fields);
    }

    @Test
    void aCitationIndexBeyondTheLongRangeIsOmittedNotWrapped() {
        JsonNode payload = tree("{\"number\":18446744073709551617,\"url\":\"https://brave.com\"}");

        List<Projection.Field> fields = StreamPayloads.citation(payload);

        assertEquals(
                List.of(new Projection.Field("url", new Projection.Text("https://brave.com"))), fields);
    }

    @Test
    void entityProjectsTheKnownTextMembersInOrder() {
        JsonNode payload = tree(
                "{\"name\":\"Brave Search\",\"url\":\"https://search.brave.com\",\"type\":\"product\",\"weight\":7}");

        List<Projection.Field> fields = StreamPayloads.entity(payload);

        assertEquals(
                List.of(
                        new Projection.Field("name", new Projection.Text("Brave Search")),
                        new Projection.Field("url", new Projection.Text("https://search.brave.com")),
                        new Projection.Field("entity_type", new Projection.Text("product"))),
                fields);
    }

    @Test
    void aPayloadWithoutKnownMembersProjectsNothing() {
        assertTrue(StreamPayloads.citation(tree("{\"other\":true}")).isEmpty());
        assertTrue(StreamPayloads.entity(tree("{}")).isEmpty());
    }

    private static JsonNode tree(String json) {
        return MAPPERS.upstreamReader().readTree(json);
    }
}

package io.amscotti.bravesearch.domain.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The order reconstruction of one enrichment chunk: every input position — duplicate ids
 * included — is matched against the returned entries by id, a returned id fills every
 * input position that carries it, and a missing or expired id keeps its original
 * position as a placeholder slot. The reconstruction is pure over ids and payloads, so
 * no parsing concern can leak into the matching rule.
 */
final class OrderReconstructorTest {

    @Test
    void returnedEntriesFillTheirInputPositionsInInputOrder() {
        OrderReconstructor.Reconstruction<String> reconstruction =
                OrderReconstructor.reconstruct(List.of("a", "b", "c"), returned("b", "two", "a", "one"));

        assertEquals(
                java.util.Arrays.asList("one", "two", null),
                reconstruction.slots().stream().map(OrderReconstructor.Slot::value).toList(),
                "each position carries the payload whose id matched it");
        assertEquals(
                List.of(0, 1, 2), reconstruction.slots().stream().map(OrderReconstructor.Slot::position).toList());
        assertEquals(2, reconstruction.returnedCount());
        assertEquals(1, reconstruction.missingCount());
    }

    @Test
    void aDuplicateInputIdIsFilledAtEveryPositionItAppears() {
        OrderReconstructor.Reconstruction<String> reconstruction =
                OrderReconstructor.reconstruct(List.of("a", "b", "a"), returned("a", "again"));

        assertTrue(reconstruction.slots().get(0).present());
        assertFalse(reconstruction.slots().get(1).present(), "the unmatched id stays a placeholder");
        assertTrue(
                reconstruction.slots().get(2).present(), "a returned duplicate input id fills every matching position");
        assertEquals("again", reconstruction.slots().get(2).value());
        assertEquals(2, reconstruction.returnedCount());
        assertEquals(1, reconstruction.missingCount());
    }

    @Test
    void theFirstReturnedEntryOfAnIdWinsEveryPositionItFills() {
        OrderReconstructor.Reconstruction<String> reconstruction =
                OrderReconstructor.reconstruct(List.of("a", "a"), returned("a", "first", "a", "second"));

        assertEquals(
                "first",
                reconstruction.slots().get(0).value(),
                "the first returned entry of an id is the payload its positions carry");
        assertEquals(
                "first",
                reconstruction.slots().get(1).value(),
                "a later returned entry of the same id never replaces the first");
    }

    @Test
    void aMissingOrExpiredIdKeepsItsOriginalPositionAsAPlaceholder() {
        OrderReconstructor.Reconstruction<String> reconstruction =
                OrderReconstructor.reconstruct(List.of("gone", "a"), returned("a", "here"));

        OrderReconstructor.Slot<String> placeholder = reconstruction.slots().getFirst();
        assertEquals(0, placeholder.position());
        assertEquals("gone", placeholder.id());
        assertFalse(placeholder.present());
        assertEquals("here", reconstruction.slots().get(1).value());
    }

    @Test
    void entriesNoInputPositionAskedForAreIgnoredWithoutBreakingTheOrder() {
        OrderReconstructor.Reconstruction<String> reconstruction =
                OrderReconstructor.reconstruct(List.of("a"), returned("unrequested", "x", "a", "one"));

        assertEquals(1, reconstruction.slots().size());
        assertEquals("one", reconstruction.slots().getFirst().value());
        assertEquals(1, reconstruction.returnedCount());
    }

    @Test
    void anEmptyReturnedListPlacesEveryInputIdAsAPlaceholder() {
        OrderReconstructor.Reconstruction<String> reconstruction =
                OrderReconstructor.reconstruct(List.of("a", "b"), List.of());

        assertEquals(2, reconstruction.missingCount());
        assertEquals(0, reconstruction.returnedCount());
        assertTrue(reconstruction.slots().stream().noneMatch(OrderReconstructor.Slot::present));
    }

    @Test
    void reconstructionRejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> OrderReconstructor.reconstruct(null, List.of()));
        assertThrows(
                NullPointerException.class,
                () -> OrderReconstructor.reconstruct(List.of("a"), null));
        assertThrows(NullPointerException.class, () -> OrderReconstructor.reconstruct(List.of("a"), returned(null, "x")));
        assertThrows(NullPointerException.class, () -> OrderReconstructor.reconstruct(List.of("a"), returned("a", null)));
    }

    private static List<OrderReconstructor.Identified<String>> returned(String... idThenValue) {
        java.util.ArrayList<OrderReconstructor.Identified<String>> entries = new java.util.ArrayList<>();
        for (int index = 0; index < idThenValue.length; index += 2) {
            entries.add(new OrderReconstructor.Identified<>(idThenValue[index], idThenValue[index + 1]));
        }
        return entries;
    }
}

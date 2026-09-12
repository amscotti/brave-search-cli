package io.amscotti.bravesearch.domain.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The web logical-result list keeps its original order, freezes the entries it was given, and
 * rejects positions that were never part of the upstream array, because the position is the
 * record's promise about where the result came from.
 */
final class WebResultsTest {

    @Test
    void entriesKeepTheirGivenOrderAndTheListIsImmutable() {
        WebResults.Entry first = new WebResults.Entry(0, "First", "https://example.com/first", "One.");
        WebResults.Entry second = new WebResults.Entry(1, "Second", "https://example.com/second", null);
        List<WebResults.Entry> source = new ArrayList<>(List.of(first, second));

        WebResults results = new WebResults(source);

        assertEquals(List.of(first, second), results.entries());
        source.clear();
        assertEquals(2, results.entries().size(), "later mutation of the source list cannot change the results");
        assertThrows(
                UnsupportedOperationException.class,
                () -> results.entries().add(new WebResults.Entry(2, "Third", null, null)),
                "the entry list is frozen at construction");
    }

    @Test
    void aNegativePositionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new WebResults.Entry(-1, "First", null, null));
    }

    @Test
    void theWebBucketNameIsStableContractVocabulary() {
        assertEquals("web", WebResults.BUCKET);
    }
}

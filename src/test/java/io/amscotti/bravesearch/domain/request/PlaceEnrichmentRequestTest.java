package io.amscotti.bravesearch.domain.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.domain.result.PagedSearch;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The enrichment request grammar of the place detail endpoints: one invocation carries
 * one through two hundred opaque ids, duplicates and input order survive verbatim, the
 * ids never face internal-format validation because they are opaque and ephemeral, and
 * the chunk view of the request — up to twenty ids per upstream request — slices the
 * input in order so the walk can dispatch consecutive chunk requests.
 */
final class PlaceEnrichmentRequestTest {

    @Test
    void oneInvocationAcceptsUpToTwoHundredIds() {
        assertEquals(200, new PlaceEnrichmentRequest(PlaceEnrichmentRequest.Kind.DETAILS, ids(200)).ids().size());
    }

    @Test
    void twoHundredAndOneIdsAreRejected() {
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> new PlaceEnrichmentRequest(PlaceEnrichmentRequest.Kind.DETAILS, ids(201)));

        assertEquals("an invocation carries at most 200 ids", rejected.getMessage());
    }

    @Test
    void anInvocationRequiresAtLeastOneId() {
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> new PlaceEnrichmentRequest(PlaceEnrichmentRequest.Kind.DETAILS, List.of()));

        assertEquals("an invocation carries at least one id", rejected.getMessage());
    }

    @Test
    void duplicatesAndInputOrderSurviveVerbatim() {
        PlaceEnrichmentRequest request =
                new PlaceEnrichmentRequest(PlaceEnrichmentRequest.Kind.DETAILS, List.of("b", "a", "b", "a"));

        assertEquals(List.of("b", "a", "b", "a"), request.ids(), "duplicates and order are never normalized away");
    }

    @Test
    void anyOpaqueIdSpellingIsAccepted() {
        PlaceEnrichmentRequest request = new PlaceEnrichmentRequest(
                PlaceEnrichmentRequest.Kind.DETAILS, List.of("", " spaces inside ", "loc4FNMQJ...==\uD83D\uDD25", "a,b"));

        assertEquals(4, request.ids().size(), "the internal format of an id is never validated");
    }

    @Test
    void theInvocationCapComposesNoMoreChunksThanTheSharedWalkAccepts() {
        int capChunkCount = (PlaceEnrichmentRequest.MAX_IDS + PlaceEnrichmentRequest.MAX_PER_REQUEST - 1)
                / PlaceEnrichmentRequest.MAX_PER_REQUEST;

        assertTrue(
                capChunkCount <= PagedSearch.MAX_PAGE,
                "an invocation cap beyond the walk's page budget would overflow the shared orchestrator"
                        + " as an internal error instead of a usage rejection: ceil(MAX_IDS/MAX_PER_REQUEST)"
                        + " must stay within PagedSearch.MAX_PAGE");
        assertEquals(
                PagedSearch.MAX_PAGE,
                new PlaceEnrichmentRequest(PlaceEnrichmentRequest.Kind.DETAILS, ids(PlaceEnrichmentRequest.MAX_IDS))
                        .chunkCount(),
                "a cap-sized invocation composes exactly the walk's full chunk budget");
    }

    @Test
    void theChunkViewSlicesTheInputInOrderIntoBoundedRequests() {
        PlaceEnrichmentRequest request =
                new PlaceEnrichmentRequest(PlaceEnrichmentRequest.Kind.DETAILS, ids(45));

        assertEquals(3, request.chunkCount(), "45 ids fan out into three chunk requests");
        assertEquals(range(1, 20), request.idsOfChunk(1));
        assertEquals(range(21, 40), request.idsOfChunk(2));
        assertEquals(range(41, 45), request.idsOfChunk(3));
    }

    @Test
    void twentyIdsStayOneSingleChunkRequest() {
        PlaceEnrichmentRequest request =
                new PlaceEnrichmentRequest(PlaceEnrichmentRequest.Kind.DETAILS, ids(20));

        assertEquals(1, request.chunkCount());
        assertEquals(range(1, 20), request.idsOfChunk(1));
    }

    @Test
    void duplicateIdsNeverSplitAcrossTheChunkBoundaryTheyEarn() {
        PlaceEnrichmentRequest request = new PlaceEnrichmentRequest(
                PlaceEnrichmentRequest.Kind.DESCRIPTIONS,
                java.util.Collections.nCopies(21, "same"));

        assertEquals(2, request.chunkCount());
        assertEquals(java.util.Collections.nCopies(20, "same"), request.idsOfChunk(1));
        assertEquals(List.of("same"), request.idsOfChunk(2));
    }

    @Test
    void theChunkViewRejectsNumbersOutsideTheWalk() {
        PlaceEnrichmentRequest request =
                new PlaceEnrichmentRequest(PlaceEnrichmentRequest.Kind.DETAILS, ids(45));

        assertThrows(IllegalArgumentException.class, () -> request.idsOfChunk(0));
        assertThrows(IllegalArgumentException.class, () -> request.idsOfChunk(4));
    }

    private static List<String> ids(int count) {
        return range(1, count);
    }

    private static List<String> range(int first, int last) {
        return IntStream.rangeClosed(first, last).mapToObj(PlaceEnrichmentRequestTest::id).toList();
    }

    private static String id(int number) {
        return "poi-" + number;
    }
}

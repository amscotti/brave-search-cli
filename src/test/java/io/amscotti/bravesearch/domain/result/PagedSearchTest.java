package io.amscotti.bravesearch.domain.result;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The aggregate of one completed multi-request pagination run and its page facts. */
final class PagedSearchTest {

    @Test
    void receivedPagesCountTheCompletedPagesAndTheSnapshotComesFromTheMostRecentOne() {
        RateLimitSnapshot latest = new RateLimitSnapshot(
                List.of(new io.amscotti.bravesearch.domain.metadata.RateLimitWindow("request", 1, 0, Duration.ofSeconds(3))),
                List.of(),
                java.time.Instant.EPOCH);
        PagedSearch<WebSearchResult> search = new PagedSearch<>(
                10,
                List.of(
                        new PagedSearch.Page<>(0, 1, null, resultOf("{}", RateLimitSnapshot.empty())),
                        new PagedSearch.Page<>(1, 2, Duration.ofSeconds(3), resultOf("{}", latest))));

        assertEquals(10, search.requestedPages());
        assertEquals(2, search.receivedPages(), "received pages are the completed pages");
        assertEquals(latest, search.latestRateLimits(), "the exposed snapshot is the most recent page's");
        assertEquals(Duration.ofSeconds(3), search.pages().getLast().waited(), "a page may carry the pacing wait that preceded it");
    }

    @Test
    void aPagedSearchWithoutCompletedPagesOrWithAnUnusableBudgetIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PagedSearch<>(3, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new PagedSearch<>(0, List.of(firstPage())));
        assertThrows(IllegalArgumentException.class, () -> new PagedSearch<>(11, List.of(firstPage())));
    }

    @Test
    void aPageCarriesItsZeroBasedRequestIndexAndUserFacingPageNumber() {
        PagedSearch.Page<WebSearchResult> page = new PagedSearch.Page<>(2, 3, null, resultOf("{}", RateLimitSnapshot.empty()));

        assertEquals(2, page.requestIndex());
        assertEquals(3, page.page());
        assertEquals(null, page.waited());
        assertThrows(IllegalArgumentException.class, () -> new PagedSearch.Page<>(1, 1, null, resultOf("{}", RateLimitSnapshot.empty())));
        assertThrows(IllegalArgumentException.class, () -> new PagedSearch.Page<>(0, 0, null, resultOf("{}", RateLimitSnapshot.empty())));
        assertThrows(IllegalArgumentException.class, () -> new PagedSearch.Page<>(0, 11, null, resultOf("{}", RateLimitSnapshot.empty())));
    }

    private static PagedSearch.Page firstPage() {
        return new PagedSearch.Page<>(0, 1, null, resultOf("{}", RateLimitSnapshot.empty()));
    }

    private static WebSearchResult resultOf(String body, RateLimitSnapshot snapshot) {
        return new WebSearchResult(200, new UpstreamPayload(body.getBytes(UTF_8)), snapshot, null, null, null);
    }
}

package io.amscotti.bravesearch.domain.result;

import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * The aggregate of one completed multi-request pagination run: the page budget the run was
 * prepared to request and the pages that completed, in request order — written once against
 * {@link PagedExchange}, so every paginated endpoint's result record plugs in unchanged.
 *
 * <p>{@link #requestedPages()} is the budget — the {@code --max-pages} bound — not a
 * prediction: a run that stops early because the continuation rule turned false still
 * reports the full budget it was given, so {@code requested} and {@code received} together
 * state exactly how much of the budget the upstream made usable. The most recent page's
 * rate-limit snapshot is the one a pacing decision reads next and the one the machine
 * documents expose.
 *
 * @param <R> the endpoint's completed-exchange type every page carries
 */
public record PagedSearch<R extends PagedExchange>(int requestedPages, List<Page<R>> pages) {

    /** The largest user-facing page the paginated search endpoints document (offset 0..9). */
    public static final int MAX_PAGE = 10;

    public PagedSearch {
        if (requestedPages < 1 || requestedPages > MAX_PAGE) {
            throw new IllegalArgumentException("requestedPages must be between 1 and " + MAX_PAGE);
        }
        pages = List.copyOf(pages);
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("a paged search carries at least the first completed page");
        }
        for (int index = 0; index < pages.size(); index++) {
            Page<R> page = pages.get(index);
            if (page.requestIndex() != index || page.page() != index + 1) {
                throw new IllegalArgumentException("pages must be the consecutive requests from page one in order");
            }
        }
    }

    /** The number of completed pages, which is also the run's completed request count. */
    public int receivedPages() {
        return pages.size();
    }

    /** The rate-limit snapshot of the most recent completed page. */
    public RateLimitSnapshot latestRateLimits() {
        return pages.getLast().result().rateLimits();
    }

    /**
     * One completed page: its zero-based request index inside the invocation, the
     * user-facing page number it asked for, the pacing wait performed before it ({@code null}
     * when no exhausted rate window paced this request), and the completed exchange.
     */
    public record Page<R extends PagedExchange>(int requestIndex, int page, Duration waited, R result) {

        public Page {
            if (requestIndex < 0) {
                throw new IllegalArgumentException("requestIndex must not be negative: " + requestIndex);
            }
            if (page < 1 || page > MAX_PAGE) {
                throw new IllegalArgumentException("page must be between 1 and " + MAX_PAGE);
            }
            if (requestIndex != page - 1) {
                throw new IllegalArgumentException("the request index is the page number minus one: page " + page);
            }
            Objects.requireNonNull(result, "result");
        }
    }
}

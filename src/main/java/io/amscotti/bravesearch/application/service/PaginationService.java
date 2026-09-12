package io.amscotti.bravesearch.application.service;

import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import io.amscotti.bravesearch.domain.metadata.RateLimitWindow;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.result.PagedExchange;
import io.amscotti.bravesearch.domain.result.PagedSearch;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The sequential pagination orchestrator of one multi-request invocation, written once over
 * the endpoint-agnostic {@link PagedExchange} so every paginated endpoint reuses the same
 * walk.
 *
 * <p>Every request is strictly sequential — page N+1 is never started before page N
 * completed and was observed — because parallel pages would burst a user's quota. The
 * continuation rule is the endpoint's own and arrives through the injected {@link
 * ContinuationProbe}: web continues only while the body's {@code query.more_results_available}
 * reads true, so an absent or unreadable flag stops the run after its page, and a short page
 * is never read as exhaustion; an endpoint that documents no continuation field injects an
 * always-continue probe, making the requested page budget the walk's sole terminator. The
 * requested page budget — the {@code --max-pages} bound within the documented user-facing
 * page range — always caps the run.
 *
 * <p>Pacing: before each subsequent request the latest rate-limit snapshot is inspected; a
 * window with remaining {@code 0} (and a nonzero limit — a documented limit of {@code 0}
 * means unlimited) paces the next request behind a cancellable wait that runs until the
 * latest such window's reset instant — the instant the headers were observed plus the
 * window's reset, because the upstream reset counts from the observation, never from the
 * wait — so the wait spans only the reset still outstanding when it starts. The wait the
 * waiter performed is recorded on the paced page so the output surfaces can expose it, and
 * an interrupted or cancelled wait ends the run as the transport failure with the completed
 * pages preserved; the latch is observed once more after the wait returns, so a
 * cancellation inside its final slice still stops the walk before the paced request.
 *
 * <p>The first failing page aborts the run: its failure travels verbatim — kind, diagnostic,
 * upstream detail, observed windows, and status — beside the completed pages and the budget,
 * so every output mode can report requested and received pages. The observer seam carries
 * each completed page to a streaming output before the next request starts; buffered modes
 * pass a no-op.
 *
 * @param <R> the endpoint's completed-exchange type every page carries
 */
public final class PaginationService<R extends PagedExchange> {

    /** One page exchange of the invocation, by the user-facing page number. */
    @FunctionalInterface
    public interface PageFetcher<R> {

        /** Performs the exchange of {@code page}; never called for two pages at once. */
        Outcome<R> fetch(int page);
    }

    /**
     * The endpoint's continuation rule over one completed body: true means another page may
     * follow, everything else — false, absent, unreadable — stops after this page.
     */
    @FunctionalInterface
    public interface ContinuationProbe {

        /**
         * The always-continue rule of an endpoint that documents no continuation field: the
         * requested page budget is the walk's sole terminator, and a short page is never
         * read as exhaustion.
         */
        static ContinuationProbe alwaysContinue() {
            return body -> true;
        }

        /** Whether the body of a completed page allows requesting the next one. */
        boolean moreResultsAvailable(UpstreamPayload body);
    }

    /**
     * The cancellable pacing wait. Implementations block close to {@code resetAt} — the
     * absolute instant the paced window resets, anchored at the observation of its headers —
     * waiting out only the part of the reset still outstanding when the wait starts, and
     * must abort early with {@link InterruptedException} when the run is cancelled; a
     * genuine interrupt propagates the same way, with the thread's interrupt status left set
     * by the waiter, while a cancellation abort must not set it — the run's failure carries
     * the cancellation, and a phantom interrupt on the calling thread would outlive the run.
     * The wait performed is the return value, so the orchestrator records what the run
     * really waited, never the reset re-anchored at the wait.
     */
    @FunctionalInterface
    public interface RateWaiter {

        /**
         * Waits out the time until {@code resetAt}, honoring {@code cancellation}.
         *
         * @return the wait performed: the reset still outstanding at wait start, never
         *     negative — a reset the preceding page's body read already outlived waits
         *     nothing
         */
        Duration await(Instant resetAt, CancellationContext cancellation) throws InterruptedException;
    }

    /**
     * The terminal state of one pagination run: either the completed pages of a success or
     * the verbatim failure of the first failed page beside the pages completed before it.
     * {@code requestedPages} is always the budget the run was given.
     */
    public record PagedRun<R extends PagedExchange>(
            int requestedPages, List<PagedSearch.Page<R>> completedPages, Outcome.Failure failure) {

        public PagedRun {
            if (requestedPages < 1 || requestedPages > PagedSearch.MAX_PAGE) {
                throw new IllegalArgumentException("requestedPages must be between 1 and " + PagedSearch.MAX_PAGE);
            }
            completedPages = List.copyOf(completedPages);
            if (failure == null && completedPages.isEmpty()) {
                throw new IllegalArgumentException("a successful pagination run completed at least its first page");
            }
        }

        /** Whether every requested page either completed or the run stopped by its rules. */
        public boolean succeeded() {
            return failure == null;
        }

        /** The completed-page count, which is also the completed-request count. */
        public int receivedPages() {
            return completedPages.size();
        }

        /** The aggregate of the completed pages; only valid on a succeeded run. */
        public PagedSearch<R> search() {
            if (failure != null) {
                throw new IllegalStateException("a failed pagination run has no search aggregate");
            }
            return new PagedSearch<>(requestedPages, completedPages);
        }
    }

    private final ContinuationProbe continuation;
    private final RateWaiter waiter;

    public PaginationService(ContinuationProbe continuation, RateWaiter waiter) {
        this.continuation = Objects.requireNonNull(continuation, "continuation");
        this.waiter = Objects.requireNonNull(waiter, "waiter");
    }

    /**
     * Walks the pages of one invocation sequentially.
     *
     * @param fetcher the page exchange seam, called with ascending user-facing page numbers
     * @param requestedPages the page budget, one through the documented maximum page
     * @param cancellation the run's cancellation latch; latched before a subsequent request
     *     it stops the run as the transport failure
     * @param observer receives each completed page strictly before the next request starts
     */
    public PagedRun<R> fetchAll(
            PageFetcher<R> fetcher,
            int requestedPages,
            CancellationContext cancellation,
            Consumer<PagedSearch.Page<R>> observer) {
        Objects.requireNonNull(fetcher, "fetcher");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(observer, "observer");
        if (requestedPages < 1 || requestedPages > PagedSearch.MAX_PAGE) {
            throw new IllegalArgumentException("requestedPages must be between 1 and " + PagedSearch.MAX_PAGE);
        }
        List<PagedSearch.Page<R>> pages = new ArrayList<>(requestedPages);
        for (int page = 1; page <= requestedPages; page++) {
            Duration pacingWait = null;
            if (page > 1) {
                if (cancellation.cancelled()) {
                    return stopped(pages, requestedPages, "pagination was cancelled before page " + page);
                }
                Instant pacingResetAt = latestExhaustedResetAtOf(pages.getLast());
                if (pacingResetAt != null) {
                    try {
                        pacingWait = waiter.await(pacingResetAt, cancellation);
                    } catch (InterruptedException interrupted) {
                        // the thread's interrupt status belongs to the waiter: a genuine
                        // interrupt propagates with the flag already restored by it, while a
                        // cancellation abort carries no interrupt at all — re-asserting here
                        // would plant a phantom interrupt on the caller's thread
                        return stopped(
                                pages,
                                requestedPages,
                                "pagination was interrupted while waiting for the rate-limit reset before page " + page);
                    }
                    if (cancellation.cancelled()) {
                        // the latch may fire inside the wait's final slice, after the
                        // waiter's own last check, so the walk re-observes it before the
                        // paced request is dispatched
                        return stopped(pages, requestedPages, "pagination was cancelled before page " + page);
                    }
                }
            }
            Outcome<R> outcome = fetcher.fetch(page);
            if (outcome instanceof Outcome.Failure<R> failed) {
                return new PagedRun<>(requestedPages, pages, failed);
            }
            R result = succeeded(outcome);
            PagedSearch.Page<R> completed = new PagedSearch.Page<>(pages.size(), page, pacingWait, result);
            pages.add(completed);
            observer.accept(completed);
            if (!continuation.moreResultsAvailable(result.body())) {
                break;
            }
        }
        return new PagedRun<>(requestedPages, pages, null);
    }

    private static <R> R succeeded(Outcome<R> outcome) {
        return ((Outcome.Success<R>) outcome).value();
    }

    /**
     * The pacing deadline the latest snapshot demands, or {@code null} when no applicable
     * window is exhausted: the latest reset instant among windows with remaining {@code 0}
     * whose limit is nonzero (a documented limit of {@code 0} is an unlimited window),
     * derived per window against the snapshot's observation instant because the upstream
     * reset counts from the observation of the headers, never from the wait that honors it.
     */
    private static Instant latestExhaustedResetAtOf(PagedSearch.Page<? extends PagedExchange> latest) {
        RateLimitSnapshot snapshot = latest.result().rateLimits();
        Instant latestResetAt = null;
        for (RateLimitWindow window : snapshot.windows()) {
            if (window.limit() != 0 && window.remaining() == 0) {
                Instant resetAt = snapshot.resetAtOf(window);
                if (latestResetAt == null || resetAt.isAfter(latestResetAt)) {
                    latestResetAt = resetAt;
                }
            }
        }
        return latestResetAt;
    }

    private static <R extends PagedExchange> PagedRun<R> stopped(
            List<PagedSearch.Page<R>> pages, int requestedPages, String diagnostic) {
        return new PagedRun<>(requestedPages, pages, new Outcome.Failure<>(FailureKind.TRANSPORT, diagnostic));
    }
}

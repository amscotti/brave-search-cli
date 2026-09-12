package io.amscotti.bravesearch.application.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import io.amscotti.bravesearch.domain.metadata.RateLimitWindow;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.result.PagedSearch;
import io.amscotti.bravesearch.domain.result.WebSearchResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The sequential pagination orchestrator: pages are fetched strictly one after another in
 * page order, continuation follows the injected web probe bounded by the requested page
 * budget, an exhausted rate window paces the next request with a cancellable wait, and the
 * first failing page aborts the run while the completed pages stay observable.
 */
final class PaginationServiceTest {

    @Test
    void stopsAfterThePageThatReportsNoMoreResults() {
        RecordingFetcher fetcher = new RecordingFetcher();
        fetcher.respond(1, successWithBody("{\"query\":{\"more_results_available\":true}}"));
        fetcher.respond(2, successWithBody("{\"query\":{\"more_results_available\":false}}"));
        fetcher.respond(3, successWithBody("{\"query\":{\"more_results_available\":true}}"));

        PaginationService.PagedRun<WebSearchResult> run = webDrivenService().fetchAll(fetcher, 3, neverCancelled(), ignored -> {});

        assertTrue(run.succeeded(), "the run completes when the continuation flag turns false");
        assertEquals(List.of(1, 2), fetcher.requestedPages(), "the page after the false flag is never requested");
        assertEquals(3, run.requestedPages());
        assertEquals(2, run.receivedPages());
        assertEquals(2, run.search().pages().size());
    }

    @Test
    void anAbsentContinuationFlagStopsAfterItsPage() {
        RecordingFetcher fetcher = new RecordingFetcher();
        fetcher.respond(1, successWithBody("{\"web\":{\"results\":[]}}"));

        PaginationService.PagedRun<WebSearchResult> run = webDrivenService().fetchAll(fetcher, 3, neverCancelled(), ignored -> {});

        assertEquals(List.of(1), fetcher.requestedPages(), "no flag means no continuation");
        assertEquals(1, run.receivedPages());
    }

    @Test
    void thePageBudgetBoundsTheRunEvenWhileMoreResultsStayAvailable() {
        RecordingFetcher fetcher = new RecordingFetcher();
        for (int page = 1; page <= 10; page++) {
            fetcher.respond(page, successWithBody("{\"query\":{\"more_results_available\":true}}"));
        }

        PaginationService.PagedRun<WebSearchResult> run = webDrivenService().fetchAll(fetcher, 2, neverCancelled(), ignored -> {});

        assertEquals(List.of(1, 2), fetcher.requestedPages(), "the budget stops the run, not the flag");
        assertEquals(2, run.receivedPages());
        assertEquals(2, run.requestedPages());
    }

    @Test
    void aShortPageIsNeverReadAsExhaustion() {
        RecordingFetcher fetcher = new RecordingFetcher();
        fetcher.respond(1, successWithBody("{\"web\":{\"results\":[]},\"query\":{\"more_results_available\":true}}"));
        fetcher.respond(2, successWithBody("{\"web\":{\"results\":[]},\"query\":{\"more_results_available\":false}}"));

        PaginationService.PagedRun<WebSearchResult> run = webDrivenService().fetchAll(fetcher, 5, neverCancelled(), ignored -> {});

        assertEquals(List.of(1, 2), fetcher.requestedPages(), "an empty page still continues while the flag says so");
        assertEquals(2, run.receivedPages());
    }

    @Test
    void boundsOutsideTheDocumentedPageRangeAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> webDrivenService().fetchAll(new RecordingFetcher(), 0, neverCancelled(), ignored -> {}));
        assertThrows(
                IllegalArgumentException.class,
                () -> webDrivenService().fetchAll(new RecordingFetcher(), 11, neverCancelled(), ignored -> {}));
    }

    @Test
    void pagedRunRejectsBudgetsOutsideTheDocumentedPageRange() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PaginationService.PagedRun<WebSearchResult>(0, List.of(), null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PaginationService.PagedRun<WebSearchResult>(11, List.of(), null));
    }

    @Test
    void pagedRunRejectsAnEmptySuccessfulRun() {
        assertThrows(
                IllegalArgumentException.class, () -> new PaginationService.PagedRun<WebSearchResult>(2, List.of(), null));
    }

    @Test
    void searchAggregateIsUnavailableOnAFailedRun() {
        RecordingFetcher fetcher = new RecordingFetcher();
        fetcher.respond(
                1, new Outcome.Failure<>(FailureKind.AUTHENTICATION, "upstream exchange failed with status 401"));

        PaginationService.PagedRun<WebSearchResult> run = webDrivenService().fetchAll(fetcher, 3, neverCancelled(), ignored -> {});

        assertFalse(run.succeeded());
        assertThrows(IllegalStateException.class, run::search);
    }

    @Test
    void everyRequestIsStrictlySequentialInPageOrder() {
        RecordingFetcher fetcher = new RecordingFetcher();
        for (int page = 1; page <= 3; page++) {
            fetcher.respond(
                    page, successWithBody("{\"query\":{\"more_results_available\":" + (page < 3) + "}}"));
        }
        AtomicInteger liveRequests = new AtomicInteger();

        PaginationService.PagedRun<WebSearchResult> run = webDrivenService()
                .fetchAll(
                        page -> {
                            assertTrue(
                                    liveRequests.compareAndSet(0, 1),
                                    "a second request entered while the first was still running");
                            try {
                                return fetcher.fetch(page);
                            } finally {
                                liveRequests.set(0);
                            }
                        },
                        3,
                        neverCancelled(),
                        ignored -> {});

        assertEquals(List.of(1, 2, 3), fetcher.requestedPages(), "pages are requested in strictly ascending order");
        assertEquals(3, run.receivedPages());
    }

    @Test
    void eachCompletedPageCarriesItsRequestIndexPageNumberAndBody() {
        RecordingFetcher fetcher = new RecordingFetcher();
        fetcher.respond(1, successWithBody("{\"query\":{\"more_results_available\":true}}"));
        fetcher.respond(2, successWithBody("{\"query\":{\"more_results_available\":false}}"));

        PaginationService.PagedRun<WebSearchResult> run = webDrivenService().fetchAll(fetcher, 4, neverCancelled(), ignored -> {});

        List<PagedSearch.Page<WebSearchResult>> pages = run.search().pages();
        assertEquals(0, pages.getFirst().requestIndex());
        assertEquals(1, pages.getFirst().page());
        assertEquals(1, pages.getLast().requestIndex());
        assertEquals(2, pages.getLast().page());
        assertEquals(
                "{\"query\":{\"more_results_available\":true}}",
                new String(pages.getFirst().result().body().toByteArray(), UTF_8));
        assertEquals(RateLimitSnapshot.empty(), run.search().latestRateLimits());
    }

    @Test
    void theObserverSeesEachCompletedPageBeforeTheNextRequestStarts() {
        RecordingFetcher fetcher = new RecordingFetcher();
        for (int page = 1; page <= 2; page++) {
            fetcher.respond(
                    page, successWithBody("{\"query\":{\"more_results_available\":" + (page < 2) + "}}"));
        }
        List<Integer> observed = new ArrayList<>();
        List<Integer> observedAtFetchTime = new ArrayList<>();

        webDrivenService()
                .fetchAll(
                        page -> {
                            observedAtFetchTime.add(observed.size());
                            return fetcher.fetch(page);
                        },
                        2,
                        neverCancelled(),
                        completed -> observed.add(completed.page()));

        assertEquals(List.of(1, 2), observed, "every completed page reaches the observer in order");
        assertEquals(List.of(0, 1), observedAtFetchTime, "a page is observed before the next request starts");
    }

    @Test
    void aFirstPageFailureAbortsWithZeroCompletedPages() {
        RecordingFetcher fetcher = new RecordingFetcher();
        fetcher.respond(
                1, new Outcome.Failure<>(FailureKind.AUTHENTICATION, "upstream exchange failed with status 401"));

        PaginationService.PagedRun<WebSearchResult> run = webDrivenService().fetchAll(fetcher, 3, neverCancelled(), ignored -> {});

        assertFalse(run.succeeded());
        assertEquals(FailureKind.AUTHENTICATION, run.failure().kind());
        assertEquals(0, run.receivedPages());
        assertEquals(3, run.requestedPages());
        assertEquals(List.of(1), fetcher.requestedPages());
    }

    @Test
    void aLaterPageFailurePreservesTheCompletedPagesAndTheFailedKind() {
        RateLimitSnapshot failingWindows = snapshotOf(new RateLimitWindow("request", 1, 0, Duration.ofSeconds(9)));
        RecordingFetcher fetcher = new RecordingFetcher();
        fetcher.respond(1, successWithBody("{\"query\":{\"more_results_available\":true}}"));
        fetcher.respond(
                2,
                new Outcome.Failure<WebSearchResult>(
                        FailureKind.RATE_LIMITED, "Upstream rate limit exceeded.", null, failingWindows, 429));

        PaginationService.PagedRun<WebSearchResult> run = webDrivenService().fetchAll(fetcher, 3, neverCancelled(), ignored -> {});

        assertFalse(run.succeeded());
        assertEquals(FailureKind.RATE_LIMITED, run.failure().kind(), "the failed request's kind decides the run");
        assertEquals("Upstream rate limit exceeded.", run.failure().diagnostic());
        assertSame(failingWindows, run.failure().rateLimits(), "the failing exchange's windows stay observable");
        assertEquals(429, run.failure().httpStatus());
        assertEquals(1, run.receivedPages(), "the completed page stays countable");
        assertEquals(1, run.completedPages().getFirst().page());
        assertEquals(3, run.requestedPages());
    }

    @Test
    void anExhaustedWindowPacesTheNextRequestUntilTheLatestExhaustedResetInstant() {
        Instant observedAt = Instant.ofEpochSecond(1_700_000_000);
        RateLimitSnapshot exhausted = snapshotOf(
                observedAt,
                new RateLimitWindow("request", 1, 0, Duration.ofSeconds(2)),
                new RateLimitWindow("day", 10, 4, Duration.ofHours(1)),
                new RateLimitWindow("minute", 60, 0, Duration.ofSeconds(5)));
        RateLimitSnapshot healthy = snapshotOf(new RateLimitWindow("request", 1, 1, Duration.ofSeconds(1)));
        RecordingFetcher fetcher = new RecordingFetcher();
        fetcher.respond(1, new Outcome.Success<>(resultWithBody(200, "{\"query\":{\"more_results_available\":true}}", exhausted)));
        fetcher.respond(2, new Outcome.Success<>(resultWithBody(200, "{\"query\":{\"more_results_available\":false}}", healthy)));
        // reading and processing the previous page's body consumed a second of the reset
        // after its headers were observed, so the waiter receives the absolute reset
        // instant and reports the shorter wait it actually performed
        Duration waitPerformed = Duration.ofSeconds(4);
        RecordingWaiter waiter = new RecordingWaiter(waitPerformed);

        PaginationService.PagedRun<WebSearchResult> run =
                new PaginationService<WebSearchResult>(PaginationServiceTest::webContinuation, waiter)
                        .fetchAll(fetcher, 3, neverCancelled(), ignored -> {});

        assertEquals(
                List.of(observedAt.plus(Duration.ofSeconds(5))),
                waiter.resetDeadlines,
                "the pacing wait targets the latest exhausted window's reset instant, anchored at observation");
        assertEquals(1, waiter.resetDeadlines.size(), "no wait happens once the latest window has remaining quota");
        assertEquals(
                waitPerformed, run.search().pages().getLast().waited(), "the wait performed is recorded on the paced page");
        assertNull(run.search().pages().getFirst().waited(), "the first page is never paced");
    }

    @Test
    void noWindowExhaustedMeansNoWaitAtAll() {
        RecordingFetcher fetcher = new RecordingFetcher();
        fetcher.respond(
                1,
                new Outcome.Success<>(
                        resultWithBody(200, "{\"query\":{\"more_results_available\":true}}", RateLimitSnapshot.empty())));
        fetcher.respond(
                2,
                new Outcome.Success<>(
                        resultWithBody(200, "{\"query\":{\"more_results_available\":false}}", RateLimitSnapshot.empty())));
        RecordingWaiter waiter = new RecordingWaiter();

        PaginationService.PagedRun<WebSearchResult> run = webDrivenService().fetchAll(fetcher, 3, neverCancelled(), ignored -> {});

        assertTrue(waiter.resetDeadlines.isEmpty(), "a snapshot without windows never paces");
        assertNull(run.search().pages().getLast().waited());
    }

    @Test
    void anUnlimitedWindowIsNeverReadAsExhausted() {
        RateLimitSnapshot unlimited = snapshotOf(new RateLimitWindow("request", 0, 0, Duration.ofSeconds(2)));
        RecordingFetcher fetcher = new RecordingFetcher();
        fetcher.respond(1, new Outcome.Success<>(resultWithBody(200, "{\"query\":{\"more_results_available\":true}}", unlimited)));
        fetcher.respond(2, new Outcome.Success<>(resultWithBody(200, "{\"query\":{\"more_results_available\":false}}", unlimited)));
        RecordingWaiter waiter = new RecordingWaiter();

        webDrivenService().fetchAll(fetcher, 3, neverCancelled(), ignored -> {});

        assertTrue(waiter.resetDeadlines.isEmpty(), "a limit of zero means unlimited, not exhausted");
    }

    @Test
    void anInterruptedWaitFailsAsTransportWithTheCompletedPages() {
        RecordingFetcher fetcher = new RecordingFetcher();
        fetcher.respond(
                1,
                new Outcome.Success<>(
                        resultWithBody(
                                200,
                                "{\"query\":{\"more_results_available\":true}}",
                                snapshotOf(new RateLimitWindow("request", 1, 0, Duration.ofSeconds(30))))));

        PaginationService.PagedRun<WebSearchResult> run = new PaginationService<WebSearchResult>(
                        PaginationServiceTest::webContinuation,
                        (resetAt, cancellation) -> {
                            throw new InterruptedException("interrupted");
                        })
                .fetchAll(fetcher, 2, neverCancelled(), ignored -> {});

        assertFalse(run.succeeded());
        assertEquals(FailureKind.TRANSPORT, run.failure().kind(), "an interrupted wait is the transport failure of the run");
        assertNotNull(run.failure().diagnostic());
        assertEquals(1, run.receivedPages(), "the completed page stays countable");
        assertEquals(2, run.requestedPages());
        assertEquals(List.of(1), fetcher.requestedPages(), "the paced request is never dispatched");
    }

    @Test
    void aCancellationAbortOfTheWaitLeavesNoPhantomInterruptOnTheThread() {
        RecordingFetcher fetcher = new RecordingFetcher();
        fetcher.respond(
                1,
                new Outcome.Success<>(
                        resultWithBody(
                                200,
                                "{\"query\":{\"more_results_available\":true}}",
                                snapshotOf(new RateLimitWindow("request", 1, 0, Duration.ofSeconds(30))))));

        new PaginationService<WebSearchResult>(
                        PaginationServiceTest::webContinuation,
                        (resetAt, cancellation) -> {
                            // the aborting shape a cancelled wait produces: an exception
                            // without any thread interrupt behind it
                            throw new InterruptedException("pagination wait cancelled");
                        })
                .fetchAll(fetcher, 2, neverCancelled(), ignored -> {});

        assertFalse(
                Thread.currentThread().isInterrupted(),
                "a cancelled wait must not leave a phantom interrupt flag on the calling thread");
    }

    @Test
    void aGenuineInterruptOfTheWaitKeepsTheThreadFlagForTheCaller() {
        RecordingFetcher fetcher = new RecordingFetcher();
        fetcher.respond(
                1,
                new Outcome.Success<>(
                        resultWithBody(
                                200,
                                "{\"query\":{\"more_results_available\":true}}",
                                snapshotOf(new RateLimitWindow("request", 1, 0, Duration.ofSeconds(30))))));

        try {
            new PaginationService<WebSearchResult>(
                            PaginationServiceTest::webContinuation,
                            (resetAt, cancellation) -> {
                                // the propagating shape a genuine interrupt produces: the
                                // waiter restored the flag before throwing
                                Thread.currentThread().interrupt();
                                throw new InterruptedException("interrupted");
                            })
                    .fetchAll(fetcher, 2, neverCancelled(), ignored -> {});
        } finally {
            assertTrue(
                    Thread.interrupted(),
                    "the waiter's genuine interrupt flag must survive the stopped run");
        }
    }

    @Test
    void aLatchFiredInsideThePacingWaitStopsTheRunBeforeThePacedRequest() {
        CancellationContext cancellation = new CancellationContext();
        RecordingFetcher fetcher = new RecordingFetcher();
        fetcher.respond(
                1,
                new Outcome.Success<>(
                        resultWithBody(
                                200,
                                "{\"query\":{\"more_results_available\":true}}",
                                snapshotOf(new RateLimitWindow("request", 1, 0, Duration.ofSeconds(30))))));
        fetcher.respond(2, successWithBody("{\"query\":{\"more_results_available\":false}}"));

        PaginationService.PagedRun<WebSearchResult> run = new PaginationService<WebSearchResult>(
                        PaginationServiceTest::webContinuation,
                        (resetAt, runLatch) -> {
                            // the signal latches inside the wait's final slice, after the
                            // waiter's own last check, so the waiter itself returns normally
                            cancellation.latch(CancellationContext.Cause.SIGINT);
                            return Duration.ZERO;
                        })
                .fetchAll(fetcher, 2, cancellation, ignored -> {});

        assertFalse(run.succeeded(), "a latch observed during the pacing wait fails the run");
        assertEquals(FailureKind.TRANSPORT, run.failure().kind());
        assertEquals(1, run.receivedPages(), "the completed page stays countable");
        assertEquals(
                List.of(1),
                fetcher.requestedPages(),
                "the paced request is never dispatched on a cancelled run");
    }

    @Test
    void aCancelledRunStopsBeforeTheNextRequest() {
        CancellationContext cancelled = new CancellationContext();
        cancelled.latch(CancellationContext.Cause.SIGINT);
        RecordingFetcher fetcher = new RecordingFetcher();
        fetcher.respond(1, successWithBody("{\"query\":{\"more_results_available\":true}}"));

        PaginationService.PagedRun<WebSearchResult> run = webDrivenService().fetchAll(fetcher, 2, cancelled, ignored -> {});

        assertFalse(run.succeeded());
        assertEquals(FailureKind.TRANSPORT, run.failure().kind());
        assertEquals(1, run.receivedPages());
        assertEquals(List.of(1), fetcher.requestedPages(), "no request leaves after the cancellation latched");
    }

    @Test
    void aLatchFiredDuringAnInFlightFetchCompletesThatPageAndStopsBeforeTheNext() {
        CancellationContext cancellation = new CancellationContext();
        RecordingFetcher fetcher = new RecordingFetcher();
        fetcher.respond(1, successWithBody("{\"query\":{\"more_results_available\":true}}"));
        fetcher.respond(2, successWithBody("{\"query\":{\"more_results_available\":true}}"));

        PaginationService.PagedRun<WebSearchResult> run = webDrivenService()
                .fetchAll(
                        page -> {
                            Outcome<WebSearchResult> outcome = fetcher.fetch(page);
                            if (page == 2) {
                                // the interrupt lands while the second exchange is in flight: the
                                // body read itself is not interrupted, so the page completes and
                                // the walk stops at its next cancellation point
                                cancellation.latch(CancellationContext.Cause.SIGINT);
                            }
                            return outcome;
                        },
                        3,
                        cancellation,
                        ignored -> {});

        assertFalse(run.succeeded(), "the latched walk ends as a failure, not a success");
        assertEquals(FailureKind.TRANSPORT, run.failure().kind());
        assertEquals(2, run.receivedPages(), "the in-flight page completes and stays countable");
        assertEquals(3, run.requestedPages());
        assertEquals(
                List.of(1, 2),
                fetcher.requestedPages(),
                "the cancellation point before page three stops the walk honestly");
    }

    private static PaginationService<WebSearchResult> webDrivenService() {
        return new PaginationService<>(PaginationServiceTest::webContinuation, (resetAt, cancellation) -> Duration.ZERO);
    }

    /** The web continuation rule as the orchestrator's tests read it from the body bytes. */
    private static boolean webContinuation(UpstreamPayload body) {
        return new String(body.toByteArray(), UTF_8).contains("\"more_results_available\":true");
    }

    private static CancellationContext neverCancelled() {
        return new CancellationContext();
    }

    private static RateLimitSnapshot snapshotOf(RateLimitWindow... windows) {
        return snapshotOf(Instant.EPOCH, windows);
    }

    private static RateLimitSnapshot snapshotOf(Instant observedAt, RateLimitWindow... windows) {
        return new RateLimitSnapshot(List.of(windows), List.of(), observedAt);
    }

    private static Outcome.Success<WebSearchResult> successWithBody(String body) {
        return new Outcome.Success<>(resultWithBody(200, body, RateLimitSnapshot.empty()));
    }

    private static WebSearchResult resultWithBody(int status, String body, RateLimitSnapshot rateLimits) {
        return new WebSearchResult(status, new UpstreamPayload(body.getBytes(UTF_8)), rateLimits, null, null, null);
    }

    private static final class RecordingFetcher implements PaginationService.PageFetcher<WebSearchResult> {

        private final Map<Integer, Outcome<WebSearchResult>> responses = new HashMap<>();
        private final List<Integer> requestedPages = new ArrayList<>();

        void respond(int page, Outcome<WebSearchResult> outcome) {
            responses.put(page, outcome);
        }

        List<Integer> requestedPages() {
            return List.copyOf(requestedPages);
        }

        @Override
        public Outcome<WebSearchResult> fetch(int page) {
            requestedPages.add(page);
            Outcome<WebSearchResult> outcome = responses.get(page);
            if (outcome == null) {
                throw new AssertionError("unexpected page request: " + page);
            }
            return outcome;
        }
    }

    private static final class RecordingWaiter implements PaginationService.RateWaiter {

        private final Duration waitPerformed;
        private final List<Instant> resetDeadlines = new ArrayList<>();

        RecordingWaiter() {
            this(Duration.ZERO);
        }

        RecordingWaiter(Duration waitPerformed) {
            this.waitPerformed = waitPerformed;
        }

        @Override
        public Duration await(Instant resetAt, CancellationContext cancellation) {
            resetDeadlines.add(resetAt);
            return waitPerformed;
        }
    }
}

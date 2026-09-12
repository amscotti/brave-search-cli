package io.amscotti.bravesearch.adapter.cli.presentation;

import io.amscotti.bravesearch.adapter.cli.exit.ExitCodeMapper;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.application.service.PaginationService;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.error.UnreadableBodyException;
import io.amscotti.bravesearch.domain.metadata.RateLimitWindow;
import io.amscotti.bravesearch.domain.metadata.RequestMeta;
import io.amscotti.bravesearch.domain.output.OutputMode;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.output.WalkCounts;
import io.amscotti.bravesearch.domain.result.PagedExchange;
import io.amscotti.bravesearch.domain.result.PagedSearch;
import io.amscotti.bravesearch.domain.result.Projection;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The shared renderer skeleton of every sequential multi-request walk — the page walk of
 * {@code --all-pages} and the chunk fan-out of the place enrichment commands — so one
 * walk's terminal render is written once: the per-mode success documents, the whole
 * partial-failure matrix with the counted machine framings, the unreadable-body
 * verdict, warnings routing, and the write-failure contract.
 *
 * <p>The walk's own counting vocabulary arrives through {@link WalkCounts}: the machine
 * field names of the request counts ({@code requested_pages}/{@code received_pages} of
 * the pagination family, {@code requested_requests}/{@code received_requests} of the
 * chunk fan-out) and the noun phrase of the counted human diagnostic, so every failure
 * document states exactly how much of the walk completed in the family's own words.
 *
 * <p>Success renders per mode through the aggregate hooks: exactly one JSON envelope
 * whose {@code data.upstream} is the ordered per-request entry array and whose
 * projection carries the walk's aggregate, the JSONL terminal summary after the
 * streamed per-request records a family observer emitted while the walk ran, or the
 * human listing. The aggregate itself — deduplicated pages or order-reconstructed
 * input positions — is the family's own and stays with the family's base; this
 * skeleton recomputes nothing about it.
 *
 * <p>Failure renders the mode's own failure document with the failed request's exit
 * status and the walk's request counts: human and raw diagnose once on stderr, json
 * writes exactly one {@code ok:false} envelope whose {@code error.details} carries the
 * counts, and jsonl ends the already-streamed record sequence with the counted error
 * record. A request body that is not readable JSON renders as the malformed failure of
 * the mode in every parsing mode, with the same counts. Renderers of the Answers
 * streaming family are expected to reuse this skeleton wherever their channel buffers
 * the walk's documents, so the failure matrix and the counted framings stay written
 * once.
 *
 * @param <TReq> the command's parsed request type
 * @param <TRes> the endpoint's completed-exchange type every walked request carries
 */
public abstract class WalkPresenterBase<TReq, TRes extends PagedExchange> {

    private static final String UNREADABLE_BODY_DIAGNOSTIC = "upstream success body is not readable JSON";

    private final EnvelopeCodec envelopes;
    private final JsonlCodec jsonl;
    private final SearchPresenterBase.WarningsRouter warnings;
    private final WalkCounts counts;

    protected WalkPresenterBase(
            EnvelopeCodec envelopes, JsonlCodec jsonl, SearchPresenterBase.WarningsRouter warnings, WalkCounts counts) {
        this.envelopes = Objects.requireNonNull(envelopes, "envelopes");
        this.jsonl = Objects.requireNonNull(jsonl, "jsonl");
        this.warnings = Objects.requireNonNull(warnings, "warnings");
        this.counts = Objects.requireNonNull(counts, "counts");
    }

    /** The canonical command name: the exact dispatch and wire value echoed by every document. */
    protected abstract String command();

    /** The walk's JSON projection: the family's aggregate of the completed requests. */
    protected abstract Projection aggregateProjection(TReq request, PagedSearch<TRes> search);

    /** The walk's JSONL terminal summary field set after the streamed per-request records. */
    protected abstract List<Projection.Field> summaryFields(TReq request, PagedSearch<TRes> search);

    /**
     * The walk's human listing of the family's aggregate. The invocation's output state
     * arrives with it, because the heading's ANSI styling is the output state's own color
     * decision.
     */
    protected abstract byte[] humanAggregate(TReq request, PagedSearch<TRes> search, OutputRequest output);

    /**
     * The shared terminal render of one finished walk: the success document of the
     * active mode, the mode's own failure document with the counted framings, or the
     * malformed verdict of an unreadable body — every write failure keeps the
     * presenter's own contract.
     */
    protected final int render(
            PaginationService.PagedRun<TRes> run,
            TReq request,
            OutputRequest output,
            ResultWriter results,
            DiagnosticsSink diagnostics) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(request, "request");
        try {
            return run.succeeded()
                    ? presentSuccess(run, request, output, results, diagnostics)
                    : presentFailure(run, output, results, diagnostics);
        } catch (UnreadableBodyException unreadable) {
            return presentUnreadableBody(run, output, results, diagnostics);
        } catch (UncheckedIOException writeFailure) {
            return exitForWriteFailure(writeFailure, diagnostics);
        }
    }

    /** Opens the per-invocation mode-aware warnings channel; composition supplies the adapter. */
    protected final SearchPresenterBase.WarningsRouter warnings() {
        return warnings;
    }

    /** The JSONL codec of the family observer's streamed records. */
    protected final JsonlCodec jsonl() {
        return jsonl;
    }

    private int presentSuccess(
            PaginationService.PagedRun<TRes> run,
            TReq request,
            OutputRequest output,
            ResultWriter results,
            DiagnosticsSink diagnostics) {
        PagedSearch<TRes> search = run.search();
        return switch (output.mode()) {
            case JSON -> {
                ModeAwareWarnings channel = warnings().open(output.mode(), diagnostics, results, output.quiet());
                search.pages().forEach(page -> notesOf(page.result()).forEach(channel::warning));
                results.write(
                        envelopes.encodeSuccess(
                                command(),
                                aggregateProjection(request, search),
                                search.pages(),
                                requestMetaOf(search.pages().getLast().result()),
                                channel.collected(),
                                output.pretty()));
                yield ExitCodeMapper.SUCCESS;
            }
            case JSONL -> {
                results.write(jsonl.encodeSummary(command(), summaryFields(request, search)));
                yield ExitCodeMapper.SUCCESS;
            }
            case HUMAN, RAW -> {
                ModeAwareWarnings channel = warnings().open(output.mode(), diagnostics, results, output.quiet());
                search.pages().forEach(page -> notesOf(page.result()).forEach(channel::warning));
                results.write(humanAggregate(request, search, output));
                yield ExitCodeMapper.SUCCESS;
            }
        };
    }

    private int presentFailure(
            PaginationService.PagedRun<TRes> run,
            OutputRequest output,
            ResultWriter results,
            DiagnosticsSink diagnostics) {
        Outcome.Failure failure = run.failure();
        boolean retryable = RetryableKinds.isRetryable(failure.kind());
        String upstreamCode = failure.upstream() == null ? null : failure.upstream().code();
        RequestMeta meta = new RequestMeta(null, failure.httpStatus(), null, windowsOf(failure), null);
        String countedDiagnostic = counted(failure.diagnostic(), run);
        // jsonl owns its failure explanation: the error record on stdout is the whole story
        if (output.mode() != OutputMode.JSONL) {
            diagnostics.emit(countedDiagnostic);
        }
        switch (output.mode()) {
            case JSON -> results.write(
                    envelopes.encodeFailure(
                            command(),
                            failure.kind(),
                            failure.diagnostic(),
                            retryable,
                            upstreamCode,
                            meta,
                            countFields(run),
                            output.pretty()));
            case JSONL -> results.write(
                    jsonl.encodeError(command(), failure.kind(), failure.diagnostic(), retryable, countFields(run), windowsOf(failure)));
            case HUMAN, RAW -> {
                // the diagnostic line above is the whole human failure document
            }
        }
        return ExitCodeMapper.forKind(failure.kind());
    }

    private int presentUnreadableBody(
            PaginationService.PagedRun<TRes> run,
            OutputRequest output,
            ResultWriter results,
            DiagnosticsSink diagnostics) {
        boolean retryable = RetryableKinds.isRetryable(FailureKind.MALFORMED);
        String countedDiagnostic = counted(UNREADABLE_BODY_DIAGNOSTIC, run);
        if (output.mode() != OutputMode.JSONL) {
            diagnostics.emit(countedDiagnostic);
        }
        switch (output.mode()) {
            case JSON -> results.write(
                    envelopes.encodeFailure(
                            command(),
                            FailureKind.MALFORMED,
                            UNREADABLE_BODY_DIAGNOSTIC,
                            retryable,
                            null,
                            malformedMetaOf(run),
                            countFields(run),
                            output.pretty()));
            case JSONL -> results.write(
                    jsonl.encodeError(
                            command(),
                            FailureKind.MALFORMED,
                            UNREADABLE_BODY_DIAGNOSTIC,
                            retryable,
                            countFields(run),
                            windowsOfUnreadable(run)));
            case HUMAN, RAW -> {
                // human needs no stdout document
            }
        }
        return ExitCodeMapper.forKind(FailureKind.MALFORMED);
    }

    /** The JSONL error-record and envelope-details counts of one stopped walk. */
    private List<Projection.Field> countFields(PaginationService.PagedRun<TRes> run) {
        return List.of(
                new Projection.Field(counts.requestedField(), decimal(run.requestedPages())),
                new Projection.Field(counts.receivedField(), decimal(run.receivedPages())));
    }

    private String counted(String diagnostic, PaginationService.PagedRun<TRes> run) {
        return command() + ": " + diagnostic + " (" + run.receivedPages() + " of " + run.requestedPages() + " "
                + counts.noun() + " completed)";
    }

    /** The advisory notes of one completed exchange in routing order: rate limits first, then usage. */
    protected static <TRes extends PagedExchange> List<String> notesOf(TRes result) {
        List<String> notes = new ArrayList<>(result.rateLimits().notes());
        if (result.usage() != null) {
            notes.addAll(result.usage().notes());
        }
        return notes;
    }

    private int exitForWriteFailure(UncheckedIOException failure, DiagnosticsSink diagnostics) {
        return ResultWriteFailure.exitFor(failure, diagnostics, command());
    }

    protected static Projection.Decimal decimal(long value) {
        return new Projection.Decimal(BigDecimal.valueOf(value));
    }

    private static <TRes extends PagedExchange> RequestMeta requestMetaOf(TRes result) {
        return new RequestMeta(
                result.requestId(), result.httpStatus(), result.apiVersion(), result.rateLimits().windows(), result.usage());
    }

    private static <TRes extends PagedExchange> RequestMeta malformedMetaOf(PaginationService.PagedRun<TRes> run) {
        if (run.completedPages().isEmpty()) {
            return new RequestMeta(null, 0, null, List.of(), null);
        }
        TRes latest = run.completedPages().getLast().result();
        return new RequestMeta(null, latest.httpStatus(), null, latest.rateLimits().windows(), null);
    }

    /** The failing exchange's windows when it observed a snapshot; {@code null} keeps the error record window-free. */
    private static List<RateLimitWindow> windowsOf(Outcome.Failure failure) {
        return failure.rateLimits() == null ? null : failure.rateLimits().windows();
    }

    /**
     * The latest completed request's windows of an unreadable-body walk — no page completed
     * means no snapshot existed, so the error record carries none.
     */
    private static <TRes extends PagedExchange> List<RateLimitWindow> windowsOfUnreadable(
            PaginationService.PagedRun<TRes> run) {
        return run.completedPages().isEmpty() ? null : run.completedPages().getLast().result().rateLimits().windows();
    }
}

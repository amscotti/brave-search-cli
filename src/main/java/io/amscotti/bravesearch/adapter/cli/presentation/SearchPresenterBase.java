package io.amscotti.bravesearch.adapter.cli.presentation;

import io.amscotti.bravesearch.adapter.cli.exit.ExitCodeMapper;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.RawCodec;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.error.UnreadableBodyException;
import io.amscotti.bravesearch.domain.metadata.RateLimitWindow;
import io.amscotti.bravesearch.domain.metadata.RequestMeta;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.output.OutputMode;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.result.PagedExchange;
import io.amscotti.bravesearch.domain.result.Projection;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The shared renderer skeleton of every search command: one completed exchange becomes the
 * output the selected channel owns, with the whole failure matrix, warnings routing, raw
 * passthrough, and the machine framings implemented once.
 *
 * <p>Success renders per mode — the command's human listing, exactly one JSON envelope
 * whose projection carries the command's stable fields and whose {@code data.upstream}
 * stays the lossless tree, one JSONL {@code result} record per logical result in
 * presentation order followed by exactly one {@code summary} record, or the raw decoded
 * body bytes with nothing added. Exchange notes route through the mode-aware warnings
 * channel before any document, so each mode keeps the channel it owns.
 *
 * <p>Failures render the mode's own failure document with the failure kind's exit status:
 * human and raw diagnose on stderr (raw also emits a bounded upstream error body byte-exact
 * when one exists), json writes exactly one {@code ok:false} envelope on stdout plus the
 * human diagnostic on stderr, and jsonl ends the stream with one {@code error} record. A
 * 2xx body that is not one readable JSON document renders as the malformed failure in every
 * parsing mode — the exchange promised JSON and did not deliver it — while raw never parses
 * what it passes through.
 *
 * <p>Write failures keep the presenter's own contract: a write failure whose cause chain
 * reports the operating system's broken pipe is the documented silent early termination,
 * and every other write failure keeps the transport status with one diagnostic line. The
 * command-specific shapes — projection, records, summary, human text — arrive through the
 * abstract seams; the exchange-meta seams (body, identifiers, windows, usage, notes) are
 * written once here over the completed-exchange view {@link PagedExchange} every command's
 * result record already carries, so no specialization repeats them.
 *
 * @param <TReq> the command's parsed request type
 * @param <TRes> the command's exchange result type
 */
public abstract class SearchPresenterBase<TReq, TRes extends PagedExchange> implements SearchPresenter<TReq, TRes> {

    /** Opens the per-invocation mode-aware warnings channel; composition supplies the adapter. */
    @FunctionalInterface
    public interface WarningsRouter {

        ModeAwareWarnings open(OutputMode mode, DiagnosticsSink diagnostics, ResultWriter results, boolean quiet);
    }

    private static final String UNREADABLE_BODY_DIAGNOSTIC = "upstream success body is not readable JSON";

    private final EnvelopeCodec envelopes;
    private final JsonlCodec jsonl;
    private final RawCodec raw;
    private final WarningsRouter warnings;

    protected SearchPresenterBase(
            EnvelopeCodec envelopes, JsonlCodec jsonl, RawCodec raw, WarningsRouter warnings) {
        this.envelopes = Objects.requireNonNull(envelopes, "envelopes");
        this.jsonl = Objects.requireNonNull(jsonl, "jsonl");
        this.raw = Objects.requireNonNull(raw, "raw");
        this.warnings = Objects.requireNonNull(warnings, "warnings");
    }

    /** The warnings-channel router the seams open per invocation. */
    protected final WarningsRouter warnings() {
        return warnings;
    }

    /** The canonical command name: the exact dispatch and wire value echoed by every document. */
    protected abstract String command();

    /**
     * The command's human listing of {@code result}. The invocation's output state arrives with
     * it, because the heading's ANSI styling is the output state's own color decision.
     */
    protected abstract byte[] humanDocument(TRes result, TReq request, OutputRequest output);

    /** The command's stable projection of {@code result} for the JSON envelope. */
    protected abstract Projection jsonProjection(TReq request, TRes result);

    /** One field set per JSONL result record, in presentation order. */
    protected abstract List<List<Projection.Field>> jsonlResultRecords(TRes result);

    /** The terminal JSONL summary field set. */
    protected abstract List<Projection.Field> jsonlSummaryFields(TRes result, TReq request, int resultCount);

    /**
     * The lossless upstream body of {@code result}: the envelope's tree and the raw passthrough.
     * The completed-exchange view carries it, so every specialization inherits this reading.
     */
    protected UpstreamPayload upstreamOf(TRes result) {
        return result.body();
    }

    /**
     * The metadata of a successful exchange for the envelope's {@code meta}, read once over
     * the completed-exchange view: identifiers, status, windows, and the usage the response
     * headers offered.
     */
    protected RequestMeta successMetaOf(TRes result) {
        return new RequestMeta(
                result.requestId(), result.httpStatus(), result.apiVersion(), result.rateLimits().windows(), result.usage());
    }

    /** The metadata a malformed success body reports: the observed status and windows only. */
    protected RequestMeta malformedMetaOf(TRes result) {
        return new RequestMeta(null, result.httpStatus(), null, result.rateLimits().windows(), null);
    }

    /**
     * The exchange's advisory notes in routing order — the rate-limit notes first, then the
     * usage notes — read once over the completed-exchange view.
     */
    protected List<String> exchangeNotes(TRes result) {
        List<String> notes = new ArrayList<>(result.rateLimits().notes());
        if (result.usage() != null) {
            notes.addAll(result.usage().notes());
        }
        return notes;
    }

    @Override
    public final int present(
            Outcome<TRes> outcome,
            TReq request,
            OutputRequest output,
            ResultWriter results,
            DiagnosticsSink diagnostics) {
        Objects.requireNonNull(request, "request");
        try {
            return switch (outcome) {
                case Outcome.Success<TRes> success ->
                    presentSuccess(success.value(), request, output, results, diagnostics);
                case Outcome.Failure<TRes> failure -> presentFailure(failure, output, results, diagnostics);
            };
        } catch (UncheckedIOException writeFailure) {
            return exitForWriteFailure(writeFailure, diagnostics);
        }
    }

    private int presentSuccess(
            TRes result, TReq request, OutputRequest output, ResultWriter results, DiagnosticsSink diagnostics) {
        routeExchangeNotes(result, output, results, diagnostics);
        try {
            return switch (output.mode()) {
                case HUMAN -> {
                    results.write(humanDocument(result, request, output));
                    yield ExitCodeMapper.SUCCESS;
                }
                case JSON -> {
                    results.write(
                            envelopes.encodeSuccess(
                                    command(),
                                    jsonProjection(request, result),
                                    upstreamOf(result),
                                    successMetaOf(result),
                                    collectedExchangeNotes(result, output, results, diagnostics),
                                    output.pretty()));
                    yield ExitCodeMapper.SUCCESS;
                }
                case JSONL -> {
                    List<List<Projection.Field>> records = jsonlResultRecords(result);
                    for (List<Projection.Field> fields : records) {
                        results.write(jsonl.encodeResult(command(), fields));
                    }
                    results.write(jsonl.encodeSummary(command(), jsonlSummaryFields(result, request, records.size())));
                    yield ExitCodeMapper.SUCCESS;
                }
                case RAW -> {
                    results.write(raw.passthrough(upstreamOf(result)));
                    yield ExitCodeMapper.SUCCESS;
                }
            };
        } catch (UnreadableBodyException unreadable) {
            return presentUnreadableBody(result, output, results, diagnostics);
        }
    }

    /**
     * Routes the exchange's notes before any document on the warnings channel the active
     * mode owns; the json envelope collects the same notes itself instead, because its
     * warnings travel inside the document rather than ahead of it.
     */
    private void routeExchangeNotes(TRes result, OutputRequest output, ResultWriter results, DiagnosticsSink diagnostics) {
        if (output.mode() != OutputMode.JSON) {
            ModeAwareWarnings channel = warnings().open(output.mode(), diagnostics, results, output.quiet());
            exchangeNotes(result).forEach(channel::warning);
        }
    }

    /** Routes the exchange's notes into the json envelope's {@code meta.warnings}, in order. */
    private List<String> collectedExchangeNotes(TRes result, OutputRequest output, ResultWriter results, DiagnosticsSink diagnostics) {
        ModeAwareWarnings channel = warnings().open(output.mode(), diagnostics, results, output.quiet());
        exchangeNotes(result).forEach(channel::warning);
        return channel.collected();
    }

    private int presentFailure(
            Outcome.Failure<TRes> failure, OutputRequest output, ResultWriter results, DiagnosticsSink diagnostics) {
        boolean retryable = RetryableKinds.isRetryable(failure.kind());
        String upstreamCode = failure.upstream() == null ? null : failure.upstream().code();
        RequestMeta meta = failureMeta(failure.httpStatus(), windowsOf(failure));
        // jsonl owns its failure explanation: the error record on stdout is the whole story
        if (output.mode() != OutputMode.JSONL) {
            diagnostics.emit(command() + ": " + failure.diagnostic());
        }
        switch (output.mode()) {
            case JSON -> results.write(
                    envelopes.encodeFailure(command(), failure.kind(), failure.diagnostic(), retryable, upstreamCode, meta, output.pretty()));
            case JSONL -> results.write(
                    jsonl.encodeError(command(), failure.kind(), failure.diagnostic(), retryable, null, windowsOf(failure)));
            case RAW -> {
                if (failure.upstream() != null) {
                    results.write(raw.passthrough(failure.upstream().body()));
                }
            }
            case HUMAN -> {
                // the diagnostic line above is the whole human failure document
            }
        }
        return ExitCodeMapper.forKind(failure.kind());
    }

    private int presentUnreadableBody(
            TRes result, OutputRequest output, ResultWriter results, DiagnosticsSink diagnostics) {
        boolean retryable = RetryableKinds.isRetryable(FailureKind.MALFORMED);
        if (output.mode() != OutputMode.JSONL) {
            diagnostics.emit(command() + ": " + UNREADABLE_BODY_DIAGNOSTIC);
        }
        switch (output.mode()) {
            case JSON -> results.write(
                    envelopes.encodeFailure(
                            command(), FailureKind.MALFORMED, UNREADABLE_BODY_DIAGNOSTIC, retryable, null, malformedMetaOf(result), output.pretty()));
            case JSONL -> results.write(
                    jsonl.encodeError(
                            command(),
                            FailureKind.MALFORMED,
                            UNREADABLE_BODY_DIAGNOSTIC,
                            retryable,
                            null,
                            result.rateLimits().windows()));
            case HUMAN, RAW -> {
                // raw never parses its passthrough; human needs no stdout document
            }
        }
        return ExitCodeMapper.forKind(FailureKind.MALFORMED);
    }

    private static RequestMeta failureMeta(int httpStatus, List<RateLimitWindow> windows) {
        return new RequestMeta(null, httpStatus, null, windows, null);
    }

    /** The failing exchange's windows when it observed a snapshot; {@code null} keeps the error record window-free. */
    private List<RateLimitWindow> windowsOf(Outcome.Failure<TRes> failure) {
        return failure.rateLimits() == null ? null : failure.rateLimits().windows();
    }

    private int exitForWriteFailure(UncheckedIOException failure, DiagnosticsSink diagnostics) {
        return ResultWriteFailure.exitFor(failure, diagnostics, command());
    }
}

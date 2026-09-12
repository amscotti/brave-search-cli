package io.amscotti.bravesearch.adapter.cli.command.places;

import io.amscotti.bravesearch.adapter.cli.command.RemoteCommandSupport;
import io.amscotti.bravesearch.adapter.cli.command.WalkExitPolicy;
import io.amscotti.bravesearch.adapter.cli.option.GlobalOptions;
import io.amscotti.bravesearch.adapter.cli.option.RemoteOptions;
import io.amscotti.bravesearch.adapter.cli.presentation.DiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.PlaceEnrichmentPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.SearchPresenter;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.PlaceEnrichmentDispatch;
import io.amscotti.bravesearch.application.port.out.PlaceEnrichmentExchange;
import io.amscotti.bravesearch.application.service.PaginationService;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.CancellationRegistry;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.error.UsageValidationError;
import io.amscotti.bravesearch.domain.output.CommandOutputProfile;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.request.PlaceEnrichmentRequest;
import io.amscotti.bravesearch.domain.result.PlaceEnrichmentResult;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The shared run policy of the two place-enrichment commands: the single-request spine
 * of {@link RemoteCommandSupport} plus a sequential chunk fan-out that reuses the shared
 * page-walk orchestrator of {@link PaginationService} — a chunk source instead of a page
 * source — so pacing, cancellation, the first-failure abort, and the observer seam of
 * the walk are written once for every sequential multi-request family.
 *
 * <p>The fan-out is the invocation itself, never a flag: the command's ids — one
 * through two hundred — chunk into consecutive requests of at most twenty ids in input
 * order, and the walk requests them strictly sequentially under its own budget of chunk
 * counts. The command registers a fresh cancellation context with the process registry
 * so an interrupt can cancel a pacing wait, hands the presenter's per-chunk observer
 * (the JSONL record channel) each completed chunk, and renders the walk's terminal
 * state through the enrichment presenter. Because an invocation fans out, the output
 * profile is the multi-request family — {@code --output raw} is rejected before
 * anything is dispatched — and the process verdicts of the walk live in the shared
 * {@link WalkExitPolicy}.
 *
 * <p>A concrete command supplies its endpoint kind, its ids, its presenter, and its
 * canonical name; options to request to dispatch is all a subclass remains.
 */
abstract class PlaceEnrichmentCommandSupport
        extends RemoteCommandSupport<PlaceEnrichmentRequest, PlaceEnrichmentResult> {

    private final PlaceEnrichmentPresenter presenter;
    private final PlaceEnrichmentExchange exchange;
    private final PaginationService<PlaceEnrichmentResult> pagination;
    private final CancellationRegistry cancellations;

    /**
     * @param presenter the renderer of the command's walk outcomes, including the
     *     per-chunk JSONL recorder
     * @param exchange the chunk exchange seam, called with ascending chunk numbers
     * @param pagination the sequential walk orchestrator, wired with the always-continue
     *     probe — the chunk budget is the walk's sole terminator — and the cancellable
     *     pacing waiter
     * @param cancellations the process registry that lets an interrupt cancel a pacing
     *     wait of the walk
     */
    protected PlaceEnrichmentCommandSupport(
            GlobalOptions globals,
            RemoteOptions remoteOptions,
            CredentialProvider storedCredentials,
            CredentialProvider loopbackTestToken,
            PlaceEnrichmentPresenter presenter,
            PlaceEnrichmentExchange exchange,
            PaginationService<PlaceEnrichmentResult> pagination,
            CancellationRegistry cancellations,
            ResultWriter results,
            Function<java.io.Writer, DiagnosticsSink> diagnosticsFactory) {
        super(
                globals,
                remoteOptions,
                storedCredentials,
                loopbackTestToken,
                unreachableSingleRequestPresenter(),
                cancellations,
                results,
                diagnosticsFactory);
        this.presenter = Objects.requireNonNull(presenter, "presenter");
        this.exchange = Objects.requireNonNull(exchange, "exchange");
        this.pagination = Objects.requireNonNull(pagination, "pagination");
        this.cancellations = Objects.requireNonNull(cancellations, "cancellations");
    }

    /** The endpoint this command travels to. */
    protected abstract PlaceEnrichmentRequest.Kind kind();

    /** The command's parsed ids, at least one and at most two hundred. */
    protected abstract java.util.List<String> ids();

    @Override
    protected final CommandOutputProfile outputProfile() {
        // an enrichment invocation fans out into sequential chunk requests: raw is the
        // single-request channel and is rejected before anything is dispatched
        return CommandOutputProfile.REMOTE_MULTI_REQUEST;
    }

    @Override
    protected final PlaceEnrichmentRequest buildRequest() throws UsageValidationError {
        try {
            return new PlaceEnrichmentRequest(kind(), ids());
        } catch (IllegalArgumentException invocationBound) {
            // the request's own bound is the CLI's invocation cap: at the command
            // boundary it is a usage rule, reported without quoting any id
            throw new UsageValidationError(invocationBound.getMessage());
        }
    }

    @Override
    protected final int dispatchAndPresent(
            PlaceEnrichmentRequest request,
            Credential credential,
            OutputRequest output,
            ResultWriter results,
            DiagnosticsSink diagnostics) {
        CancellationContext cancellation = new CancellationContext();
        Runnable detach = cancellations.register(cancellation);
        try {
            PaginationService.PagedRun<PlaceEnrichmentResult> run;
            try {
                run = pagination.fetchAll(
                        chunk -> exchange(chunkOf(request, chunk), credential, cancellation),
                        request.chunkCount(),
                        cancellation,
                        presenter.chunkObserver(request, output, results, diagnostics));
            } catch (UncheckedIOException writeFailure) {
                return WalkExitPolicy.exitForWalkWriteFailure(writeFailure, cancellation, diagnostics, commandName());
            }
            int presented;
            try {
                presented = presenter.present(run, request, output, results, diagnostics);
            } catch (UncheckedIOException writeFailure) {
                return WalkExitPolicy.exitForWalkWriteFailure(writeFailure, cancellation, diagnostics, commandName());
            }
            Optional<Integer> signalExit = WalkExitPolicy.latchedSignalExit(run, cancellation);
            if (signalExit.isPresent()) {
                // the latch is authoritative: the mode rendered its transport-failure document
                // with the run's counts, while the signalled process exits by its signal
                return signalExit.get();
            }
            return presented;
        } finally {
            detach.run();
        }
    }

    /**
     * The single-request render the spine would call after one exchange — unreachable
     * here, because an enrichment invocation always walks its chunks through the
     * overridden dispatch step.
     */
    private static SearchPresenter<PlaceEnrichmentRequest, PlaceEnrichmentResult> unreachableSingleRequestPresenter() {
        return (outcome, request, output, results, diagnostics) -> {
            throw new IllegalStateException("an enrichment invocation always walks its chunks");
        };
    }

    /** The invocation's request pinned to one chunk — the slice the walk dispatches. */
    private static PlaceEnrichmentRequest chunkOf(PlaceEnrichmentRequest request, int chunk) {
        return new PlaceEnrichmentRequest(request.kind(), request.idsOfChunk(chunk));
    }

    @Override
    protected final Outcome<PlaceEnrichmentResult> exchange(
            PlaceEnrichmentRequest chunk, Credential credential, CancellationContext cancellation) {
        return exchange.dispatch(new PlaceEnrichmentDispatch(
                chunk,
                remoteOptions.origin(),
                credential,
                remoteOptions.totalTimeout(),
                remoteOptions.connectTimeout(),
                remoteOptions.apiVersionPin(),
                cancellation));
    }
}

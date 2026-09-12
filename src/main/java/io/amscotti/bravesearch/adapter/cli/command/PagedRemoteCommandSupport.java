package io.amscotti.bravesearch.adapter.cli.command;

import io.amscotti.bravesearch.adapter.cli.option.CommonSearchOptions;
import io.amscotti.bravesearch.adapter.cli.option.GlobalOptions;
import io.amscotti.bravesearch.adapter.cli.option.RemoteOptions;
import io.amscotti.bravesearch.adapter.cli.presentation.DiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.PagedSearchPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.SearchPresenter;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.service.PaginationService;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.CancellationRegistry;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.UsageValidationError;
import io.amscotti.bravesearch.domain.output.CommandOutputProfile;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.result.PagedExchange;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * The shared run policy of every paginated remote search command: the single-request spine
 * of {@link RemoteCommandSupport} plus the sequential page walk of {@code --all-pages},
 * written once so one paginated endpoint's walk is cloned by options and renderers instead
 * of by copy.
 *
 * <p>{@code --all-pages} switches the invocation to the sequential multi-request walk of
 * {@link PaginationService}: the command registers a fresh cancellation context with the
 * process registry so an interrupt can cancel a pacing wait, walks the user-facing pages
 * under the {@code --max-pages} budget, streams each completed page to the paged presenter's
 * observer (the JSONL record channel), and renders the run's terminal state through the
 * paged presenter. The same flag switches the output profile to the multi-request family,
 * which rejects {@code --output raw} before anything is dispatched, because raw is the
 * single-request channel. The pagination grammar — {@code --page} exclusivity, {@code
 * --max-pages} requiring {@code --all-pages}, the documented page bounds — is enforced here
 * before any dispatch.
 *
 * <p>The walk's process status follows its cancellation latch: a write failure whose cause
 * is the consumer's closed pipe latches the broken-pipe cause and ends the run as silent
 * success, any other write failure keeps the shared transport verdict of {@link
 * io.amscotti.bravesearch.adapter.cli.presentation.ResultWriteFailure}, and a walk that
 * failed while the latch held the interrupt cause
 * renders the mode's transport-failure document with the run's counts but exits by the
 * interrupt signal (130) — the latch, not the rendered kind, decides.
 *
 * <p>A concrete command supplies its name, the documented page bounds, the page-pinning form
 * of its request, its endpoint-local option validation, the request assembly, the exchange
 * call, and — when its endpoint documents Goggles — its own goggle mixin with the verbose
 * file report; options to request to dispatch is all a subclass remains.
 *
 * @param <TReq> the command's parsed request type
 * @param <TRes> the endpoint's completed-exchange type every page carries
 */
public abstract class PagedRemoteCommandSupport<TReq, TRes extends PagedExchange>
        extends RemoteCommandSupport<TReq, TRes> {

    @Mixin
    protected CommonSearchOptions commonOptions;

    @Option(names = "--all-pages", description = "Request the pages sequentially, up to the bound, and aggregate them.")
    boolean allPages;

    @Option(
            names = "--max-pages",
            paramLabel = "<1..10>",
            description = "The page budget of --all-pages; the default is the documented maximum of 10.")
    Integer maxPages;

    private final PagedSearchPresenter<TReq, TRes> pagedPresenter;

    private final PaginationService<TRes> pagination;

    private final CancellationRegistry cancellations;

    /**
     * @param pagination the sequential page-walk orchestrator of {@code --all-pages}
     * @param cancellations the process registry that lets an interrupt cancel a pacing wait
     *     of the page walk
     */
    protected PagedRemoteCommandSupport(
            GlobalOptions globals,
            RemoteOptions remoteOptions,
            CommonSearchOptions commonOptions,
            CredentialProvider storedCredentials,
            CredentialProvider loopbackTestToken,
            SearchPresenter<TReq, TRes> presenter,
            PagedSearchPresenter<TReq, TRes> pagedPresenter,
            PaginationService<TRes> pagination,
            CancellationRegistry cancellations,
            ResultWriter results,
            Function<java.io.Writer, DiagnosticsSink> diagnosticsFactory) {
        super(
                globals,
                remoteOptions,
                storedCredentials,
                loopbackTestToken,
                presenter,
                cancellations,
                results,
                diagnosticsFactory);
        this.commonOptions = Objects.requireNonNull(commonOptions, "commonOptions");
        this.pagedPresenter = Objects.requireNonNull(pagedPresenter, "pagedPresenter");
        this.pagination = Objects.requireNonNull(pagination, "pagination");
        this.cancellations = Objects.requireNonNull(cancellations, "cancellations");
    }

    /** The smallest user-facing page the endpoint documents. */
    protected abstract int minDocumentedPage();

    /** The largest user-facing page the endpoint documents; also the default walk budget. */
    protected abstract int maxDocumentedPage();

    /**
     * The same request pinned to {@code page} — the form sequential pagination walks
     * without rebuilding every member.
     */
    protected abstract TReq withPage(TReq request, int page);

    /**
     * The endpoint's own local request validation beyond the shared pagination grammar; the
     * default accepts everything, because the single-request spine already ran the shared
     * rejections.
     *
     * @throws UsageValidationError when a parsed combination violates the endpoint contract
     */
    protected void validateEndpointOptions() throws UsageValidationError {}

    /** Assembles the endpoint's domain request from the parsed options. */
    protected abstract TReq requestOf();

    @Override
    protected final CommandOutputProfile outputProfile() {
        // raw is the single-request channel: a page walk is the multi-request family
        return allPages ? CommandOutputProfile.REMOTE_MULTI_REQUEST : CommandOutputProfile.REMOTE;
    }

    @Override
    protected final TReq buildRequest() throws UsageValidationError {
        validatePaginationOptions();
        validateEndpointOptions();
        return requestOf();
    }

    @Override
    protected final int dispatchAndPresent(
            TReq request, Credential credential, OutputRequest output, ResultWriter results, DiagnosticsSink diagnostics) {
        reportInvocationDiagnostics(output, diagnostics);
        if (!allPages) {
            return super.dispatchAndPresent(request, credential, output, results, diagnostics);
        }
        CancellationContext cancellation = new CancellationContext();
        Runnable detach = cancellations.register(cancellation);
        try {
            PaginationService.PagedRun<TRes> run;
            try {
                run = pagination.fetchAll(
                        page -> exchange(withPage(request, page), credential, cancellation),
                        maxPages == null ? maxDocumentedPage() : maxPages,
                        cancellation,
                        pagedPresenter.pageObserver(output, results, diagnostics));
            } catch (UncheckedIOException writeFailure) {
                return exitForWalkWriteFailure(writeFailure, cancellation, diagnostics);
            }
            int presented;
            try {
                presented = pagedPresenter.present(run, request, output, results, diagnostics);
            } catch (UncheckedIOException writeFailure) {
                return exitForWalkWriteFailure(writeFailure, cancellation, diagnostics);
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
     * The walk's own write-failure policy lives once in the shared
     * {@link WalkExitPolicy} of every sequential multi-request walk.
     */
    private int exitForWalkWriteFailure(
            UncheckedIOException failure, CancellationContext cancellation, DiagnosticsSink diagnostics) {
        return WalkExitPolicy.exitForWalkWriteFailure(failure, cancellation, diagnostics, commandName());
    }

    /**
     * The endpoint's own verbose invocation report — by default nothing. A command whose
     * endpoint documents Goggles overrides this with the goggle mixin's own verbose file
     * report ({@code GoggleOptions.reportLoadedFiles}); the goggle input options themselves
     * are a per-command concern, because an endpoint that documents no Goggles must reject
     * every goggle spelling as an unknown option rather than silently ignore it.
     */
    protected void reportInvocationDiagnostics(OutputRequest output, DiagnosticsSink diagnostics) {}

    private void validatePaginationOptions() throws UsageValidationError {
        if (allPages && commonOptions.page() != null) {
            throw new UsageValidationError("--page and --all-pages are mutually exclusive");
        }
        if (maxPages != null && !allPages) {
            throw new UsageValidationError("--max-pages requires --all-pages");
        }
        if (maxPages != null && (maxPages < minDocumentedPage() || maxPages > maxDocumentedPage())) {
            throw new UsageValidationError(
                    "max-pages must be between " + minDocumentedPage() + " and " + maxDocumentedPage());
        }
    }
}

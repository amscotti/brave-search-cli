package io.amscotti.bravesearch.adapter.cli.presentation;

import io.amscotti.bravesearch.application.service.PaginationService;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.result.PagedExchange;
import io.amscotti.bravesearch.domain.result.PagedSearch;
import java.util.function.Consumer;

/**
 * Renders one completed multi-request pagination run — the aggregate of its completed
 * pages or the failure of its first failed page — on the selected output channel,
 * written once against the endpoint-agnostic {@link PagedExchange} so every paginated
 * command's renderer plugs in unchanged.
 *
 * <p>JSONL is the one streaming mode: {@link #pageObserver} hands back the per-page
 * recorder that emits each completed page's deduplicated result records while the run is
 * still walking, so a later page failure leaves those records standing and the terminal
 * {@code present} call adds the counted error record. Every other mode buffers and renders
 * only the whole run; their observer is a no-op, and a failed run emits no result payload at
 * all — only the mode's failure document with the requested and received page counts.
 *
 * @param <TReq> the command's parsed request type
 * @param <TRes> the endpoint's completed-exchange type every page carries
 */
public interface PagedSearchPresenter<TReq, TRes extends PagedExchange> {

    /**
     * Renders the terminal state of one pagination run on the selected output channel and
     * owns its exit status.
     *
     * @param run the run's completed pages and — when it failed — the first failed page's
     *     verbatim failure
     * @param request the invocation's request; its query names the aggregate listing
     */
    int present(
            PaginationService.PagedRun<TRes> run,
            TReq request,
            OutputRequest output,
            ResultWriter results,
            DiagnosticsSink diagnostics);

    /**
     * The per-page recorder of this invocation for the selected mode: the JSONL record
     * streamer, or a no-op for the buffered modes.
     */
    Consumer<PagedSearch.Page<TRes>> pageObserver(
            OutputRequest output, ResultWriter results, DiagnosticsSink diagnostics);
}

package io.amscotti.bravesearch.adapter.cli.presentation;

import io.amscotti.bravesearch.application.service.PaginationService;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.request.PlaceEnrichmentRequest;
import io.amscotti.bravesearch.domain.result.PagedSearch;
import io.amscotti.bravesearch.domain.result.PlaceEnrichmentResult;
import java.util.function.Consumer;

/**
 * Renders one completed place-enrichment chunk walk — the order-reconstructed aggregate
 * of its completed chunks or the failure of its first failed chunk — on the selected
 * output channel.
 *
 * <p>JSONL is the one streaming mode: {@link #chunkObserver} hands back the per-chunk
 * recorder that emits each completed chunk's per-id records — in the input order of that
 * chunk's slice, placeholders at their original positions — while the walk is still
 * running, so a later chunk failure leaves those records standing and the terminal
 * {@link #present} call adds the counted error record. Every other mode buffers and
 * renders only the whole walk; their observer is a no-op, and a failed walk emits no
 * result payload at all — only the mode's failure document with the requested and
 * received request counts.
 *
 * <p>Unlike the page-walk presenter family, the observer needs the invocation's request
 * — the input ids are the reconstruction source — so this contract carries it, and the
 * walk's page number is the chunk number of the invocation.
 */
public interface PlaceEnrichmentPresenter {

    /**
     * Renders the terminal state of one enrichment walk on the selected output channel
     * and owns its exit status.
     *
     * @param run the walk's completed chunks and — when it failed — the first failed
     *     chunk's verbatim failure
     * @param request the invocation's request; its ids name the reconstructed positions
     */
    int present(
            PaginationService.PagedRun<PlaceEnrichmentResult> run,
            PlaceEnrichmentRequest request,
            OutputRequest output,
            ResultWriter results,
            DiagnosticsSink diagnostics);

    /**
     * The per-chunk recorder of this invocation for the selected mode: the JSONL record
     * streamer, or a no-op for the buffered modes.
     */
    Consumer<PagedSearch.Page<PlaceEnrichmentResult>> chunkObserver(
            PlaceEnrichmentRequest request,
            OutputRequest output,
            ResultWriter results,
            DiagnosticsSink diagnostics);
}

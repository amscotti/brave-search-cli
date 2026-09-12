package io.amscotti.bravesearch.adapter.cli.presentation;

import io.amscotti.bravesearch.domain.request.WebSearchRequest;
import io.amscotti.bravesearch.domain.result.WebSearchResult;

/**
 * Renders one completed multi-request web pagination run — the aggregate of its completed
 * pages or the failure of its first failed page — on the selected output channel; the
 * channel contract is the generic {@link PagedSearchPresenter} one every paginated command
 * shares.
 */
public interface WebPagedSearchPresenter extends PagedSearchPresenter<WebSearchRequest, WebSearchResult> {

    /** The canonical command name of web search, the exact dispatch and wire value. */
    String COMMAND = "web";
}

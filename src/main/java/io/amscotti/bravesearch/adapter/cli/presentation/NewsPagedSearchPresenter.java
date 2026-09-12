package io.amscotti.bravesearch.adapter.cli.presentation;

import io.amscotti.bravesearch.domain.request.NewsSearchRequest;
import io.amscotti.bravesearch.domain.result.NewsSearchResult;

/**
 * Renders one completed multi-request news pagination run — the aggregate of its completed
 * pages or the failure of its first failed page — on the selected output channel; the
 * channel contract is the generic {@link PagedSearchPresenter} one every paginated command
 * shares.
 */
public interface NewsPagedSearchPresenter extends PagedSearchPresenter<NewsSearchRequest, NewsSearchResult> {

    /** The canonical command name of news search, the exact dispatch and wire value. */
    String COMMAND = "news";
}

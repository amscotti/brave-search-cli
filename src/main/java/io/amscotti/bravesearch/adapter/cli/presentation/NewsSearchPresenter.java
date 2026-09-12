package io.amscotti.bravesearch.adapter.cli.presentation;

import io.amscotti.bravesearch.domain.request.NewsSearchRequest;
import io.amscotti.bravesearch.domain.result.NewsSearchResult;

/**
 * Renders one completed news-search exchange — success or expected failure — on the selected
 * output channel; see {@link SearchPresenter} for the contract both sides share. The news
 * specialization of the generic presenter surface, kept as its own type so the command, the
 * composition, and the contract index name the news renderer concretely.
 */
public interface NewsSearchPresenter extends SearchPresenter<NewsSearchRequest, NewsSearchResult> {

    /** The canonical command name of news search, the exact dispatch and wire value. */
    String COMMAND = "news";
}

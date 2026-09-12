package io.amscotti.bravesearch.adapter.cli.presentation;

import io.amscotti.bravesearch.domain.request.WebSearchRequest;
import io.amscotti.bravesearch.domain.result.WebSearchResult;

/**
 * Renders one completed web-search exchange — success or expected failure — on the selected
 * output channel; see {@link SearchPresenter} for the contract both sides share. The web
 * specialization of the generic presenter surface, kept as its own type so the command, the
 * composition, and the contract index name the web renderer concretely.
 */
public interface WebSearchPresenter extends SearchPresenter<WebSearchRequest, WebSearchResult> {

    /** The canonical command name of web search, the exact dispatch and wire value. */
    String COMMAND = "web";
}

package io.amscotti.bravesearch.adapter.cli.presentation;

import io.amscotti.bravesearch.domain.request.SuggestRequest;
import io.amscotti.bravesearch.domain.result.SuggestSearchResult;

/**
 * Renders one completed suggest exchange — success or expected failure — on the selected
 * output channel; see {@link SearchPresenter} for the contract both sides share. The
 * suggest specialization of the generic presenter surface, kept as its own type so the
 * command, the composition, and the contract index name the suggest renderer concretely.
 */
public interface SuggestPresenter extends SearchPresenter<SuggestRequest, SuggestSearchResult> {

    /** The canonical command name of suggest, the exact dispatch and wire value. */
    String COMMAND = "suggest";
}

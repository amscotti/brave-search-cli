package io.amscotti.bravesearch.adapter.cli.presentation;

import io.amscotti.bravesearch.domain.request.ContextRequest;
import io.amscotti.bravesearch.domain.result.ContextResult;

/**
 * Renders one completed LLM-context exchange — success or expected failure — on the selected
 * output channel; see {@link SearchPresenter} for the contract both sides share. The
 * context specialization of the generic presenter surface, kept as its own type so the
 * command, the composition, and the contract index name the context renderer concretely.
 */
public interface ContextPresenter extends SearchPresenter<ContextRequest, ContextResult> {

    /** The canonical command name of LLM context retrieval, the exact dispatch and wire value. */
    String COMMAND = "context";
}

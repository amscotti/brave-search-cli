package io.amscotti.bravesearch.adapter.cli.presentation;

import io.amscotti.bravesearch.domain.request.RichRequest;
import io.amscotti.bravesearch.domain.result.RichResult;

/**
 * Renders one completed rich callback exchange — success or expected failure — on the
 * selected output channel; see {@link SearchPresenter} for the contract both sides
 * share. The rich specialization of the generic presenter surface, kept as its own type
 * so the command, the composition, and the contract index name the rich renderer
 * concretely.
 */
public interface RichPresenter extends SearchPresenter<RichRequest, RichResult> {

    /** The canonical command name of rich, the exact dispatch and wire value. */
    String COMMAND = "rich";
}

package io.amscotti.bravesearch.adapter.cli.presentation;

import io.amscotti.bravesearch.domain.request.SpellcheckRequest;
import io.amscotti.bravesearch.domain.result.SpellcheckSearchResult;

/**
 * Renders one completed spellcheck exchange — success or expected failure — on the
 * selected output channel; see {@link SearchPresenter} for the contract both sides
 * share. The spellcheck specialization of the generic presenter surface, kept as its own
 * type so the command, the composition, and the contract index name the spellcheck
 * renderer concretely.
 */
public interface SpellcheckPresenter extends SearchPresenter<SpellcheckRequest, SpellcheckSearchResult> {

    /** The canonical command name of spellcheck, the exact dispatch and wire value. */
    String COMMAND = "spellcheck";
}

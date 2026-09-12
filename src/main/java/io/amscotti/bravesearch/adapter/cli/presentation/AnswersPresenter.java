package io.amscotti.bravesearch.adapter.cli.presentation;

import io.amscotti.bravesearch.domain.request.AnswersRequest;
import io.amscotti.bravesearch.domain.result.AnswersResult;

/**
 * Renders one completed Answers exchange — success or expected failure — on the selected
 * output channel; see {@link SearchPresenter} for the contract both sides share. The
 * answers specialization of the generic presenter surface, kept as its own type so the
 * command, the composition, and the contract index name the answers renderer concretely.
 */
public interface AnswersPresenter extends SearchPresenter<AnswersRequest, AnswersResult> {

    /** The canonical command name of answers, the exact dispatch and wire value. */
    String COMMAND = "answers";
}

package io.amscotti.bravesearch.application.port.out;

import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.request.AnswersRequest;
import io.amscotti.bravesearch.domain.result.AnswersResult;

/** Outbound gateway port for the Brave Answers chat-completions endpoint. */
public interface AnswersPort {

    Outcome<AnswersResult> answer(AnswersRequest request);
}

package io.amscotti.bravesearch.application.port.out;

import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.request.ContextRequest;
import io.amscotti.bravesearch.domain.result.ContextResult;

/** Outbound gateway port for the Brave LLM context endpoint. */
public interface ContextPort {

    /**
     * Performs the context retrieval of {@code request}.
     *
     * @throws java.lang.NullPointerException when {@code request} is null
     */
    Outcome<ContextResult> search(ContextRequest request);
}

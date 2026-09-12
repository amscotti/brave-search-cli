package io.amscotti.bravesearch.application.port.out;

import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.result.ContextResult;

/**
 * Outbound port of one fully parsed LLM-context invocation: unlike {@link ContextPort}, the
 * invocation carries the parsed origin, budgets, and version pin, because a CLI process
 * learns them from its command line only after the command object exists. The CLI injects
 * this port; the library facade keeps injecting {@link ContextPort} with fixed wiring.
 */
@FunctionalInterface
public interface ContextExchange {

    /**
     * Performs the context retrieval of {@code invocation}.
     *
     * @throws java.lang.NullPointerException when {@code invocation} is null
     */
    Outcome<ContextResult> dispatch(ContextDispatch invocation);
}

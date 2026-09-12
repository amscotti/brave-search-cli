package io.amscotti.bravesearch.application.port.out;

import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.result.AnswersResult;

/**
 * Outbound port of one fully parsed answers invocation: unlike {@link AnswersPort}, the
 * invocation carries the parsed origin, budgets, and version pin, because a CLI process
 * learns them from its command line only after the command object exists. The CLI injects
 * this port; the library facade keeps injecting {@link AnswersPort} with fixed wiring.
 */
@FunctionalInterface
public interface AnswersExchange {

    /**
     * Performs the blocking answers exchange of {@code invocation}.
     *
     * @throws java.lang.NullPointerException when {@code invocation} is null
     */
    Outcome<AnswersResult> dispatch(AnswersDispatch invocation);
}

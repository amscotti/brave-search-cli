package io.amscotti.bravesearch.application.port.out;

import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.result.SpellcheckSearchResult;

/**
 * Outbound port of one fully parsed spellcheck invocation: unlike {@link
 * SpellcheckPort}, the invocation carries the parsed origin, budgets, and version pin,
 * because a CLI process learns them from its command line only after the command object
 * exists. The CLI injects this port; the library facade keeps injecting {@link
 * SpellcheckPort} with fixed wiring.
 */
@FunctionalInterface
public interface SpellcheckExchange {

    /**
     * Performs the spellcheck lookup of {@code invocation}.
     *
     * @throws java.lang.NullPointerException when {@code invocation} is null
     */
    Outcome<SpellcheckSearchResult> dispatch(SpellcheckDispatch invocation);
}

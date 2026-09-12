package io.amscotti.bravesearch.application.port.out;

import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.result.RichResult;

/**
 * Outbound port of one fully parsed rich callback invocation: unlike {@link RichPort},
 * the invocation carries the parsed origin, budgets, and version pin, because a CLI
 * process learns them from its command line only after the command object exists. The
 * CLI injects this port; the library facade keeps injecting {@link RichPort} with fixed
 * wiring.
 */
@FunctionalInterface
public interface RichExchange {

    /**
     * Performs the rich callback lookup of {@code invocation}.
     *
     * @throws java.lang.NullPointerException when {@code invocation} is null
     */
    Outcome<RichResult> dispatch(RichDispatch invocation);
}

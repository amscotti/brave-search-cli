package io.amscotti.bravesearch.application.port.out;

import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.result.PlaceSearchResult;

/**
 * Outbound port of one fully parsed place search invocation: unlike {@link
 * PlaceSearchPort}, the invocation carries the parsed origin, budgets, and version pin,
 * because a CLI process learns them from its command line only after the command
 * object exists. The CLI injects this port; the library facade keeps injecting {@link
 * PlaceSearchPort} with fixed wiring.
 */
@FunctionalInterface
public interface PlaceSearchExchange {

    /**
     * Performs the place search of {@code invocation}.
     *
     * @throws java.lang.NullPointerException when {@code invocation} is null
     */
    Outcome<PlaceSearchResult> dispatch(PlaceSearchDispatch invocation);
}

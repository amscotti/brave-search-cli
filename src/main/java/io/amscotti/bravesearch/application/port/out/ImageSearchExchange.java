package io.amscotti.bravesearch.application.port.out;

import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.result.ImageSearchResult;

/**
 * Outbound port of one fully parsed images-search invocation: unlike {@link
 * ImageSearchPort}, the invocation carries the parsed origin, budgets, and version pin,
 * because a CLI process learns them from its command line only after the command object
 * exists. The CLI injects this port; the library facade keeps injecting {@link
 * ImageSearchPort} with fixed wiring.
 */
@FunctionalInterface
public interface ImageSearchExchange {

    /**
     * Performs the search of {@code invocation}.
     *
     * @throws java.lang.NullPointerException when {@code invocation} is null
     */
    Outcome<ImageSearchResult> dispatch(ImageSearchDispatch invocation);
}

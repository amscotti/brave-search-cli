package io.amscotti.bravesearch.application.port.out;

import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.result.NewsSearchResult;

/**
 * Outbound port of one fully parsed news-search invocation: unlike {@link NewsSearchPort}, the
 * invocation carries the parsed origin, budgets, and version pin, because a CLI process
 * learns them from its command line only after the command object exists. The CLI injects
 * this port; the library facade keeps injecting {@link NewsSearchPort} with fixed wiring.
 */
@FunctionalInterface
public interface NewsSearchExchange {

    /**
     * Performs the search of {@code invocation}.
     *
     * @throws java.lang.NullPointerException when {@code invocation} is null
     */
    Outcome<NewsSearchResult> dispatch(NewsSearchDispatch invocation);
}

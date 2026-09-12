package io.amscotti.bravesearch.application.port.out;

import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.result.PlaceEnrichmentResult;

/**
 * Outbound port of one place-enrichment chunk invocation: the chunk carries its parsed
 * origin, budgets, and version pin, because a CLI process learns them from its command
 * line only after the command object exists. The sequential chunk walk of one
 * invocation calls this port once per chunk, strictly in order; the CLI injects this
 * port.
 */
@FunctionalInterface
public interface PlaceEnrichmentExchange {

    /**
     * Performs the enrichment exchange of {@code invocation}.
     *
     * @throws java.lang.NullPointerException when {@code invocation} is null
     */
    Outcome<PlaceEnrichmentResult> dispatch(PlaceEnrichmentDispatch invocation);
}

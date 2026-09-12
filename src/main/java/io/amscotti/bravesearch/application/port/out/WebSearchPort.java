package io.amscotti.bravesearch.application.port.out;

import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.request.WebSearchRequest;
import io.amscotti.bravesearch.domain.result.WebSearchResult;

/** Outbound gateway port for the Brave web search endpoint. */
public interface WebSearchPort {

    Outcome<WebSearchResult> search(WebSearchRequest request);
}

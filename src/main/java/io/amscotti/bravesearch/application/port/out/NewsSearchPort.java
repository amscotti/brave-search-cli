package io.amscotti.bravesearch.application.port.out;

import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.request.NewsSearchRequest;
import io.amscotti.bravesearch.domain.result.NewsSearchResult;

/** Outbound gateway port for the Brave news search endpoint. */
public interface NewsSearchPort {

    Outcome<NewsSearchResult> search(NewsSearchRequest request);
}

package io.amscotti.bravesearch.application.port.out;

import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.request.SuggestRequest;
import io.amscotti.bravesearch.domain.result.SuggestSearchResult;

/** Outbound gateway port for the Brave suggest endpoint. */
public interface SuggestPort {

    Outcome<SuggestSearchResult> suggest(SuggestRequest request);
}

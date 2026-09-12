package io.amscotti.bravesearch.application.port.out;

import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.request.PlaceSearchRequest;
import io.amscotti.bravesearch.domain.result.PlaceSearchResult;

/** Outbound gateway port for the Brave place search endpoint. */
public interface PlaceSearchPort {

    Outcome<PlaceSearchResult> search(PlaceSearchRequest request);
}

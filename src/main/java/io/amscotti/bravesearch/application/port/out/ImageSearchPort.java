package io.amscotti.bravesearch.application.port.out;

import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.request.ImageSearchRequest;
import io.amscotti.bravesearch.domain.result.ImageSearchResult;

/** Outbound gateway port for the Brave images search endpoint. */
public interface ImageSearchPort {

    Outcome<ImageSearchResult> search(ImageSearchRequest request);
}

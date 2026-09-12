package io.amscotti.bravesearch.application.port.out;

import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.request.VideoSearchRequest;
import io.amscotti.bravesearch.domain.result.VideoSearchResult;

/** Outbound gateway port for the Brave videos search endpoint. */
public interface VideoSearchPort {

    Outcome<VideoSearchResult> search(VideoSearchRequest request);
}

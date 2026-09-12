package io.amscotti.bravesearch.application.port.out;

import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.request.RichRequest;
import io.amscotti.bravesearch.domain.result.RichResult;

/** Outbound gateway port for the Brave Web rich-result callback endpoint. */
public interface RichPort {

    Outcome<RichResult> rich(RichRequest request);
}

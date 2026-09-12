package io.amscotti.bravesearch.application.port.out;

import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.request.SpellcheckRequest;
import io.amscotti.bravesearch.domain.result.SpellcheckSearchResult;

/** Outbound gateway port for the Brave spellcheck endpoint. */
public interface SpellcheckPort {

    Outcome<SpellcheckSearchResult> spellcheck(SpellcheckRequest request);
}

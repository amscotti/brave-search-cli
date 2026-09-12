package io.amscotti.bravesearch.domain.request;

import io.amscotti.bravesearch.domain.error.UsageValidationError;
import java.util.Objects;

/**
 * Immutable, fully validated request for the Web rich-result callback endpoint.
 *
 * <p>The checked-in upstream contract documents no format rules for a callback key — the
 * endpoint's own reference page does not exist — so the key is opaque: the only boundary
 * is that it carries at least one non-whitespace character. Every spelling, including
 * option-like and reserved-character-laden ones, travels verbatim, because the key names
 * an earlier web search's rich-result callback, not a free-text query; percent-encoding
 * is the endpoint assembly's concern, never the domain's. The endpoint documents no
 * other request member, so the request carries none.
 */
public record RichRequest(String callbackKey) {

    public RichRequest {
        Objects.requireNonNull(callbackKey, "callbackKey");
        if (callbackKey.isBlank()) {
            throw new UsageValidationError("callback key must not be blank");
        }
    }
}

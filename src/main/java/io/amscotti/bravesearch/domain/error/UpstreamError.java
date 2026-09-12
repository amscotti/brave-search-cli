package io.amscotti.bravesearch.domain.error;

import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import java.util.Objects;

/**
 * The structured facts extracted from a bounded upstream error body, alongside that body kept
 * verbatim.
 *
 * <p>{@code code} and {@code timestamp} are the projections the Brave error envelope may carry
 * under {@code error}; both are {@code null} when the body was missing, malformed, or silent.
 * The {@code timestamp} keeps its original text rather than a parsed instant, so no format
 * assumption can lose precision. {@code body} is the raw bounded body itself — the machine
 * surface treats it as the lossless record of everything the server said, including unknown
 * {@code error.meta} fields, while human diagnostics derive from the status alone and never
 * quote it.
 */
public record UpstreamError(String code, String timestamp, UpstreamPayload body) {

    public UpstreamError {
        Objects.requireNonNull(body, "body");
    }
}

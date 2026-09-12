package io.amscotti.bravesearch.application.exchange;

import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.metadata.Usage;
import java.util.Objects;

/**
 * The status, bounded body, and observed metadata of one completed non-streaming Brave
 * exchange.
 *
 * <p>{@code rateLimits} is never null: an exchange that carried no rate-limit headers carries
 * an empty snapshot instead. {@code usage} is null unless the exchange carried Answers usage
 * headers, and {@code requestId} and {@code apiVersion} are null unless the response headers
 * offered those identifiers. The two-argument constructor is the metadata-free form used
 * before the response headers have been parsed; the transport attaches the observed metadata
 * on the success path through {@link #withMetadata(RateLimitSnapshot, Usage, String, String)},
 * because metadata parsing is nonfatal by contract and must never influence whether an
 * exchange succeeded.
 */
public record BraveHttpResponse(
        int statusCode, UpstreamPayload body, RateLimitSnapshot rateLimits, Usage usage, String requestId, String apiVersion) {

    public BraveHttpResponse {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(rateLimits, "rateLimits");
    }

    public BraveHttpResponse(int statusCode, UpstreamPayload body) {
        this(statusCode, body, RateLimitSnapshot.empty(), null, null, null);
    }

    /** The same exchange with the metadata observed on its response headers attached. */
    public BraveHttpResponse withMetadata(
            RateLimitSnapshot observedRateLimits, Usage observedUsage, String observedRequestId, String observedApiVersion) {
        return new BraveHttpResponse(
                statusCode, body, observedRateLimits, observedUsage, observedRequestId, observedApiVersion);
    }
}

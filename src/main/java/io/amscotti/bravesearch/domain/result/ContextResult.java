package io.amscotti.bravesearch.domain.result;

import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.metadata.Usage;
import java.util.Objects;

/**
 * One completed LLM context exchange as the application sees it: the HTTP status, the
 * lossless bounded body bytes, and the metadata observed on the response headers.
 *
 * <p>The body stays raw on purpose — the structured projection of the context document is a
 * separate concern fed from these bytes — so no parsing failure can lose or alter what the
 * upstream sent. {@code rateLimits} is never null (an exchange without rate-limit headers
 * carries an empty snapshot); {@code usage}, {@code requestId}, and {@code apiVersion} are
 * null unless the exchange's response headers offered them.
 */
public record ContextResult(
        int httpStatus,
        UpstreamPayload body,
        RateLimitSnapshot rateLimits,
        Usage usage,
        String requestId,
        String apiVersion) implements PagedExchange {

    public ContextResult {
        Objects.requireNonNull(body, "body");
        rateLimits = rateLimits == null ? RateLimitSnapshot.empty() : rateLimits;
    }
}

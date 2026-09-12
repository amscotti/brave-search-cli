package io.amscotti.bravesearch.domain.result;

import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.metadata.Usage;
import java.util.Objects;

/**
 * One completed web search exchange as the application sees it: the HTTP status, the
 * lossless bounded body bytes, and the metadata observed on the response headers.
 *
 * <p>The body stays raw on purpose — the structured projection of web results is a separate
 * concern fed from these bytes — so no parsing failure can lose or alter what the upstream
 * sent. {@code rateLimits} is never null (an exchange without rate-limit headers carries an
 * empty snapshot); {@code usage}, {@code requestId}, and {@code apiVersion} are null unless
 * the exchange's response headers offered them. The record implements {@link PagedExchange}
 * — the interface is exactly its member set — so the shared pagination machinery accepts it
 * without adaptation.
 */
public record WebSearchResult(
        int httpStatus,
        UpstreamPayload body,
        RateLimitSnapshot rateLimits,
        Usage usage,
        String requestId,
        String apiVersion) implements PagedExchange {

    public WebSearchResult {
        Objects.requireNonNull(body, "body");
        rateLimits = rateLimits == null ? RateLimitSnapshot.empty() : rateLimits;
    }
}

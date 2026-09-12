package io.amscotti.bravesearch.domain.result;

import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.metadata.Usage;
import java.util.Objects;

/**
 * One completed rich callback exchange as the application sees it: the HTTP status, the
 * lossless bounded body bytes, and the metadata observed on the response headers.
 *
 * <p>The body stays raw on purpose — the upstream response shape of the rich endpoint is
 * undocumented, so the projection of vertical blocks is a separate, tolerant concern fed
 * from these bytes and no parsing decision can lose or alter what the upstream sent.
 * {@code rateLimits} is never null (an exchange without rate-limit headers carries an
 * empty snapshot); {@code usage}, {@code requestId}, and {@code apiVersion} are null
 * unless the exchange's response headers offered them. The {@link PagedExchange}
 * declaration is consumed by the renderer base: it reads this completed-exchange view for
 * the machine documents' exchange metadata, so the rich renderer never repeats it.
 */
public record RichResult(
        int httpStatus,
        UpstreamPayload body,
        RateLimitSnapshot rateLimits,
        Usage usage,
        String requestId,
        String apiVersion) implements PagedExchange {

    public RichResult {
        Objects.requireNonNull(body, "body");
        rateLimits = rateLimits == null ? RateLimitSnapshot.empty() : rateLimits;
    }
}

package io.amscotti.bravesearch.domain.result;

import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.metadata.Usage;
import java.util.Objects;

/**
 * One completed Answers chat-completions exchange as the application sees it: the HTTP
 * status, the lossless bounded body bytes, and the metadata observed on the response
 * headers.
 *
 * <p>The body stays raw on purpose — the structured projection of the answer document is
 * a separate, tolerant concern fed from these bytes — so no parsing failure can lose or
 * alter what the upstream sent. {@code rateLimits} is never null (an exchange without
 * rate-limit headers carries an empty snapshot); {@code usage} is null unless the
 * exchange carried the Answers {@code X-Request-*} usage headers, and {@code requestId}
 * and {@code apiVersion} are null unless the response headers offered them. The {@link
 * PagedExchange} declaration is consumed by the renderer base: it reads this
 * completed-exchange view for the machine documents' exchange metadata.
 */
public record AnswersResult(
        int httpStatus,
        UpstreamPayload body,
        RateLimitSnapshot rateLimits,
        Usage usage,
        String requestId,
        String apiVersion) implements PagedExchange {

    public AnswersResult {
        Objects.requireNonNull(body, "body");
        rateLimits = rateLimits == null ? RateLimitSnapshot.empty() : rateLimits;
    }
}

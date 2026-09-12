package io.amscotti.bravesearch.domain.metadata;

import java.util.List;

/**
 * Metadata of one upstream exchange.
 *
 * <p>{@code httpStatus} is {@code 0} when the status is unknown; {@code requestId},
 * {@code apiVersion}, and {@code usage} are {@code null} when absent. The rate-limit list is
 * copied on construction and never {@code null}.
 */
public record RequestMeta(
        String requestId, int httpStatus, String apiVersion, List<RateLimitWindow> rateLimits, Usage usage) {

    public RequestMeta {
        rateLimits = rateLimits == null ? List.of() : List.copyOf(rateLimits);
    }
}

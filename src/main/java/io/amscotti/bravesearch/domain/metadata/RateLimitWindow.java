package io.amscotti.bravesearch.domain.metadata;

import java.time.Duration;
import java.util.Objects;

/**
 * One upstream quota window.
 *
 * <p>A {@code limit} of {@code 0} means the quota is unlimited, not exhausted; upstream
 * declarations of unlimited capacity keep this shape so consumers never conflate the two. The
 * reset duration is required and never negative: its upstream wire form, the {@code
 * X-RateLimit-Reset} header, is a nonnegative whole-second count, and {@code reset_ms} is the
 * envelope's whole-millisecond rendering of this duration — neither form can carry a negative
 * value. The upstream metadata parser drops malformed windows with a note, so a negative
 * duration reaching construction is a bug, not an input.
 */
public record RateLimitWindow(String policy, long limit, long remaining, Duration reset) {

    public RateLimitWindow {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(reset, "reset");
        if (reset.isNegative()) {
            throw new IllegalArgumentException("rate-limit reset must not be negative: " + reset);
        }
    }
}

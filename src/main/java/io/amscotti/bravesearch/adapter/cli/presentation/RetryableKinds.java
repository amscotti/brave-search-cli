package io.amscotti.bravesearch.adapter.cli.presentation;

import io.amscotti.bravesearch.domain.error.FailureKind;
import java.util.Objects;

/**
 * The retryability answer of the machine failure documents: a failure is retryable exactly
 * when it is rate limited, the one category the upstream contract describes as potentially
 * retryable. The flag informs consumers; this CLI itself never retries.
 */
public final class RetryableKinds {

    private RetryableKinds() {}

    /** Whether {@code kind} promises that a repeat attempt can help. */
    public static boolean isRetryable(FailureKind kind) {
        Objects.requireNonNull(kind, "kind");
        return kind == FailureKind.RATE_LIMITED;
    }
}

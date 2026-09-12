package io.amscotti.bravesearch.adapter.cli.presentation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.domain.error.FailureKind;
import org.junit.jupiter.api.Test;

/**
 * The wire retryability flag of a failure: exactly the rate-limit category, because that is
 * the only failure this CLI's contract documents as potentially retryable — every other
 * category either persists on its own terms (credentials, usage) or carries no promise that
 * repeating it helps.
 */
final class RetryableKindsTest {

    @Test
    void onlyARateLimitedFailureIsRetryable() {
        assertTrue(RetryableKinds.isRetryable(FailureKind.RATE_LIMITED));
    }

    @Test
    void everyOtherCategoryIsNotRetryable() {
        for (FailureKind kind : FailureKind.values()) {
            if (kind == FailureKind.RATE_LIMITED) {
                continue;
            }
            assertFalse(RetryableKinds.isRetryable(kind), () -> kind + " must not promise retryability");
        }
    }
}

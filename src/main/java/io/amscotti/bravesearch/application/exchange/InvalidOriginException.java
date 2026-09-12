package io.amscotti.bravesearch.application.exchange;

import io.amscotti.bravesearch.domain.error.FailureKind;

/**
 * A base-URL override that violates the loopback origin contract.
 *
 * <p>The message names the violated rule and never repeats the rejected input, because that
 * text may itself carry user information. Every origin rejection is a usage failure, which the
 * {@link #kind()} accessor makes explicit for the exit-status mapping.
 */
public final class InvalidOriginException extends IllegalArgumentException {

    public InvalidOriginException(String violatedRule) {
        super(violatedRule);
    }

    /** The failure category of every origin rejection. */
    public FailureKind kind() {
        return FailureKind.USAGE;
    }
}

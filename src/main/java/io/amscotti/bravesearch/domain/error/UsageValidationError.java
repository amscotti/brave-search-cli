package io.amscotti.bravesearch.domain.error;

/**
 * A request that violates a documented usage rule of an endpoint option.
 *
 * <p>The message names the violated rule only and never repeats rejected input, because
 * usage text may quote arbitrary query content. Every usage rejection carries {@link
 * FailureKind#USAGE} through {@link #kind()} so the exit-status mapping needs no instanceof
 * ladder. Validation boundaries throw this type — domain request constructors and the
 * option parsing that feeds them — while upstream disagreement stays inside the {@code
 * Outcome} envelope, which models exchange failures instead.
 */
public final class UsageValidationError extends RuntimeException {

    public UsageValidationError(String violatedRule) {
        super(violatedRule);
    }

    /** The failure category of every usage rejection. */
    public FailureKind kind() {
        return FailureKind.USAGE;
    }
}

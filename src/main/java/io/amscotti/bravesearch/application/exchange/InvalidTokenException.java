package io.amscotti.bravesearch.application.exchange;

import io.amscotti.bravesearch.domain.error.FailureKind;

/**
 * A subscription token whose characters cannot travel in an HTTP header value.
 *
 * <p>The message names the violated rule and never repeats the rejected token, because that
 * text is credential material. Every token rejection is a local-configuration failure — the
 * stored or supplied key itself cannot ride the one header every exchange carries — which
 * the {@link #kind()} accessor makes explicit for the exit-status mapping.
 */
public final class InvalidTokenException extends IllegalArgumentException {

    public InvalidTokenException(String violatedRule) {
        super(violatedRule);
    }

    /** The failure category of every token rejection. */
    public FailureKind kind() {
        return FailureKind.LOCAL_CONFIG;
    }
}

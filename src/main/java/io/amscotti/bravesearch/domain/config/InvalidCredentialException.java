package io.amscotti.bravesearch.domain.config;

import java.util.Objects;

/**
 * A token rejected by {@link Credential#of(byte[])}: the message names only the violated
 * invariant and never includes token material, so the failure can surface in any diagnostic
 * channel.
 */
public final class InvalidCredentialException extends RuntimeException {

    private final String reason;

    public InvalidCredentialException(String reason) {
        super("invalid credential: " + Objects.requireNonNull(reason, "reason"));
        this.reason = reason;
    }

    /** The violated invariant, free of token material. */
    public String reason() {
        return reason;
    }
}

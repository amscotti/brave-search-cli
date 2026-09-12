package io.amscotti.bravesearch.adapter.bravehttp.error;

import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import java.io.IOException;
import java.util.Objects;

/**
 * Maps a transport-level failure — a refused or unreachable connection, an unknown host, a TLS
 * break, any other I/O failure, or a headers-phase timeout — onto the expected failure
 * envelope.
 *
 * <p>The diagnostic deliberately carries only the failure category and the exception's class
 * name: exception messages are outside this code base's control and some embed the full
 * request URI (query values included) or header material, so none of the message text is
 * trusted on any diagnostic channel. The kind is always {@link FailureKind#TRANSPORT}.
 */
public final class TransportFailureMapping {

    private TransportFailureMapping() {}

    /** The redacted TRANSPORT failure of the given transport-level cause. */
    public static <T> Outcome.Failure<T> of(IOException cause) {
        Objects.requireNonNull(cause, "cause");
        return new Outcome.Failure<>(
                FailureKind.TRANSPORT, "upstream transport failure: " + cause.getClass().getSimpleName());
    }
}

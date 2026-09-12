package io.amscotti.bravesearch.application.stream;

import io.amscotti.bravesearch.domain.error.ClassifiedFailure;
import io.amscotti.bravesearch.domain.error.FailureKind;

/**
 * The streaming body broke before its terminator arrived — a truncated or reset connection
 * mid-body rather than a well-formed end. It is typed apart from every other transport
 * failure because the accounting that follows a run must treat it specially: an abrupt
 * ending leaves the run's usage and cost facts unknown, never absent-by-contract.
 *
 * <p>The diagnostic is content-free by construction: the fixed message names the condition
 * and never quotes peer text, because wire detail is untrusted input.
 */
public final class AbruptEofException extends RuntimeException implements ClassifiedFailure {

    public AbruptEofException(String message, Throwable cause) {
        super(message, cause);
    }

    /** A broken body is a transport failure: the peer's connection ended, not its content. */
    @Override
    public FailureKind kind() {
        return FailureKind.TRANSPORT;
    }
}

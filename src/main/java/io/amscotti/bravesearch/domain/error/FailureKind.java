package io.amscotti.bravesearch.domain.error;

/** Expected failure categories shared by outcomes and the CLI exit mapping. */
public enum FailureKind {
    USAGE,
    LOCAL_CONFIG,
    AUTHENTICATION,
    RATE_LIMITED,
    TRANSPORT,
    UPSTREAM,
    MALFORMED,
    INTERNAL
}

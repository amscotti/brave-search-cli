package io.amscotti.bravesearch.adapter.cli.presentation.json;

import io.amscotti.bravesearch.domain.error.FailureKind;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** The stable wire error-code string of every failure category. */
public final class ErrorCodes {

    /**
     * The code of user interruption in the streaming family's terminal records: SIGINT ends
     * a run rather than failing an exchange, so it has no failure category — its documents
     * still need the one stable spelling.
     */
    public static final String INTERRUPTED = "INTERRUPTED";

    /** The code of user termination in the streaming family's terminal records: SIGTERM's spelling. */
    public static final String TERMINATED = "TERMINATED";

    private static final Map<FailureKind, String> CODES = buildCodes();

    private ErrorCodes() {}

    private static Map<FailureKind, String> buildCodes() {
        Map<FailureKind, String> codes = new EnumMap<>(FailureKind.class);
        codes.put(FailureKind.USAGE, "USAGE_ERROR");
        codes.put(FailureKind.LOCAL_CONFIG, "LOCAL_CONFIG_ERROR");
        codes.put(FailureKind.AUTHENTICATION, "AUTHENTICATION_FAILED");
        codes.put(FailureKind.RATE_LIMITED, "RATE_LIMITED");
        codes.put(FailureKind.TRANSPORT, "TRANSPORT_ERROR");
        codes.put(FailureKind.UPSTREAM, "UPSTREAM_ERROR");
        codes.put(FailureKind.MALFORMED, "MALFORMED_RESPONSE");
        codes.put(FailureKind.INTERNAL, "INTERNAL_ERROR");
        return Collections.unmodifiableMap(codes);
    }

    /** The exact error {@code code} string of {@code kind}; every category has exactly one. */
    public static String codeFor(FailureKind kind) {
        return Objects.requireNonNull(CODES.get(Objects.requireNonNull(kind, "kind")), () -> "no code for " + kind);
    }
}

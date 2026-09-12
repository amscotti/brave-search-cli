package io.amscotti.bravesearch.adapter.cli.exit;

import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.FailureSignal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * The stable process exit statuses: one table entry per failure kind plus the special
 * non-failure paths.
 *
 * <p>Success and broken pipe are both {@code 0} because a downstream consumer terminating a
 * stream early is successful early termination, not a failure; SIGINT termination carries the
 * conventional {@code 128 + 2} and SIGTERM the conventional {@code 128 + 15}. Pure function:
 * the mapping is total, side-effect free, and stable within CLI major version 1.
 */
public final class ExitCodeMapper {

    /** Success, including zero results. */
    public static final int SUCCESS = 0;

    /** Terminated by SIGINT. */
    public static final int SIGINT = 130;

    /** Terminated by SIGTERM. */
    public static final int SIGTERM = 143;

    /** Silent success: the downstream consumer closed the pipe early. */
    public static final int BROKEN_PIPE = 0;

    private static final Map<FailureKind, Integer> KIND_EXIT_CODES = kindExitCodes();

    private ExitCodeMapper() {}

    /** The exit status of {@code kind}; every failure kind has exactly one. */
    public static int forKind(FailureKind kind) {
        return Objects.requireNonNull(
                KIND_EXIT_CODES.get(Objects.requireNonNull(kind, "kind")), () -> "no exit code for " + kind);
    }

    /** The exit status of {@code signal}, with user interruption and termination mapped to their signal statuses. */
    public static int forSignal(FailureSignal signal) {
        Objects.requireNonNull(signal, "signal");
        return switch (signal) {
            case INTERRUPTED -> SIGINT;
            case TERMINATED -> SIGTERM;
            default -> forKind(signal.kind());
        };
    }

    private static Map<FailureKind, Integer> kindExitCodes() {
        Map<FailureKind, Integer> codes = new EnumMap<>(FailureKind.class);
        codes.put(FailureKind.USAGE, 2);
        codes.put(FailureKind.LOCAL_CONFIG, 3);
        codes.put(FailureKind.AUTHENTICATION, 4);
        codes.put(FailureKind.RATE_LIMITED, 5);
        codes.put(FailureKind.TRANSPORT, 6);
        codes.put(FailureKind.UPSTREAM, 7);
        codes.put(FailureKind.MALFORMED, 8);
        codes.put(FailureKind.INTERNAL, 70);
        return Collections.unmodifiableMap(codes);
    }
}

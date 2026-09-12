package io.amscotti.bravesearch.domain.error;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Failure conditions in the fixed resolution order that decides a single exit status:
 * declaration order is precedence order, highest first.
 *
 * <p>Every {@link FailureKind} has exactly one signal; user interruption and user termination
 * are the two extra conditions because each ends the run rather than failing an exchange, so
 * they carry no kind. Shared by the CLI exit mapping so simultaneous conditions resolve
 * identically everywhere.
 */
public enum FailureSignal {
    USAGE(FailureKind.USAGE),
    LOCAL_CONFIG(FailureKind.LOCAL_CONFIG),
    INTERRUPTED(null),
    TERMINATED(null),
    TRANSPORT(FailureKind.TRANSPORT),
    MALFORMED(FailureKind.MALFORMED),
    AUTHENTICATION(FailureKind.AUTHENTICATION),
    RATE_LIMITED(FailureKind.RATE_LIMITED),
    UPSTREAM(FailureKind.UPSTREAM),
    INTERNAL(FailureKind.INTERNAL);

    private static final Map<FailureKind, FailureSignal> SIGNALS_BY_KIND = signalsByKind();

    private final FailureKind kind;

    FailureSignal(FailureKind kind) {
        this.kind = kind;
    }

    /** The failure category of this signal, or {@code null} for user interruption or termination. */
    public FailureKind kind() {
        return kind;
    }

    /** The signal of {@code kind}; every kind has exactly one. */
    public static FailureSignal fromKind(FailureKind kind) {
        return Objects.requireNonNull(
                SIGNALS_BY_KIND.get(Objects.requireNonNull(kind, "kind")), () -> "no signal for " + kind);
    }

    private static Map<FailureKind, FailureSignal> signalsByKind() {
        Map<FailureKind, FailureSignal> signals = new EnumMap<>(FailureKind.class);
        for (FailureSignal signal : values()) {
            if (signal.kind != null) {
                signals.put(signal.kind, signal);
            }
        }
        return Collections.unmodifiableMap(signals);
    }
}

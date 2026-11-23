package io.amscotti.bravesearch.adapter.config;

import java.util.Objects;
import java.util.function.Function;

/**
 * The opt-in of the internal-error trace. By default an unexpected internal error reports one
 * redacted line and nothing else, because an exception's message can quote user input or
 * credential material. A process that sets the debug variable asks for a trace of frames and
 * exception types on top of that line — where the failure happened, never what it said — which
 * is what a useful bug report needs.
 *
 * <p>Only an explicit, affirmative value opts in: anything nonblank except {@code 0} and
 * {@code false}. An inherited empty or refusing setting keeps the redacted report.
 */
public final class DebugTrace {

    /** The environment variable that opts a process into message-free internal-error traces. */
    public static final String VARIABLE = "BRAVE_SEARCH_DEBUG";

    private DebugTrace() {}

    /**
     * Whether the process asked for internal-error traces.
     *
     * @throws NullPointerException when {@code environmentLookup} is null
     */
    public static boolean requested(Function<String, String> environmentLookup) {
        String value = Objects.requireNonNull(environmentLookup, "environmentLookup").apply(VARIABLE);
        if (value == null) {
            return false;
        }
        String normalized = value.strip();
        return !normalized.isEmpty() && !"0".equals(normalized) && !"false".equalsIgnoreCase(normalized);
    }
}

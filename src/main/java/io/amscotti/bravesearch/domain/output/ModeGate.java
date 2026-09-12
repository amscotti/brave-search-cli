package io.amscotti.bravesearch.domain.output;

import java.util.Objects;
import java.util.Optional;

/**
 * Records whether an output mode was successfully parsed, so failure reporting knows whose
 * contract applies: before a mode parsed, failures are human diagnostics on stderr only —
 * no machine document may exist yet; once a mode parsed, that mode's own documented failure
 * contract applies.
 *
 * <p>Immutable snapshot: the gate is closed at invocation start and replaced by an open gate
 * at the moment of successful parse, so late failures cannot retroactively claim a machine
 * contract that did not exist when the failure happened.
 */
public record ModeGate(Optional<OutputMode> parsedMode) {

    public ModeGate {
        Objects.requireNonNull(parsedMode, "parsedMode");
    }

    /** Whether an output mode was successfully parsed. */
    public boolean isParsed() {
        return parsedMode.isPresent();
    }

    /**
     * The parsed mode.
     *
     * @throws IllegalStateException if no mode was parsed yet
     */
    public OutputMode mode() {
        return parsedMode.orElseThrow(() -> new IllegalStateException("no output mode has been parsed"));
    }

    /**
     * Whether the parsed mode owns failure reporting (its documented failure contract
     * applies); {@code false} means failures still belong to human stderr diagnostics.
     */
    public boolean modeOwnsFailureReporting() {
        return isParsed();
    }
}

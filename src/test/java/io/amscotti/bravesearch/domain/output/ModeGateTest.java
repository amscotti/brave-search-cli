package io.amscotti.bravesearch.domain.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Failure-reporting ownership across the parse boundary: before an output mode parsed,
 * failures belong to human diagnostics on stderr; after a successful parse, the parsed mode
 * owns its documented failure contract.
 */
final class ModeGateTest {

    @Test
    void failuresBeforeModeParsingStayOnHumanDiagnostics() {
        ModeGate gate = new ModeGate(Optional.empty());
        assertFalse(gate.isParsed(), "a fresh invocation has not parsed an output mode yet");
        assertFalse(
                gate.modeOwnsFailureReporting(),
                "an unparsed run must report failures as human diagnostics, never as a machine document");
        assertThrows(IllegalStateException.class, gate::mode, "no mode exists before parsing");
    }

    @Test
    void parsedModeOwnsFailureReporting() {
        for (OutputMode mode : OutputMode.values()) {
            ModeGate gate = new ModeGate(Optional.of(mode));
            assertTrue(gate.isParsed(), () -> mode + " was successfully parsed");
            assertTrue(
                    gate.modeOwnsFailureReporting(),
                    () -> "once " + mode + " is parsed, its own failure contract applies");
            assertEquals(mode, gate.mode());
        }
    }
}

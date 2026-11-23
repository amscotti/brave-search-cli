package io.amscotti.bravesearch.adapter.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The opt-in of the internal-error trace: only an explicit, nonblank, non-negating value of
 * the debug variable asks for traces, so an inherited empty or refusing setting keeps the
 * redacted one-line report.
 */
final class DebugTraceTest {

    @Test
    void onlyAnExplicitAffirmativeValueAsksForTraces() {
        assertFalse(DebugTrace.requested(name -> null), "an unset variable keeps the redacted report");
        assertFalse(DebugTrace.requested(name -> ""), "an empty value opts into nothing");
        assertFalse(DebugTrace.requested(name -> "   "), "a blank value opts into nothing");
        assertFalse(DebugTrace.requested(name -> "0"), "zero refuses");
        assertFalse(DebugTrace.requested(name -> "FALSE"), "false refuses, whatever its case");
        assertTrue(DebugTrace.requested(name -> "1"), "one opts in");
        assertTrue(DebugTrace.requested(name -> " true "), "true opts in, blanks and case aside");
        assertTrue(DebugTrace.requested(name -> "yes"), "any other nonblank value opts in");
    }

    @Test
    void theVariableIsReadByItsOneNameThroughTheInjectedLookup() {
        Map<String, String> seen = new java.util.HashMap<>();

        DebugTrace.requested(name -> {
            seen.put(name, "1");
            return "1";
        });

        assertTrue(seen.containsKey("BRAVE_SEARCH_DEBUG"), "the documented name is the one read: " + seen.keySet());
        assertThrows(NullPointerException.class, () -> DebugTrace.requested(null));
    }
}

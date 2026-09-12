package io.amscotti.bravesearch.domain.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * The render width of one invocation's output state: 100 columns unless a wider or
 * narrower sanctioned value was supplied, bounded to the documented 40..500 range at
 * construction, because a width outside that range can only be a wiring defect, never a
 * terminal observation.
 */
final class OutputRequestWidthTest {

    @Test
    void theWidthDefaultsToOneHundredColumns() {
        assertEquals(100, new OutputRequest(false, OutputMode.HUMAN, false, false, false).width());
        assertEquals(100, new OutputRequest(false, OutputMode.HUMAN, false, false, false, true).width());
    }

    @Test
    void anExplicitWidthTravelsThroughUntouched() {
        assertEquals(40, new OutputRequest(false, OutputMode.HUMAN, false, false, false, false, 40).width());
        assertEquals(500, new OutputRequest(false, OutputMode.HUMAN, false, false, false, false, 500).width());
        assertEquals(120, new OutputRequest(false, OutputMode.HUMAN, false, false, false, false, 120).width());
    }

    @Test
    void widthsBeyondTheDocumentedRangeAreRejectedAtConstruction() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new OutputRequest(false, OutputMode.HUMAN, false, false, false, false, 39));
        assertThrows(
                IllegalArgumentException.class,
                () -> new OutputRequest(false, OutputMode.HUMAN, false, false, false, false, 501));
    }
}

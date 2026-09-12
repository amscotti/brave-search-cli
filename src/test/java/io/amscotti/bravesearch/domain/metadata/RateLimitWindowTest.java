package io.amscotti.bravesearch.domain.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Construction contracts of one quota window: the reset duration is required and never
 * negative, because the upstream reset header is a nonnegative whole-second count and the
 * envelope renders whole milliseconds — neither form can carry a negative.
 */
final class RateLimitWindowTest {

    @Test
    void negativeResetDurationsAreRejectedAtConstruction() {
        for (Duration negative : new Duration[] {Duration.ofMillis(-1), Duration.ofSeconds(-5), Duration.ofNanos(-1)}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new RateLimitWindow("request", 1, 0, negative),
                    () -> "a negative reset must fail fast: " + negative);
        }
    }

    @Test
    void zeroResetDurationsRemainValid() {
        RateLimitWindow window = new RateLimitWindow("request", 1, 0, Duration.ZERO);
        assertEquals(Duration.ZERO, window.reset());
    }
}

package io.amscotti.bravesearch.application.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Progress marking of the body-stream wrapper: every delivered byte refreshes the shared
 * activity instant, while the end of the stream leaves it untouched.
 */
final class ProgressMarkingStreamTest {

    private static final Instant START = Instant.ofEpochSecond(1_700_000_000);
    private static final Instant TICK = START.plusSeconds(30);

    @Test
    void singleByteReadsMarkProgress() throws Exception {
        AtomicReference<Instant> lastActivity = new AtomicReference<>(START);
        Clock clock = Clock.fixed(TICK, ZoneOffset.UTC);

        try (ProgressMarkingStream body =
                new ProgressMarkingStream(new ByteArrayInputStream(new byte[] {7}), lastActivity, clock)) {
            assertEquals(7, body.read());
        }

        assertEquals(TICK, lastActivity.get(), "a delivered byte refreshes the shared activity instant");
    }

    @Test
    void endOfStreamLeavesTheActivityInstantUntouched() throws Exception {
        AtomicReference<Instant> lastActivity = new AtomicReference<>(START);
        Clock clock = Clock.fixed(TICK, ZoneOffset.UTC);

        int terminal;
        try (ProgressMarkingStream body =
                new ProgressMarkingStream(new ByteArrayInputStream(new byte[0]), lastActivity, clock)) {
            terminal = body.read();
        }

        assertEquals(-1, terminal);
        assertEquals(START, lastActivity.get(), "no byte arrived, so the idle window keeps its mark");
    }
}

package io.amscotti.bravesearch.application.stream;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A body stream that marks a shared progress instant on every decoded byte it delivers, so a
 * {@link DeadlineWatchdog} watching that reference sees the idle window reset exactly when
 * bytes actually arrive — the semantics the streaming run's idle deadline is defined by.
 *
 * <p>Like the body publisher it feeds, this wrapper is transport-independent — any blocking
 * {@link InputStream} works — which is why it lives beside the deadline machinery rather than
 * beside a specific HTTP client.
 */
public final class ProgressMarkingStream extends FilterInputStream {

    private final AtomicReference<Instant> lastActivity;

    private final Clock clock;

    public ProgressMarkingStream(InputStream body, AtomicReference<Instant> lastActivity, Clock clock) {
        super(Objects.requireNonNull(body, "body"));
        this.lastActivity = Objects.requireNonNull(lastActivity, "lastActivity");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int read = super.read(buffer, offset, length);
        if (read > 0) {
            lastActivity.set(clock.instant());
        }
        return read;
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value >= 0) {
            lastActivity.set(clock.instant());
        }
        return value;
    }
}

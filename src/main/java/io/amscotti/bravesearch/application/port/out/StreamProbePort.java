package io.amscotti.bravesearch.application.port.out;

import io.amscotti.bravesearch.application.stream.CancellationContext;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Flow;

/**
 * Outbound port for opening one streaming exchange against a URL, used by the streaming
 * lifecycle probe and, later, by endpoint streams that share the same shape.
 *
 * <p>The caller owns the run's {@link CancellationContext}: it creates it, registers it with the
 * process-wide registry while the exchange is open, and derives the run's exit code from its
 * terminal cause. The implementation owns the transport and hands back only application-visible
 * types: the body as a {@code byte[]} publisher and one release action that ends the exchange on
 * every path.
 */
public interface StreamProbePort {

    /**
     * Opens a streaming GET exchange for {@code url}.
     *
     * @param url destination of the stream
     * @param cancellation terminal-cause latch of this run; deadlines inside the returned stream
     *                     race other causes on it
     * @param idleTimeout maximum silence between delivered bytes, or {@code null} for no idle
     *                    limit
     * @param wallTimeout total budget for the stream measured from this call, or {@code null}
     *                    for no wall limit
     * @return the open stream; must be closed on every path
     * @throws IOException when the exchange cannot be opened or answers with a failure status
     */
    ProbeStream open(URI url, CancellationContext cancellation, Duration idleTimeout, Duration wallTimeout)
            throws IOException, InterruptedException;

    /** One open streaming exchange: its published bytes and the action that ends it. */
    record ProbeStream(Flow.Publisher<byte[]> bytes, Runnable release) implements AutoCloseable {

        public ProbeStream {
            Objects.requireNonNull(bytes, "bytes");
            Objects.requireNonNull(release, "release");
        }

        /** Ends the exchange: releases the body, its reader, and the transport. */
        @Override
        public void close() {
            release.run();
        }
    }
}

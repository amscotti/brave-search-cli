package io.amscotti.bravesearch.adapter.bravehttp;

import io.amscotti.bravesearch.application.exchange.ExchangeCancelledException;
import io.amscotti.bravesearch.application.exchange.ResponseRejectionException;
import io.amscotti.bravesearch.application.exchange.TotalDeadlineExceededException;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The wire-side bound of every response body: the bounded reader over the response stream the
 * JDK client hands out, counting the bytes actually delivered — an advertised
 * {@code Content-Length} is never consulted, so a lying or unframed peer cannot smuggle volume
 * past the counter — and closing the stream the moment one byte more than the applicable
 * ceiling arrives. Closing that stream cancels the underlying body subscription and severs the
 * connection, so an oversized or endless peer stops being read immediately and no partial
 * non-stream body flows onward.
 *
 * <p>The applicable ceiling and its breach diagnostic are chosen by the caller — a 2xx with
 * {@code Content-Encoding: gzip} counts wire bytes against the compressed ceiling (the
 * decoded ceiling is enforced later, while inflating, by {@link ContentEncodingDecoder}); a
 * 2xx identity body counts its bytes exactly once against the decoded ceiling; any non-2xx
 * status counts against the structured error ceiling in whatever form the body arrives.
 *
 * <p>An optional total deadline bounds the read itself: the loop checks the clock between
 * deliveries and a daemon watchdog closes the stream at the deadline, so a peer that
 * trickles or withholds body bytes cannot hold the exchange past its total budget. A read
 * the watchdog ends surfaces as {@link TotalDeadlineExceededException}, never as a clean
 * end of body.
 *
 * <p>An optional cancellation latch owns the same closing power as the deadline: the moment
 * the run's context latches — a SIGINT or SIGTERM reaching a live exchange — a watcher closes
 * the stream, unblocking a parked read, and the read surfaces as {@link
 * ExchangeCancelledException} on every path, so a signalled exchange ends promptly instead
 * of waiting out its budget.
 */
public final class BoundedBodyHandler {

    private static final int CHUNK = 8 * 1024;

    private BoundedBodyHandler() {}

    /**
     * Reads the whole response body, refusing to deliver one byte more than {@code limit}.
     *
     * @throws ResponseRejectionException when the delivered bytes exceed {@code limit}; the
     *     stream is closed — and the subscription cancelled — before the rejection surfaces
     * @throws IOException when the stream breaks while being read, mapped by the caller to a
     *     transport failure
     * @throws NullPointerException when the stream or diagnostic is null
     */
    public static byte[] readBounded(InputStream body, long limit, String breachDiagnostic) throws IOException {
        return readBounded(body, limit, breachDiagnostic, null, null, null);
    }

    /**
     * Reads at most {@code limit} bytes of the response body — a bounded preview of a body
     * whose verdict is already decided, never a whole read — under an optional total deadline
     * and an optional cancellation latch: whichever fires first closes the stream, and the
     * preview ends with the bytes delivered so far instead of throwing, because a preview
     * only decorates a refusal that stands on its own.
     *
     * @throws NullPointerException when the stream is null, or when the clock is null while
     *     a deadline is present
     */
    public static byte[] preview(
            InputStream body, int limit, Clock clock, Instant totalDeadline, CancellationContext cancellation) {
        Objects.requireNonNull(body, "body");
        if (limit <= 0) {
            closeQuietly(body);
            return new byte[0];
        }
        if (totalDeadline == null && cancellation == null) {
            try (InputStream stream = body) {
                return stream.readNBytes(limit);
            } catch (IOException unreadable) {
                return new byte[0];
            }
        }
        Objects.requireNonNull(clock, "clock");
        // one flag both guards set: either firing ends the preview, whichever kind it was
        AtomicBoolean ended = new AtomicBoolean();
        Thread watchdog = totalDeadline == null ? null : deadlineWatchdog(body, clock, totalDeadline, ended);
        Thread watcher = cancellation == null ? null : cancellationWatcher(body, cancellation, ended);
        if (watchdog != null) {
            watchdog.start();
        }
        if (watcher != null) {
            watcher.start();
        }
        try {
            ByteArrayOutputStream buffered = new ByteArrayOutputStream();
            byte[] chunk = new byte[Math.min(CHUNK, limit)];
            while (buffered.size() < limit) {
                if (ended.get()) {
                    break;
                }
                int read;
                try {
                    read = body.read(chunk, 0, Math.min(chunk.length, limit - buffered.size()));
                } catch (IOException broken) {
                    // the guards close the stream to end a parked preview read; the bytes
                    // delivered so far are the preview
                    break;
                }
                if (read < 0) {
                    break;
                }
                buffered.write(chunk, 0, read);
            }
            return buffered.toByteArray();
        } finally {
            closeQuietly(body);
            if (watchdog != null) {
                watchdog.interrupt();
            }
            if (watcher != null) {
                watcher.interrupt();
            }
        }
    }

    /**
     * Reads the whole response body under an optional total deadline.
     *
     * @throws TotalDeadlineExceededException when the deadline passes before the body
     *     completes; the stream is closed, severing the peer
     * @throws ResponseRejectionException when the delivered bytes exceed {@code limit}
     * @throws IOException when the stream breaks while being read for any reason other than
     *     the watchdog closing it at the deadline
     * @throws NullPointerException when the stream or diagnostic is null, or when the clock
     *     is null while a deadline is present
     */
    public static byte[] readBounded(
            InputStream body, long limit, String breachDiagnostic, Clock clock, Instant totalDeadline)
            throws IOException {
        return readBounded(body, limit, breachDiagnostic, clock, totalDeadline, null);
    }

    /**
     * Reads the whole response body under an optional total deadline and an optional
     * cancellation latch: whichever of the two fires first closes the stream and decides the
     * typed failure the read surfaces as.
     *
     * @throws ExchangeCancelledException when the cancellation latch fires before the body
     *     completes; the stream is closed, severing the peer
     * @throws TotalDeadlineExceededException when the deadline passes before the body
     *     completes; the stream is closed, severing the peer
     * @throws ResponseRejectionException when the delivered bytes exceed {@code limit}
     * @throws IOException when the stream breaks while being read for any reason other than
     *     a watchdog closing it
     * @throws NullPointerException when the stream or diagnostic is null, or when the clock
     *     is null while a deadline is present
     */
    public static byte[] readBounded(
            InputStream body,
            long limit,
            String breachDiagnostic,
            Clock clock,
            Instant totalDeadline,
            CancellationContext cancellation)
            throws IOException {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(breachDiagnostic, "breachDiagnostic");
        if (totalDeadline == null && cancellation == null) {
            return readWithoutDeadline(body, limit, breachDiagnostic);
        }
        Objects.requireNonNull(clock, "clock");
        return readGuarded(body, limit, breachDiagnostic, clock, totalDeadline, cancellation);
    }

    private static byte[] readWithoutDeadline(InputStream body, long limit, String breachDiagnostic) throws IOException {
        ByteArrayOutputStream buffered = new ByteArrayOutputStream();
        byte[] chunk = new byte[CHUNK];
        long total = 0;
        // closing the response stream — on every path, success, breach, or break — cancels
        // the body subscription, which is what severs an oversized peer mid-wire
        try (InputStream stream = body) {
            int read;
            while ((read = stream.read(chunk)) != -1) {
                if (total + read > limit) {
                    throw new ResponseRejectionException(breachDiagnostic);
                }
                buffered.write(chunk, 0, read);
                total += read;
            }
        }
        return buffered.toByteArray();
    }

    private static byte[] readGuarded(
            InputStream body,
            long limit,
            String breachDiagnostic,
            Clock clock,
            Instant totalDeadline,
            CancellationContext cancellation)
            throws IOException {
        AtomicBoolean breached = new AtomicBoolean();
        AtomicBoolean cancelled = new AtomicBoolean();
        Thread watchdog = totalDeadline == null ? null : deadlineWatchdog(body, clock, totalDeadline, breached);
        Thread watcher = cancellation == null ? null : cancellationWatcher(body, cancellation, cancelled);
        if (watchdog != null) {
            watchdog.start();
        }
        if (watcher != null) {
            watcher.start();
        }
        try (InputStream ignored = body) {
            ByteArrayOutputStream buffered = new ByteArrayOutputStream();
            byte[] chunk = new byte[CHUNK];
            long total = 0;
            while (true) {
                if (cancelled.get()) {
                    throw new ExchangeCancelledException();
                }
                if (breached.get()) {
                    throw new TotalDeadlineExceededException();
                }
                int read;
                try {
                    read = body.read(chunk);
                } catch (IOException broken) {
                    if (cancelled.get()) {
                        // the watcher closed the stream to end a blocked read
                        throw new ExchangeCancelledException();
                    }
                    if (breached.get()) {
                        // the watchdog closed the stream to end a blocked read
                        throw new TotalDeadlineExceededException();
                    }
                    throw broken;
                }
                if (read == -1) {
                    break;
                }
                if (total + read > limit) {
                    throw new ResponseRejectionException(breachDiagnostic);
                }
                buffered.write(chunk, 0, read);
                total += read;
                if (cancellation != null && cancellation.cancelled()) {
                    cancelled.set(true);
                    throw new ExchangeCancelledException();
                }
                if (totalDeadline != null && !clock.instant().isBefore(totalDeadline)) {
                    breached.set(true);
                    throw new TotalDeadlineExceededException();
                }
            }
            if (cancelled.get()) {
                // a watcher close can surface as a clean end of stream; the latch verdict outranks it
                throw new ExchangeCancelledException();
            }
            if (breached.get()) {
                // a watchdog close can surface as a clean end of stream; the deadline verdict outranks it
                throw new TotalDeadlineExceededException();
            }
            return buffered.toByteArray();
        } finally {
            if (watchdog != null) {
                watchdog.interrupt();
            }
            if (watcher != null) {
                watcher.interrupt();
            }
        }
    }

    private static Thread deadlineWatchdog(
            InputStream body, Clock clock, Instant totalDeadline, AtomicBoolean breached) {
        // virtual threads are daemons by construction
        return Thread.ofVirtual()
                .name("total-deadline-watchdog")
                .unstarted(() -> {
                    Duration remaining = Duration.between(clock.instant(), totalDeadline);
                    if (!remaining.isNegative()) {
                        try {
                            Thread.sleep(remaining);
                        } catch (InterruptedException stoppedEarly) {
                            return;
                        }
                    }
                    breached.set(true);
                    closeQuietly(body);
                });
    }

    /**
     * Watches for the run's first terminal cause, then closes the body stream: a read parked
     * on a withholding peer unblocks as a break the loop reports as the typed cancelled
     * failure — the same closing power the deadline watchdog owns. The watch polls and stops
     * on interrupt, so a completed read never leave a parked thread behind.
     */
    private static Thread cancellationWatcher(InputStream body, CancellationContext cancellation, AtomicBoolean cancelled) {
        // virtual threads are daemons by construction
        return Thread.ofVirtual()
                .name("exchange-cancellation-watcher")
                .unstarted(() -> {
                    while (!cancellation.cancelled()) {
                        try {
                            Thread.sleep(25);
                        } catch (InterruptedException stoppedEarly) {
                            return;
                        }
                    }
                    cancelled.set(true);
                    closeQuietly(body);
                });
    }

    private static void closeQuietly(InputStream body) {
        try {
            body.close();
        } catch (IOException alreadyBroken) {
            // the blocked reader observes the break on its own
        }
    }
}

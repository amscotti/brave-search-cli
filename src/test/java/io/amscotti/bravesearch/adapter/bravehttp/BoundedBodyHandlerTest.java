package io.amscotti.bravesearch.adapter.bravehttp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.application.exchange.ExchangeCancelledException;
import io.amscotti.bravesearch.application.exchange.ResponseRejectionException;
import io.amscotti.bravesearch.application.exchange.TotalDeadlineExceededException;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Wire-side bounds of a response body stream: the bounded reader counts the bytes actually
 * delivered — never an advertised length — and closes the stream the moment one more byte
 * than the applicable limit arrives, which is what cancels the JDK client's body subscription
 * and severs an oversized peer. Limits are injected, so the tests run at byte scale while
 * production uses the documented mebibyte defaults.
 *
 * <p>The guarded read adds two verdicts of its own. A total deadline and a cancellation latch
 * each end the read as their typed failure however the peer behaves — parked, or trickling a
 * byte at a time — and however the closed stream then reports itself: a clean end of stream
 * after a guard fired is still that guard's failure, never a shorter successful body.
 */
final class BoundedBodyHandlerTest {

    @Test
    void aBodyWithinTheLimitIsReadWholeAndTheStreamClosed() throws Exception {
        byte[] body = bytes(1024);

        byte[] read = BoundedBodyHandler.readBounded(new RecordingStream(body), 1024, "response exceeds decoded limit");

        assertArrayEquals(body, read);
    }

    @Test
    void aByteBeyondTheLimitClosesTheStreamBeforeTheRejectionSurfaces() {
        RecordingStream stream = new RecordingStream(bytes(1025));

        ResponseRejectionException rejected = assertThrows(
                ResponseRejectionException.class,
                () -> BoundedBodyHandler.readBounded(stream, 1024, "response exceeds decoded limit"));

        assertEquals("response exceeds decoded limit", rejected.getMessage());
        assertTrue(stream.closedAfterBreach(), "closing the stream cancels the subscription immediately");
    }

    @Test
    void exactlyTheLimitIsAccepted() throws Exception {
        byte[] body = bytes(512);

        assertArrayEquals(
                body, BoundedBodyHandler.readBounded(new RecordingStream(body), 512, "response exceeds decoded limit"));
    }

    @Test
    void anEmptyBodyReadsEmpty() throws Exception {
        assertEquals(
                0,
                BoundedBodyHandler.readBounded(
                                new RecordingStream(new byte[0]), 16, "response exceeds decoded limit")
                        .length);
    }

    @Test
    void chunkedDeliveryStillCountsToTheBreach() {
        RecordingStream chunked = new RecordingStream(bytes(17), 1);

        ResponseRejectionException rejected = assertThrows(
                ResponseRejectionException.class,
                () -> BoundedBodyHandler.readBounded(chunked, 8, "response exceeds decoded limit"));

        assertEquals("response exceeds decoded limit", rejected.getMessage());
        assertTrue(chunked.closedAfterBreach());
    }

    @Test
    void streamBreaksSurfaceAsIoFailuresWithTheStreamClosed() {
        IOException broken = new IOException("connection reset");
        RecordingStream stream = new RecordingStream(broken);

        IOException surfaced = assertThrows(
                IOException.class,
                () -> BoundedBodyHandler.readBounded(stream, 16, "response exceeds decoded limit"));

        assertEquals(broken, surfaced);
        assertTrue(stream.closedAfterBreach(), "a broken stream is still closed, cancelling the subscription");
    }

    @Test
    void aPreviewReadsAtMostTheLimitBytesOfALongerBody() {
        RecordingStream stream = new RecordingStream(bytes(4096));

        byte[] preview = BoundedBodyHandler.preview(stream, 1024, null, null, null);

        assertEquals(1024, preview.length, "the preview stops at its own bound, not the body's");
        assertArrayEquals(bytes(1024), preview);
        assertTrue(stream.closedAfterBreach(), "the preview closes the stream, cancelling the subscription");
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aPreviewEndsWhenTheDeadlineFiresOnAWithholdingPeer() {
        WithholdingStream body = new WithholdingStream();
        Clock arrived = Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC);

        byte[] preview = BoundedBodyHandler.preview(
                body, 1024, arrived, Instant.parse("2026-09-02T00:00:00Z"), null);

        assertEquals(0, preview.length, "a peer that withholds everything previews nothing");
        assertTrue(body.closed(), "the deadline watchdog closed the withheld stream");
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aPreviewEndsWhenTheCancellationLatchFires() throws Exception {
        WithholdingStream body = new WithholdingStream();
        CancellationContext cancellation = new CancellationContext();
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException parked) {
                Thread.currentThread().interrupt();
            }
            cancellation.latch(CancellationContext.Cause.SIGINT);
        });

        byte[] preview = BoundedBodyHandler.preview(body, 1024, Clock.systemUTC(), null, cancellation);

        assertEquals(0, preview.length, "a signalled preview reads nothing further");
        assertTrue(body.closed(), "the cancellation watcher closed the withheld stream");
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aTricklingPeerCannotOutliveTheTotalDeadlineBetweenDeliveries() {
        // a stalled peer is cut by the watchdog while the read is parked; a peer that keeps
        // delivering never parks long enough for that, so the deadline must be noticed here.
        // The watchdog's single look at the clock still sees time before the deadline and
        // keeps sleeping: only the reader's own check between deliveries can end this read
        Instant deadline = Instant.EPOCH.plusSeconds(30);
        TricklingStream body = new TricklingStream(() -> {});

        TotalDeadlineExceededException expired = assertThrows(
                TotalDeadlineExceededException.class,
                () -> BoundedBodyHandler.readBounded(
                        body, 1024, "response exceeds decoded limit", new BudgetPassingClock(Instant.EPOCH, deadline), deadline));

        assertTrue(body.closed(), "the deadline severs the trickling peer");
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aCancellationNoticedBetweenDeliveriesEndsTheReadAsCancelled() {
        CancellationContext cancellation = new CancellationContext();
        TricklingStream body = new TricklingStream(() -> cancellation.latch(CancellationContext.Cause.SIGINT));

        ExchangeCancelledException cancelled = assertThrows(
                ExchangeCancelledException.class,
                () -> BoundedBodyHandler.readBounded(
                        body, 1024, "response exceeds decoded limit", Clock.systemUTC(), null, cancellation));

        assertTrue(body.closed(), "the latch severs the peer");
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aCancellationThatSurfacesAsACleanEndOfStreamIsStillTheCancelledVerdict() throws Exception {
        // closing a JDK response stream can end a parked read with end of stream rather than
        // a break: without the latch's verdict the partial body would pass as a complete success
        CancellationContext cancellation = new CancellationContext();
        PartialThenSilentStream body = new PartialThenSilentStream(bytes(16));
        Thread.ofVirtual().start(() -> {
            try {
                body.awaitParked();
            } catch (InterruptedException parked) {
                Thread.currentThread().interrupt();
                return;
            }
            cancellation.latch(CancellationContext.Cause.SIGINT);
        });

        ExchangeCancelledException cancelled = assertThrows(
                ExchangeCancelledException.class,
                () -> BoundedBodyHandler.readBounded(
                        body, 1024, "response exceeds decoded limit", Clock.systemUTC(), null, cancellation));

        assertTrue(body.closed(), "the watcher closed the stream to end the parked read");
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aDeadlineThatSurfacesAsACleanEndOfStreamIsStillTheDeadlineVerdict() {
        Clock arrived = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        PartialThenSilentStream body = new PartialThenSilentStream(bytes(16));

        TotalDeadlineExceededException expired = assertThrows(
                TotalDeadlineExceededException.class,
                () -> BoundedBodyHandler.readBounded(
                        body, 1024, "response exceeds decoded limit", arrived, Instant.EPOCH.plusMillis(100)));

        assertTrue(body.closed(), "the watchdog closed the stream at the deadline");
    }

    @Test
    void anUnreadableBodyPreviewsEmptyInsteadOfFailingTheRefusalItDecorates() {
        // a preview only adorns a refusal that stands on its own, such as a classified status
        RecordingStream broken = new RecordingStream(new IOException("connection reset"));

        assertArrayEquals(new byte[0], BoundedBodyHandler.preview(broken, 1024, null, null, null));

        assertTrue(broken.closedAfterBreach(), "the unreadable stream is still closed");
    }

    @Test
    void aPreviewWithoutABudgetReadsNothingAndStillClosesTheStream() {
        RecordingStream stream = new RecordingStream(bytes(64));

        assertArrayEquals(new byte[0], BoundedBodyHandler.preview(stream, 0, null, null, null));

        assertEquals(0, stream.served(), "no byte is read");
        assertTrue(stream.closedAfterBreach(), "the stream is still closed, cancelling the subscription");
    }

    private static byte[] bytes(int count) {
        byte[] filled = new byte[count];
        for (int i = 0; i < count; i++) {
            filled[i] = (byte) ('a' + (i % 26));
        }
        return filled;
    }

    /** Serves scripted bytes in configurable chunk sizes and records when it was closed. */
    private static final class RecordingStream extends InputStream {

        private final byte[] data;
        private final int chunkSize;
        private final IOException breaksWith;

        private int position;
        private boolean closed;

        RecordingStream(byte[] data) {
            this(data, Integer.MAX_VALUE, null);
        }

        RecordingStream(byte[] data, int chunkSize) {
            this(data, chunkSize, null);
        }

        RecordingStream(IOException breaksWith) {
            this(new byte[0], Integer.MAX_VALUE, breaksWith);
        }

        private RecordingStream(byte[] data, int chunkSize, IOException breaksWith) {
            this.data = data;
            this.chunkSize = chunkSize;
            this.breaksWith = breaksWith;
        }

        boolean closedAfterBreach() {
            return closed;
        }

        int served() {
            return position;
        }

        @Override
        public int read() {
            if (position >= data.length) {
                return -1;
            }
            return data[position++] & 0xff;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            if (closed) {
                throw new IOException("stream is closed");
            }
            if (breaksWith != null) {
                throw breaksWith;
            }
            if (position >= data.length) {
                return -1;
            }
            int served = Math.min(length, Math.min(chunkSize, data.length - position));
            System.arraycopy(data, position, target, offset, served);
            position += served;
            return served;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** A stream whose reads park until close: the withholding peer of a preview read. */
    private static final class WithholdingStream extends InputStream {

        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read() throws IOException {
            try {
                closed.await();
            } catch (InterruptedException parked) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while parked");
            }
            throw new IOException("closed while parked");
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            return read();
        }

        @Override
        public void close() {
            closed.countDown();
        }

        boolean closed() {
            return closed.getCount() == 0;
        }
    }

    /** Delivers one byte per read and never ends: the peer that keeps a read from ever parking. */
    private static final class TricklingStream extends InputStream {

        private final Runnable afterDelivery;
        private boolean closed;

        TricklingStream(Runnable afterDelivery) {
            this.afterDelivery = afterDelivery;
        }

        boolean closed() {
            return closed;
        }

        @Override
        public int read() throws IOException {
            throw new UnsupportedOperationException("the bounded reader reads in chunks");
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            if (closed) {
                throw new IOException("stream is closed");
            }
            target[offset] = 't';
            afterDelivery.run();
            return 1;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /**
     * Serves a prefix, then parks until closed and reports that close as a clean end of
     * stream: the shape a JDK response stream can take when a guard closes it under a read.
     */
    private static final class PartialThenSilentStream extends InputStream {

        private final byte[] prefix;
        private final CountDownLatch parked = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private boolean prefixServed;

        PartialThenSilentStream(byte[] prefix) {
            this.prefix = prefix;
        }

        void awaitParked() throws InterruptedException {
            parked.await();
        }

        boolean closed() {
            return closed.getCount() == 0;
        }

        @Override
        public int read() throws IOException {
            return read(new byte[1], 0, 1);
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            if (!prefixServed) {
                prefixServed = true;
                int served = Math.min(length, prefix.length);
                System.arraycopy(prefix, 0, target, offset, served);
                return served;
            }
            parked.countDown();
            try {
                closed.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while parked");
            }
            return -1;
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    /**
     * A clock whose first reading still precedes the budget and every later one has passed
     * it, whoever takes the reading — the passage of time a read that outlasts its budget
     * observes, without depending on which thread looks first.
     */
    private static final class BudgetPassingClock extends Clock {

        private final Instant before;
        private final Instant after;
        private final AtomicInteger readings = new AtomicInteger();

        BudgetPassingClock(Instant before, Instant after) {
            this.before = before;
            this.after = after;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return readings.getAndIncrement() == 0 ? before : after;
        }
    }
}

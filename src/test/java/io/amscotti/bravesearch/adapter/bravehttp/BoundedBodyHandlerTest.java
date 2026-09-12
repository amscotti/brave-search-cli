package io.amscotti.bravesearch.adapter.bravehttp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.application.exchange.ResponseRejectionException;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Wire-side bounds of a response body stream: the bounded reader counts the bytes actually
 * delivered — never an advertised length — and closes the stream the moment one more byte
 * than the applicable limit arrives, which is what cancels the JDK client's body subscription
 * and severs an oversized peer. Limits are injected, so the tests run at byte scale while
 * production uses the documented mebibyte defaults.
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
}

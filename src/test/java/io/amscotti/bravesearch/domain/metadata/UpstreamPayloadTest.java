package io.amscotti.bravesearch.domain.metadata;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import org.junit.jupiter.api.Test;

/** Copy and read-only semantics of the bounded decoded upstream body value. */
final class UpstreamPayloadTest {

    @Test
    void constructorCopiesTheCallerArray() {
        byte[] source = {1, 2, 3};
        UpstreamPayload payload = new UpstreamPayload(source);
        source[0] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, payload.toByteArray());
    }

    @Test
    void byteArrayAccessorReturnsAFreshCopyEachCall() {
        UpstreamPayload payload = new UpstreamPayload(new byte[] {1, 2, 3});
        byte[] first = payload.toByteArray();
        first[0] = 9;
        byte[] second = payload.toByteArray();
        assertArrayEquals(new byte[] {1, 2, 3}, second);
        assertNotEquals((byte) 9, second[0]);
    }

    @Test
    void readOnlyBufferRejectsMutationAndReadsEveryByte() {
        UpstreamPayload payload = new UpstreamPayload(new byte[] {1, 2, 3});
        ByteBuffer buffer = payload.asReadOnlyBuffer();
        assertTrue(buffer.isReadOnly());
        assertEquals(0, buffer.position());
        assertEquals(3, buffer.remaining());
        assertThrows(ReadOnlyBufferException.class, () -> buffer.put((byte) 9));
        assertEquals(1, buffer.get());
        assertEquals(2, buffer.get());
        assertEquals(3, buffer.get());
        assertThrows(BufferUnderflowException.class, () -> buffer.get());
    }

    @Test
    void eachReadOnlyBufferIsIndependentOfEarlierOnes() {
        UpstreamPayload payload = new UpstreamPayload(new byte[] {1, 2, 3});
        ByteBuffer first = payload.asReadOnlyBuffer();
        first.get();
        ByteBuffer second = payload.asReadOnlyBuffer();
        assertEquals(0, second.position());
    }

    @Test
    void lengthReportsTheDecodedByteCount() {
        assertEquals(0, new UpstreamPayload(new byte[] {}).length());
        assertEquals(4, new UpstreamPayload(new byte[] {'t', 'r', 'u', 'e'}).length());
    }

    @Test
    void equalityFollowsContentOnly() {
        assertEquals(new UpstreamPayload(new byte[] {1, 2}), new UpstreamPayload(new byte[] {1, 2}));
        assertEquals(
                new UpstreamPayload(new byte[] {1, 2}).hashCode(),
                new UpstreamPayload(new byte[] {1, 2}).hashCode());
        assertNotEquals(new UpstreamPayload(new byte[] {1, 2}), new UpstreamPayload(new byte[] {2, 1}));
        assertNotEquals(new UpstreamPayload(new byte[] {1}), new UpstreamPayload(new byte[] {1, 2}));
    }
}

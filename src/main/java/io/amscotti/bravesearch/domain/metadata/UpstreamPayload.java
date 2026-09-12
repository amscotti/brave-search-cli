package io.amscotti.bravesearch.domain.metadata;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Bounded, decoded upstream body bytes of a non-streaming exchange.
 *
 * <p>The constructor copies the caller's array, and every accessor hands out a fresh read-only or
 * copy view, so the payload can never be mutated through a reference its caller kept.
 */
public final class UpstreamPayload {

    private final byte[] bytes;

    public UpstreamPayload(byte[] bytes) {
        this.bytes = bytes.clone();
    }

    /** The number of decoded bytes held. */
    public int length() {
        return bytes.length;
    }

    /** Fresh read-only view over a copy of the bytes; consuming it never affects later views. */
    public ByteBuffer asReadOnlyBuffer() {
        return ByteBuffer.wrap(bytes.clone()).asReadOnlyBuffer();
    }

    /** Fresh mutable copy of the bytes. */
    public byte[] toByteArray() {
        return bytes.clone();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof UpstreamPayload other && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "UpstreamPayload[bytes=" + bytes.length + "]";
    }
}

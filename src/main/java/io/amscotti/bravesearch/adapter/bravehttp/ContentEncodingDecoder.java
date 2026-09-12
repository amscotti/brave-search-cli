package io.amscotti.bravesearch.adapter.bravehttp;

import io.amscotti.bravesearch.application.exchange.ContentCoding;
import io.amscotti.bravesearch.application.exchange.ResponseRejectionException;
import java.io.ByteArrayOutputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Decoding of the {@code Content-Encoding} response header.
 *
 * <p>The parsed value admits exactly one coding — {@code identity} (including a missing or
 * blank header) or {@code gzip}, each case-insensitively and whitespace-tolerantly — and
 * everything else is unsupported: stacked lists such as {@code "gzip, gzip"}, {@code br},
 * {@code deflate}, or any unknown token. RFC 9110 lets a recipient combine repeated field
 * lines into one value, so more than one {@code Content-Encoding} line is by construction a
 * stacked coding — two {@code gzip} lines never parse as a single gzip — and is rejected the
 * same way. The JDK {@code HttpClient} performs no transparent decompression, so gzip is
 * decoded here, explicitly.
 *
 * <p>The gzip path walks the container itself — header, optional fields, one raw-deflate
 * member, trailer — instead of relying on a buffered stream decoder, so every boundary is
 * exact: truncation anywhere (header, deflate data, trailer) is a typed rejection, a corrupt
 * checksum or length is a typed rejection, reserved header flag bits are a typed rejection,
 * and even a single trailing byte after the member is detected rather than lost inside a
 * decoder's read-ahead buffer. A second concatenated member is trailing bytes, not a
 * continuation. Decoded output is counted against the injected decoded ceiling while
 * inflating, so a decompression bomb aborts at the ceiling without ever being fully
 * materialized; nothing here trusts a compression ratio, and the wire-side compressed ceiling
 * is enforced by {@link BoundedBodyHandler} before these bytes exist.
 */
public final class ContentEncodingDecoder {

    private ContentEncodingDecoder() {}

    /**
     * The coding a {@code Content-Encoding} header denotes: no field line — or one blank value
     * — means identity, exactly one non-blank {@code gzip} or {@code identity} token means that
     * coding, and anything else, including more than one field line, is unsupported.
     */
    public static ContentCoding parse(List<String> headerValues) {
        if (headerValues == null || headerValues.isEmpty()) {
            return ContentCoding.IDENTITY;
        }
        if (headerValues.size() != 1) {
            return ContentCoding.UNSUPPORTED;
        }
        String token = headerValues.getFirst().strip();
        if (token.isEmpty() || token.equalsIgnoreCase("identity")) {
            return ContentCoding.IDENTITY;
        }
        if (token.equalsIgnoreCase("gzip")) {
            return ContentCoding.GZIP;
        }
        return ContentCoding.UNSUPPORTED;
    }

    /**
     * Decodes the bounded wire bytes of one completed response body.
     *
     * @throws ResponseRejectionException when the coding is unsupported, the body grows beyond
     *     {@code decodedLimit}, or a gzip member is truncated, corrupt, or trailed by bytes
     * @throws NullPointerException when the wire bytes or coding are null
     */
    public static byte[] decode(byte[] wireBytes, ContentCoding coding, long decodedLimit)
            throws ResponseRejectionException {
        Objects.requireNonNull(wireBytes, "wireBytes");
        Objects.requireNonNull(coding, "coding");
        return switch (coding) {
            case IDENTITY -> identity(wireBytes, decodedLimit);
            case GZIP -> gzip(wireBytes, decodedLimit);
            case UNSUPPORTED -> throw new ResponseRejectionException("unsupported content encoding");
        };
    }

    private static byte[] identity(byte[] wireBytes, long decodedLimit) throws ResponseRejectionException {
        if (wireBytes.length > decodedLimit) {
            throw new ResponseRejectionException("response exceeds decoded limit");
        }
        return wireBytes;
    }

    private static byte[] gzip(byte[] wire, long decodedLimit) throws ResponseRejectionException {
        ByteBuffer cursor = ByteBuffer.wrap(wire);
        try {
            readHeader(cursor);
            byte[] decoded = inflateMember(cursor, decodedLimit);
            if (cursor.hasRemaining()) {
                throw new ResponseRejectionException("trailing bytes after gzip stream");
            }
            return decoded;
        } catch (BufferUnderflowException truncated) {
            throw new ResponseRejectionException("truncated gzip response body");
        }
    }

    private static void readHeader(ByteBuffer cursor) throws ResponseRejectionException {
        if (cursor.remaining() < 10) {
            throw new ResponseRejectionException("truncated gzip response body");
        }
        int magicLow = cursor.get() & 0xff;
        int magicHigh = cursor.get() & 0xff;
        int method = cursor.get() & 0xff;
        int flags = cursor.get() & 0xff;
        if (magicLow != 0x1f || magicHigh != 0x8b || method != 8) {
            throw new ResponseRejectionException("malformed gzip response body");
        }
        if ((flags & 0xe0) != 0) {
            throw new ResponseRejectionException("malformed gzip response body");
        }
        cursor.position(cursor.position() + 6);
        if ((flags & 0x04) != 0) {
            int extraLength = (cursor.get() & 0xff) | (cursor.get() & 0xff) << 8;
            skip(cursor, extraLength);
        }
        if ((flags & 0x08) != 0) {
            skipUntilZero(cursor);
        }
        if ((flags & 0x10) != 0) {
            skipUntilZero(cursor);
        }
        if ((flags & 0x02) != 0) {
            skip(cursor, 2);
        }
    }

    private static byte[] inflateMember(ByteBuffer cursor, long decodedLimit)
            throws ResponseRejectionException {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(cursor.array(), cursor.position(), cursor.remaining());
            ByteArrayOutputStream decoded = new ByteArrayOutputStream();
            CRC32 checksum = new CRC32();
            byte[] chunk = new byte[8 * 1024];
            while (!inflater.finished()) {
                int produced;
                try {
                    produced = inflater.inflate(chunk);
                } catch (DataFormatException corrupt) {
                    throw new ResponseRejectionException("malformed gzip response body");
                }
                if (produced == 0 && inflater.needsInput()) {
                    throw new ResponseRejectionException("truncated gzip response body");
                }
                if (produced > 0) {
                    if (decoded.size() + (long) produced > decodedLimit) {
                        throw new ResponseRejectionException("response exceeds decoded limit");
                    }
                    decoded.write(chunk, 0, produced);
                    checksum.update(chunk, 0, produced);
                }
            }
            cursor.position(cursor.array().length - inflater.getRemaining());
            return readAndCheckTrailer(cursor, decoded, checksum);
        } finally {
            inflater.end();
        }
    }

    private static byte[] readAndCheckTrailer(
            ByteBuffer cursor, ByteArrayOutputStream decoded, CRC32 checksum) throws ResponseRejectionException {
        if (cursor.remaining() < 8) {
            throw new ResponseRejectionException("truncated gzip response body");
        }
        long expectedChecksum = readLittleEndian(cursor, 4);
        long expectedLength = readLittleEndian(cursor, 4);
        byte[] bytes = decoded.toByteArray();
        if (checksum.getValue() != expectedChecksum || (bytes.length & 0xffffffffL) != expectedLength) {
            throw new ResponseRejectionException("malformed gzip response body");
        }
        return bytes;
    }

    private static long readLittleEndian(ByteBuffer cursor, int byteCount) {
        long value = 0;
        for (int i = 0; i < byteCount; i++) {
            value |= (cursor.get() & 0xffL) << (8 * i);
        }
        return value;
    }

    private static void skip(ByteBuffer cursor, int byteCount) {
        if (byteCount > 0) {
            if (cursor.remaining() < byteCount) {
                throw new BufferUnderflowException();
            }
            cursor.position(cursor.position() + byteCount);
        }
    }

    private static void skipUntilZero(ByteBuffer cursor) {
        while (cursor.get() != 0) {
            // advance to the NUL terminator
        }
    }
}

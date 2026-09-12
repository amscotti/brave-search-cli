package io.amscotti.bravesearch.adapter.bravehttp;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.amscotti.bravesearch.application.exchange.ContentCoding;
import io.amscotti.bravesearch.application.exchange.ResponseRejectionException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Decoding contract of the {@code Content-Encoding} response header: identity passes through,
 * one well-formed gzip member decodes byte-exactly, and every other shape — stacked, unknown,
 * truncated, corrupt, or trailed-by-garbage — is a typed rejection whose message never repeats
 * body content. The decoder walks the gzip container itself, so boundaries are exact: even one
 * trailing byte is detected, which buffered stream decoders can silently miss.
 */
final class ContentEncodingDecoderTest {

    @Test
    void parsesHeaderValuesCaseInsensitivelyAndWhitespaceTolerantly() {
        assertEquals(ContentCoding.IDENTITY, ContentEncodingDecoder.parse(null));
        assertEquals(ContentCoding.IDENTITY, ContentEncodingDecoder.parse(List.of()));
        assertEquals(ContentCoding.IDENTITY, ContentEncodingDecoder.parse(List.of("")));
        assertEquals(ContentCoding.IDENTITY, ContentEncodingDecoder.parse(List.of("   ")));
        assertEquals(ContentCoding.IDENTITY, ContentEncodingDecoder.parse(List.of("identity")));
        assertEquals(ContentCoding.IDENTITY, ContentEncodingDecoder.parse(List.of(" IDENTITY ")));
        assertEquals(ContentCoding.IDENTITY, ContentEncodingDecoder.parse(List.of("Identity")));
        assertEquals(ContentCoding.GZIP, ContentEncodingDecoder.parse(List.of("gzip")));
        assertEquals(ContentCoding.GZIP, ContentEncodingDecoder.parse(List.of(" GZIP\t")));
        assertEquals(ContentCoding.GZIP, ContentEncodingDecoder.parse(List.of("GZip")));
    }

    @Test
    void parsesStackedAndUnknownTokensAsUnsupported() {
        assertEquals(ContentCoding.UNSUPPORTED, ContentEncodingDecoder.parse(List.of("gzip, gzip")));
        assertEquals(ContentCoding.UNSUPPORTED, ContentEncodingDecoder.parse(List.of("br")));
        assertEquals(ContentCoding.UNSUPPORTED, ContentEncodingDecoder.parse(List.of("deflate")));
        assertEquals(ContentCoding.UNSUPPORTED, ContentEncodingDecoder.parse(List.of("x-gzip")));
        assertEquals(ContentCoding.UNSUPPORTED, ContentEncodingDecoder.parse(List.of("gzip gzip")));
        assertEquals(ContentCoding.UNSUPPORTED, ContentEncodingDecoder.parse(List.of("identity, gzip")));
        assertEquals(ContentCoding.UNSUPPORTED, ContentEncodingDecoder.parse(List.of("chunked")));
    }

    @Test
    void parsesRepeatedFieldLinesAsUnsupported() {
        assertEquals(ContentCoding.UNSUPPORTED, ContentEncodingDecoder.parse(List.of("gzip", "gzip")));
        assertEquals(ContentCoding.UNSUPPORTED, ContentEncodingDecoder.parse(List.of("identity", "identity")));
        assertEquals(ContentCoding.UNSUPPORTED, ContentEncodingDecoder.parse(List.of("identity", "gzip")));
        assertEquals(ContentCoding.UNSUPPORTED, ContentEncodingDecoder.parse(List.of("gzip", "")));
    }

    @Test
    void identityPassesTheWireBytesThroughUnchanged() throws Exception {
        byte[] body = "{\"web\":{\"results\":[{\"title\":\"café ☕\"}]}}".getBytes(UTF_8);

        assertArrayEquals(body, ContentEncodingDecoder.decode(body, ContentCoding.IDENTITY, 16 * 1024));
    }

    @Test
    void gzipMembersDecodeByteExactly() throws Exception {
        byte[] body = "{\"query\":\"récherche ☕\",\"web\":{\"results\":[1,2,3]}}".getBytes(UTF_8);

        assertArrayEquals(body, ContentEncodingDecoder.decode(gzip(body), ContentCoding.GZIP, 16 * 1024));
    }

    @Test
    void gzipHeadersWithOptionsAreSkippedExactly() throws Exception {
        byte[] body = "payload with header options".getBytes(UTF_8);

        byte[] wire = gzipMember(body, (byte) 0x1b, "name.txt", "a comment");

        assertArrayEquals(body, ContentEncodingDecoder.decode(wire, ContentCoding.GZIP, 16 * 1024));
    }

    @Test
    void aConsistentExtraFieldBlockIsSkippedExactly() throws Exception {
        byte[] body = "payload with an extra field".getBytes(UTF_8);

        byte[] wire = gzipMember(body, (byte) 0x1f, "name.txt", "a comment");

        assertArrayEquals(body, ContentEncodingDecoder.decode(wire, ContentCoding.GZIP, 16 * 1024));
    }

    @Test
    void truncationInsideTheExtraFieldBlockIsRejected() throws Exception {
        byte[] wire = gzipMember("payload".getBytes(UTF_8), (byte) 0x1f, "name.txt", "a comment");

        for (int cut : new int[] {11, 12, 13, 14, 15}) {
            ResponseRejectionException rejected = assertThrows(
                    ResponseRejectionException.class,
                    () -> ContentEncodingDecoder.decode(
                            Arrays.copyOfRange(wire, 0, cut), ContentCoding.GZIP, 16 * 1024),
                    "a member cut at byte " + cut + " inside the extra field must be rejected as truncated");
            assertEquals("truncated gzip response body", rejected.getMessage());
        }
    }

    @Test
    void reservedFlagBitsAreRejectedAsMalformed() throws Exception {
        byte[] body = "reserved flag bits".getBytes(UTF_8);

        assertEquals(
                "malformed gzip response body",
                rejectMessage(gzipMember(body, (byte) 0x3f, "name.txt", "a comment")));
        assertEquals(
                "malformed gzip response body",
                rejectMessage(gzipMember(body, (byte) 0xdf, "name.txt", "a comment")));
    }

    @Test
    void aSecondGzipMemberIsTrailingBytesNotADecodedContinuation() throws Exception {
        byte[] body = "first member".getBytes(UTF_8);

        byte[] wire = concat(gzip(body), gzip("second".getBytes(UTF_8)));

        ResponseRejectionException rejected = assertThrows(
                ResponseRejectionException.class,
                () -> ContentEncodingDecoder.decode(wire, ContentCoding.GZIP, 16 * 1024));
        assertEquals("trailing bytes after gzip stream", rejected.getMessage());
    }

    @Test
    void evenASingleTrailingByteIsRejected() throws Exception {
        byte[] body = "exact member".getBytes(UTF_8);

        byte[] wire = concat(gzip(body), new byte[] {'x'});

        ResponseRejectionException rejected = assertThrows(
                ResponseRejectionException.class,
                () -> ContentEncodingDecoder.decode(wire, ContentCoding.GZIP, 16 * 1024));
        assertEquals("trailing bytes after gzip stream", rejected.getMessage());
    }

    @Test
    void truncatedMembersAreRejectedAtEveryCutPoint() throws Exception {
        byte[] body = "{\"web\":{\"results\":[{\"deeply\":[\"nested\",\"value\"]}]}}".getBytes(UTF_8);
        byte[] wire = gzip(body);

        for (int cut : new int[] {3, 10, 18, wire.length / 2, wire.length - 8, wire.length - 1}) {
            ResponseRejectionException rejected = assertThrows(
                    ResponseRejectionException.class,
                    () -> ContentEncodingDecoder.decode(
                            Arrays.copyOfRange(wire, 0, cut), ContentCoding.GZIP, 16 * 1024),
                    "a member cut at byte " + cut + " must be rejected as truncated");
            assertEquals("truncated gzip response body", rejected.getMessage());
        }
    }

    @Test
    void corruptMembersAreRejected() throws Exception {
        byte[] body = "corruptible".getBytes(UTF_8);

        byte[] badMagic = gzip(body);
        badMagic[1] = 0x42;
        assertEquals(
                "malformed gzip response body",
                rejectMessage(badMagic));

        byte[] badMethod = gzip(body);
        badMethod[2] = 9;
        assertEquals(
                "malformed gzip response body",
                rejectMessage(badMethod));

        byte[] badCrc = gzip(body);
        badCrc[badCrc.length - 5] ^= 0x55;
        assertEquals(
                "malformed gzip response body",
                rejectMessage(badCrc));
    }

    @Test
    void decompressionBombsAbortAtTheDecodedLimitWithoutInflatingFurther() throws Exception {
        byte[] bomb = gzip(repeat((byte) 'x', 1024 * 1024));

        ResponseRejectionException rejected = assertThrows(
                ResponseRejectionException.class,
                () -> ContentEncodingDecoder.decode(bomb, ContentCoding.GZIP, 4 * 1024));
        assertEquals("response exceeds decoded limit", rejected.getMessage());
    }

    @Test
    void decodingSucceedsAtExactlyTheDecodedLimit() throws Exception {
        byte[] body = repeat((byte) 'y', 8 * 1024);

        assertArrayEquals(body, ContentEncodingDecoder.decode(gzip(body), ContentCoding.GZIP, 8 * 1024));
    }

    @Test
    void unsupportedCodingsAreRejectedBeforeAnyDecoding() {
        ResponseRejectionException rejected = assertThrows(
                ResponseRejectionException.class,
                () -> ContentEncodingDecoder.decode(
                        "irrelevant".getBytes(UTF_8), ContentCoding.UNSUPPORTED, 16 * 1024));
        assertEquals("unsupported content encoding", rejected.getMessage());
    }

    @Test
    void identityBodiesBeyondTheLimitAreRejected() {
        ResponseRejectionException rejected = assertThrows(
                ResponseRejectionException.class,
                () -> ContentEncodingDecoder.decode(
                        new byte[17], ContentCoding.IDENTITY, 16));
        assertEquals("response exceeds decoded limit", rejected.getMessage());
    }

    private static String rejectMessage(byte[] wire) {
        return assertThrows(
                        ResponseRejectionException.class,
                        () -> ContentEncodingDecoder.decode(wire, ContentCoding.GZIP, 16 * 1024))
                .getMessage();
    }

    /** Flags FTEXT(1) FHCRC(2) FEXTRA(4) FNAME(8) FCOMMENT(16) when set in {@code flags}. */
    private static byte[] gzipMember(byte[] payload, byte flags, String filename, String comment) throws IOException {
        CRC32 crc = new CRC32();
        ByteArrayOutputStream member = new ByteArrayOutputStream();
        member.write(new byte[] {0x1f, (byte) 0x8b, 8, flags, 0, 0, 0, 0, 0, 3});
        if ((flags & 0x04) != 0) {
            // XLEN is little-endian and must cover exactly the four payload bytes that follow
            member.write(new byte[] {4, 0, 'e', 'x', 't', 'r'});
        }
        if ((flags & 0x08) != 0) {
            member.write(filename.getBytes(UTF_8));
            member.write(0);
        }
        if ((flags & 0x10) != 0) {
            member.write(comment.getBytes(UTF_8));
            member.write(0);
        }
        if ((flags & 0x02) != 0) {
            CRC32 headerCrc = new CRC32();
            headerCrc.update(member.toByteArray());
            long value = headerCrc.getValue();
            member.write(new byte[] {(byte) value, (byte) (value >>> 8)});
        }
        member.write(deflate(payload));
        crc.update(payload);
        long value = crc.getValue();
        member.write(new byte[] {
            (byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24),
            (byte) payload.length, (byte) (payload.length >>> 8), (byte) (payload.length >>> 16), (byte) (payload.length >>> 24)
        });
        return member.toByteArray();
    }

    private static byte[] deflate(byte[] payload) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        deflater.setInput(payload);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[64];
        while (!deflater.finished()) {
            int produced = deflater.deflate(chunk);
            out.write(chunk, 0, produced);
        }
        deflater.end();
        return out.toByteArray();
    }

    private static byte[] gzip(byte[] payload) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(payload);
        }
        return out.toByteArray();
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = new byte[first.length + second.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    private static byte[] repeat(byte value, int count) {
        byte[] filled = new byte[count];
        Arrays.fill(filled, value);
        return filled;
    }
}

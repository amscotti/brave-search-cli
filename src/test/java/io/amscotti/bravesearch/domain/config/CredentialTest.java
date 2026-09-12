package io.amscotti.bravesearch.domain.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Token contract of the credential value: validation boundaries count bytes, multibyte UTF-8
 * survives exactly, copies are defensive, equality follows content, and no diagnostic ever
 * carries token material.
 */
final class CredentialTest {

    private static final int MAX_BYTES = 4096;

    @Test
    void rejectsNullTokenBytes() {
        assertThrows(NullPointerException.class, () -> Credential.of(null));
    }

    @Test
    void rejectsAnEmptyToken() {
        String sentinel = sentinel();
        InvalidCredentialException invalid =
                assertThrows(InvalidCredentialException.class, () -> Credential.of(new byte[0]));
        assertCarriesNoTokenMaterial(invalid, sentinel);
    }

    @Test
    void rejectsAWhitespaceOnlyToken() {
        byte[] token = " \t\n\r\f\u000B\u001C\u001D\u001E\u001F".getBytes(StandardCharsets.UTF_8);
        InvalidCredentialException invalid =
                assertThrows(InvalidCredentialException.class, () -> Credential.of(token));
        assertNotNull(invalid.getMessage());
    }

    @Test
    void rejectsAControlCharacterInsideTheToken() {
        String sentinel = sentinel();
        byte[] token = (sentinel + "\u0000" + "tail").getBytes(StandardCharsets.UTF_8);
        InvalidCredentialException invalid =
                assertThrows(InvalidCredentialException.class, () -> Credential.of(token));
        assertCarriesNoTokenMaterial(invalid, sentinel);
    }

    @Test
    void rejectsTheDeleteControlCharacter() {
        String sentinel = sentinel();
        byte[] token = (sentinel + "\u007F").getBytes(StandardCharsets.UTF_8);
        InvalidCredentialException invalid =
                assertThrows(InvalidCredentialException.class, () -> Credential.of(token));
        assertCarriesNoTokenMaterial(invalid, sentinel);
    }

    @Test
    void rejectsAControlCharacterFromTheUnicodeC1Range() {
        byte[] token = "head\u0085tail".getBytes(StandardCharsets.UTF_8);
        assertThrows(InvalidCredentialException.class, () -> Credential.of(token));
    }

    @Test
    void rejectsATokenWhoseBytesAreNotStrictlyValidUtf8() {
        String sentinel = sentinel();
        byte[] prefix = sentinel.getBytes(StandardCharsets.UTF_8);
        byte[][] mangled = {
            concat(prefix, new byte[] {(byte) 0xff}),
            concat(prefix, new byte[] {(byte) 0xc3, 0x28}),
            concat(prefix, new byte[] {(byte) 0xc0, (byte) 0xaf}),
            concat(prefix, new byte[] {(byte) 0xed, (byte) 0xa0, (byte) 0x80})
        };

        for (byte[] token : mangled) {
            InvalidCredentialException invalid =
                    assertThrows(InvalidCredentialException.class, () -> Credential.of(token));
            assertCarriesNoTokenMaterial(invalid, sentinel);
        }
    }

    @Test
    void acceptsATokenOfExactlyTheMaximumByteLength() {
        byte[] token = ("a".repeat(MAX_BYTES - 1) + "b").getBytes(StandardCharsets.UTF_8);
        Credential credential = Credential.of(token);
        assertEquals(MAX_BYTES, credential.byteLength());
    }

    @Test
    void rejectsATokenOneBytePastTheMaximum() {
        String sentinel = sentinel();
        byte[] token = (sentinel + "a".repeat(MAX_BYTES)).getBytes(StandardCharsets.UTF_8);
        InvalidCredentialException invalid =
                assertThrows(InvalidCredentialException.class, () -> Credential.of(token));
        assertCarriesNoTokenMaterial(invalid, sentinel);
    }

    @Test
    void theByteLimitCountsBytesNotCharacters() {
        String token = "キ".repeat(1366);
        assertTrue(token.length() < MAX_BYTES, "the character count must stay under the byte limit");
        assertEquals(4098, token.getBytes(StandardCharsets.UTF_8).length);
        assertThrows(InvalidCredentialException.class, () -> Credential.of(token.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void preservesMultibyteUtf8BytesExactly() {
        String token = "ключ-キー-\uD83E\uDDEA-key";
        Credential credential = Credential.of(token.getBytes(StandardCharsets.UTF_8));
        assertEquals(token, new String(credential.tokenBytes(), StandardCharsets.UTF_8));
        assertEquals(token.getBytes(StandardCharsets.UTF_8).length, credential.byteLength());
    }

    @Test
    void tokenBytesReturnsADefensiveCopy() {
        byte[] token = "token-value".getBytes(StandardCharsets.UTF_8);
        Credential credential = Credential.of(token);
        token[0] = 'X';
        byte[] leakedCopy = credential.tokenBytes();
        leakedCopy[0] = 'Y';
        assertEquals('t', credential.tokenBytes()[0], "caller mutations must not reach the stored token");
    }

    @Test
    void equalityAndHashingFollowTheTokenBytes() {
        Credential first = Credential.of("same-token".getBytes(StandardCharsets.UTF_8));
        Credential second = Credential.of("same-token".getBytes(StandardCharsets.UTF_8));
        Credential other = Credential.of("other-token".getBytes(StandardCharsets.UTF_8));
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, other);
        assertNotEquals(first, null);
    }

    @Test
    void toStringIsFullyRedacted() {
        String sentinel = sentinel();
        Credential credential = Credential.of(sentinel.getBytes(StandardCharsets.UTF_8));
        assertEquals("Credential[redacted]", credential.toString());
        assertFalse(credential.toString().contains(sentinel));
    }

    /**
     * A unique random-looking sentinel per invocation: it is embedded in rejected token material,
     * so any appearance in a message or rendering is a leak of the token itself.
     */
    private static String sentinel() {
        return "BSK" + Long.toUnsignedString(UUID.randomUUID().getMostSignificantBits(), 36)
                + Long.toUnsignedString(UUID.randomUUID().getLeastSignificantBits(), 36);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = new byte[first.length + second.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    private static void assertCarriesNoTokenMaterial(Throwable failure, String sentinel) {
        for (Throwable cursor = failure; cursor != null; cursor = cursor.getCause()) {
            assertNotNull(cursor.getMessage(), "the failure must explain the violated invariant");
            assertFalse(cursor.getMessage().contains(sentinel), "message leaks token material");
            assertFalse(cursor.toString().contains(sentinel), "toString leaks token material");
        }
    }
}

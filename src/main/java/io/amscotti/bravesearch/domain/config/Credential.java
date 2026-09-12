package io.amscotti.bravesearch.domain.config;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * An opaque Brave API credential: the token's exact bytes plus the guarantee that they form a
 * usable secret.
 *
 * <p>The token is treated as opaque: the bytes are copied on the way in and on the way out, so
 * no later caller mutation can change or alias the stored value, and multibyte UTF-8 content
 * survives a round trip untouched. Decoding happens only to verify the character-level
 * invariants — non-empty, not whitespace-only, strictly UTF-8 with a byte-exact round trip,
 * printable and free of control characters, at most {@value #MAX_BYTES} bytes — so the value
 * is header-safe: rendering it into an HTTP header value can never mangle it or turn it into
 * replacement characters. The stored bytes themselves are never re-encoded. The token never
 * appears in diagnostics: {@link #toString()} is always {@code Credential[redacted]}, and the
 * factory's {@link InvalidCredentialException} carries only the violated invariant.
 */
public final class Credential {

    /** The largest token, in bytes, accepted from any source. */
    public static final int MAX_BYTES = 4096;

    private static final String REDACTED = "Credential[redacted]";

    private final byte[] token;

    private Credential(byte[] token) {
        this.token = token;
    }

    /**
     * Validates and wraps the token bytes.
     *
     * @throws InvalidCredentialException when the token is empty, whitespace-only, not strictly
     *     round-tripping UTF-8, contains a control character, or exceeds {@value #MAX_BYTES}
     *     bytes; the exception never carries token material
     * @throws NullPointerException when {@code token} is null
     */
    public static Credential of(byte[] token) {
        Objects.requireNonNull(token, "token");
        byte[] bytes = token.clone();
        if (bytes.length == 0) {
            throw new InvalidCredentialException("empty");
        }
        if (bytes.length > MAX_BYTES) {
            throw new InvalidCredentialException("longer than " + MAX_BYTES + " bytes");
        }
        String decoded = decodeHeaderSafe(bytes);
        if (decoded.chars().allMatch(Character::isWhitespace)) {
            throw new InvalidCredentialException("whitespace-only");
        }
        if (decoded.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidCredentialException("contains a control character");
        }
        return new Credential(bytes);
    }

    /**
     * Decodes the token bytes as UTF-8 with malformed input reported instead of replaced, and
     * requires the decode to re-encode byte-exact: the value travels verbatim into the
     * subscription-token header, so any sequence a lenient decode would turn into a
     * replacement character is rejected here instead. The rejection names the invariant only;
     * no token byte is repeated.
     */
    private static String decodeHeaderSafe(byte[] bytes) {
        String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException invalid) {
            throw new InvalidCredentialException("not strictly round-tripping UTF-8");
        }
        if (!Arrays.equals(decoded.getBytes(StandardCharsets.UTF_8), bytes)) {
            throw new InvalidCredentialException("not strictly round-tripping UTF-8");
        }
        return decoded;
    }

    /** A defensive copy of the exact token bytes. */
    public byte[] tokenBytes() {
        return token.clone();
    }

    /** The token length in bytes. */
    public int byteLength() {
        return token.length;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Credential candidate && Arrays.equals(token, candidate.token);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(token);
    }

    @Override
    public String toString() {
        return REDACTED;
    }
}

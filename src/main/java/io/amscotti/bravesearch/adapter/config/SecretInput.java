package io.amscotti.bravesearch.adapter.config;

import io.amscotti.bravesearch.application.exchange.BraveApiRequest;
import io.amscotti.bravesearch.application.exchange.InvalidTokenException;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.config.InvalidCredentialException;
import io.amscotti.bravesearch.domain.config.LocalConfigException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads one API-key secret for {@code config set-key} from either the no-echo console or — only
 * when the caller explicitly requested it — standard input under the exact one-line contract.
 *
 * <p>Console path: the injected {@link ConsoleAccess} owns every {@code System.console} touch of
 * this package; its characters are encoded as UTF-8, validated through the credential contract
 * and the header alphabet of the subscription token, and the character buffer is wiped after use.
 * The wiping is deliberate but partial: the encoded bytes are copied into the credential and,
 * on exchange, into an immutable header string, and Java offers no way to wipe those copies.
 *
 * <p>Stdin path: exactly one nonempty line terminated by LF or CRLF, with only that terminator
 * removed. Rejected — as the typed secret-input configuration error, never echoing the offered
 * bytes — are end of file before the terminator, control characters inside the line, bytes that
 * are not valid UTF-8, a line above {@link Credential#MAX_BYTES}, a token carrying a character
 * above U+00FF, and any non-whitespace byte
 * after the terminator; trailing ASCII whitespace and blank lines are accepted. An interrupted
 * or failing stream restores the interrupt flag and fails the same way, so the command layer
 * can guarantee the prior config stays untouched on every failure by storing only after a
 * successful read.
 */
public final class SecretInput {

    /** The bytes allowed after the line terminator: ASCII whitespace only. */
    private static final String TRAILING_WHITESPACE = " \t\n\u000B\u000C\r";

    private final ConsoleAccess console;

    private final InputStream stdin;

    public SecretInput(ConsoleAccess console, InputStream stdin) {
        this.console = Objects.requireNonNull(console, "console");
        this.stdin = Objects.requireNonNull(stdin, "stdin");
    }

    /**
     * Reads one secret; {@code fromStdin} selects the exact one-line stdin contract, otherwise
     * the no-echo console is required.
     *
     * @throws LocalConfigException with reason {@code SECRET_INPUT} when no console delivers a
     *     secret without {@code --stdin}, or the input violates the one-line contract; the
     *     failure never carries token material
     */
    public Credential read(boolean fromStdin) {
        return fromStdin ? readStdinLine() : readConsole();
    }

    private Credential readConsole() {
        char[] password =
                console.readPassword().orElseThrow(() -> LocalConfigException.secretInput(
                                "no console delivered a secret and --stdin was not requested;"
                                        + " a key can only be read from a no-echo console or one --stdin line"));
        try {
            return credential(encodedUtf8(password));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /**
     * Encodes the secret's characters as UTF-8 without building an intermediate String: the
     * characters go straight through a reporting encoder into a byte array, so characters the
     * UTF-8 encoder refuses — an unpaired surrogate — fail as the typed secret-input error
     * instead of silently replacing themselves in the stored token.
     */
    private static byte[] encodedUtf8(char[] secret) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(secret));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException unmappable) {
            throw LocalConfigException.secretInput("the console secret is not encodable as UTF-8");
        }
    }

    private Credential readStdinLine() {
        byte[] line = readTerminatedLine();
        if (line.length > 0 && line[line.length - 1] == '\r') {
            line = Arrays.copyOf(line, line.length - 1);
        }
        requireStrictUtf8(line);
        return credential(line);
    }

    /**
     * Reads the line into a buffer one byte beyond the bound: a full-bound secret must still
     * admit the CR of a CRLF terminator, which the length check discounts, while anything
     * longer than the bound plus that CR stays rejected.
     */
    private byte[] readTerminatedLine() {
        ByteBuffer buffer = ByteBuffer.allocate(Credential.MAX_BYTES + 1);
        int first;
        try {
            first = stdin.read();
            while (first != -1 && first != '\n' && buffer.position() < Credential.MAX_BYTES + 1) {
                buffer.put((byte) first);
                first = stdin.read();
            }
        } catch (IOException failure) {
            throw stdinFailed(failure);
        }
        if (first != '\n') {
            if (first == -1) {
                throw LocalConfigException.secretInput(
                        "the stdin line ended before its LF or CRLF terminator");
            }
            throw LocalConfigException.secretInput("the stdin line exceeds " + Credential.MAX_BYTES + " bytes");
        }
        if (lineLength(buffer) > Credential.MAX_BYTES) {
            throw LocalConfigException.secretInput("the stdin line exceeds " + Credential.MAX_BYTES + " bytes");
        }
        requireOnlyWhitespaceAfterTheLine();
        return Arrays.copyOf(buffer.array(), buffer.position());
    }

    /** The line's length with the CRLF terminator's CR discounted from a full-bound buffer. */
    private static int lineLength(ByteBuffer buffer) {
        int length = buffer.position();
        return length > 0 && buffer.get(length - 1) == '\r' ? length - 1 : length;
    }

    private void requireOnlyWhitespaceAfterTheLine() {
        int next;
        try {
            while ((next = stdin.read()) != -1) {
                if (TRAILING_WHITESPACE.indexOf(next) < 0) {
                    throw LocalConfigException.secretInput(
                            "the stdin input carries non-whitespace after the first line");
                }
            }
        } catch (IOException failure) {
            throw stdinFailed(failure);
        }
    }

    private static void requireStrictUtf8(byte[] line) {
        try {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(line));
        } catch (CharacterCodingException malformed) {
            throw LocalConfigException.secretInput("the stdin line is not valid UTF-8");
        }
    }

    /**
     * The stored secret's two validation gates: the credential contract first, then the
     * header alphabet of the subscription token. The second gate keeps the store free of a
     * token no HTTP header value can carry: such a token could be written, but every later
     * invocation would refuse it at the preflight, so it is rejected where it is read.
     */
    private static Credential credential(byte[] bytes) {
        Credential secret;
        try {
            secret = Credential.of(bytes);
        } catch (InvalidCredentialException invalid) {
            throw LocalConfigException.secretInput("the secret is rejected: it is " + invalid.reason());
        }
        try {
            BraveApiRequest.requireWireableToken(secret);
        } catch (InvalidTokenException unwirable) {
            throw LocalConfigException.secretInput("the secret is rejected: " + unwirable.getMessage());
        }
        return secret;
    }

    private static LocalConfigException stdinFailed(IOException failure) {
        if (failure instanceof InterruptedIOException) {
            Thread.currentThread().interrupt();
            return LocalConfigException.secretInput("reading the secret from stdin was interrupted");
        }
        return LocalConfigException.secretInput(
                "the secret could not be read from stdin: " + failure.getClass().getSimpleName());
    }

    /** The process console behind an injectable seam: the only {@code System.console} touch. */
    @FunctionalInterface
    public interface ConsoleAccess {

        /**
         * One no-echo password line from the console; empty when no console is attached or the
         * console delivered none.
         */
        Optional<char[]> readPassword();

        /** The process console of this JVM. */
        static ConsoleAccess processConsole() {
            return () -> {
                java.io.Console attached = System.console();
                return attached == null ? Optional.empty() : Optional.ofNullable(attached.readPassword());
            };
        }
    }
}

package io.amscotti.bravesearch.application.exchange;

import java.io.IOException;
import java.util.Objects;

/**
 * The typed rejection of a response whose body violated the response contract: a byte ceiling,
 * an unsupported content encoding, or a gzip member that is truncated, corrupt, or trailed by
 * extra bytes.
 *
 * <p>Checked and extending {@link IOException} so it travels through the HTTP client's
 * exception channel unchanged, and constructed only with diagnostics that name the violated
 * rule — the message never quotes body content, header values, or URI text. It lives with the
 * exchange vocabulary rather than inside the HTTP adapter because it is the cross-layer signal
 * of a bounded exchange going malformed, in the same way {@link InvalidOriginException} is the
 * signal of an unusable origin.
 */
public final class ResponseRejectionException extends IOException {

    /**
     * @throws NullPointerException when {@code diagnostic} is null
     */
    public ResponseRejectionException(String diagnostic) {
        super(Objects.requireNonNull(diagnostic, "diagnostic"));
    }
}

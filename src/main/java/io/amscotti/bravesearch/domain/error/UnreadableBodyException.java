package io.amscotti.bravesearch.domain.error;

/**
 * The success body of an exchange is not exactly one readable JSON document.
 *
 * <p>Raised where a 2xx body that promised JSON is first read as a document, so the rendering
 * surfaces can classify the exchange as malformed instead of inventing a result list. The
 * message never quotes body text: the body is untrusted input and the failure is already
 * fully described by its category.
 */
public final class UnreadableBodyException extends RuntimeException {

    public UnreadableBodyException(String message) {
        super(message);
    }
}

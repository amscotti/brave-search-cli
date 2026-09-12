package io.amscotti.bravesearch.application.port.out;

import io.amscotti.bravesearch.domain.error.Outcome;

/**
 * Outbound gateway port for one streaming Brave Answers exchange.
 *
 * <p>Opening either yields the live exchange — whose body publishes progressively under the
 * caller's demand and whose terminal cause the caller reads from its cancellation context —
 * or the expected failure envelope of an exchange that never opened: a transport break, a
 * classified upstream failure with its partial metadata, or a malformed response shape. The
 * streaming path deliberately bypasses the shared blocking transport: one streaming gateway
 * owns its client, its reader executor, and its body publisher per exchange.
 */
public interface AnswersStreamPort {

    /**
     * Opens one streaming exchange for {@code invocation}.
     *
     * @throws java.lang.NullPointerException when {@code invocation} is null
     * @throws IllegalArgumentException when the invocation's request does not carry
     *     {@code stream=true}
     */
    Outcome<AnswersStreamExchange> stream(AnswersStreamDispatch invocation);
}

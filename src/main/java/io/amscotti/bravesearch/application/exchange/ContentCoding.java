package io.amscotti.bravesearch.application.exchange;

/**
 * The response coding one {@code Content-Encoding} value denotes: identity (including a
 * missing or blank header), the one supported compression coding gzip, or unsupported —
 * stacked lists such as {@code "gzip, gzip"}, {@code br}, and any unknown token.
 *
 * <p>An exchange-level value vocabulary rather than an HTTP-adapter type: requests choose
 * their accept-encoding by response mode, responses carry their coding, and the transport's
 * byte ceilings key off exactly this trichotomy.
 */
public enum ContentCoding {
    IDENTITY,
    GZIP,
    UNSUPPORTED
}

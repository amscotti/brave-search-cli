package io.amscotti.bravesearch.application.exchange;

/**
 * The run's cancellation latch fired while the response body was still being read; the body
 * stream is closed before this surfaces — severing the peer — and the exchange maps to a
 * TRANSPORT failure whose fixed diagnostic carries no cause text, because the latched cause,
 * not the exchange, decides the process status.
 */
public final class ExchangeCancelledException extends RuntimeException {}

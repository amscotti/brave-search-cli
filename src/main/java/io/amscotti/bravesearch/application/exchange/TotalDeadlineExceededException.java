package io.amscotti.bravesearch.application.exchange;

/**
 * The total request deadline passed while the response body was still being read; the body
 * stream is closed before this surfaces — severing the stalled peer — and the exchange maps
 * to a TRANSPORT failure whose diagnostic is distinct from the headers-phase timeout.
 */
public final class TotalDeadlineExceededException extends RuntimeException {}

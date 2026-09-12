package io.amscotti.bravesearch.api;

/**
 * The public cancellation handle of one Answers stream: request the end of the exchange and
 * observe whether a terminal cause has latched.
 *
 * <p>Requesting cancellation latches the exchange's terminal cause (if none latched before)
 * and releases every resource the exchange owns — the body, its reader, and the transport.
 * It is safe to call from any thread, more than once, and concurrently with the stream's
 * own termination: the first terminal cause always wins, exactly as an idle or wall-clock
 * deadline or an explicit close would.
 */
public interface StreamCancellation {

    /** Requests the end of the exchange; idempotent and never blocks on the stream's own progress. */
    void cancel();

    /** Whether a terminal cause — cancellation, a deadline, a failure, or a close — has latched. */
    boolean cancelled();
}

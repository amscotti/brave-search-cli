package io.amscotti.bravesearch.application.stream;

/**
 * Attachment point between a running process and the cancellation contexts of its live
 * streaming runs.
 *
 * <p>Implementations keep track of every context registered while a stream exchange is open and
 * hand back a detach handle whose single job is deregistration, so the process-wide interrupt
 * path can stay a plain dependency of whoever drives streams without owning any bootstrap type.
 */
public interface CancellationRegistry {

    /**
     * Tracks {@code cancellation} as live for as long as its exchange is open.
     *
     * @param cancellation the terminal-cause latch of one streaming run
     * @return handle that detaches the context; safe to run more than once
     */
    Runnable register(CancellationContext cancellation);
}

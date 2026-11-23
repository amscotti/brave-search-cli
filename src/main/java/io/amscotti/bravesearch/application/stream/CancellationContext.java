package io.amscotti.bravesearch.application.stream;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single owner of terminal-cause arbitration for one streaming run: SIGINT, SIGTERM, a broken
 * output pipe, an idle or wall-clock deadline, a subscriber failure, a transport failure, and
 * an explicit close all converge here, and exactly the first of them becomes the run's terminal
 * cause and decides its exit code.
 *
 * <p>Instances are thread-safe: {@link #latch(Cause)} resolves the race for the first cause
 * atomically, so exactly one caller ever observes {@code true} no matter how many signal
 * sources fire concurrently, and the remaining readers are lock-free and wait-free. Once latched,
 * the cause never changes.
 */
public final class CancellationContext {

    /** Why the run terminated; the CLI exit table derives the process status from the cause. */
    public enum Cause {        /** The user interrupted the process; conventional shell signal exit status. */
        SIGINT,
        /** The user terminated the process; conventional shell signal exit status. */
        SIGTERM,
        /** The consumer closed the output stream; a normal end of the stream, not a failure. */
        BROKEN_PIPE,
        /** No event arrived within the allowed inactivity window. */
        IDLE_TIMEOUT,
        /** The run exceeded its total wall-clock budget. */
        WALL_TIMEOUT,
        /** The downstream subscriber failed while consuming the stream. */
        SUBSCRIBER_FAILURE,
        /** The transport under the stream failed — a reset, truncated, or unreachable body. */
        TRANSPORT_FAILURE,

        /** The stream ended or was closed explicitly without another cause firing first. */
        CLOSED;
    }

    private final AtomicReference<Cause> cause = new AtomicReference<>();
    private final CountDownLatch released = new CountDownLatch(1);

    /**
     * Records {@code candidate} as the terminal cause if no cause was latched before; the first
     * call wins atomically and is the only one that returns {@code true}.
     */
    public boolean latch(Cause candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (cause.compareAndSet(null, candidate)) {
            released.countDown();
            return true;
        }
        return false;
    }

    /** The terminal cause, present once the first latch has happened. */
    public Optional<Cause> cause() {
        return Optional.ofNullable(cause.get());
    }

    /** Whether a terminal cause has been latched. */
    public boolean cancelled() {
        return cause.get() != null;
    }

    /**
     * Waits until the first latch, or until the waiting thread is interrupted. An interrupt
     * ends the wait by throwing and latches nothing, so a watcher parked here retires on its
     * own interrupt instead of polling the latch; the terminal cause is read through
     * {@link #cause()} once the wait returns.
     *
     * @throws InterruptedException when the waiting thread is interrupted before the latch;
     *     the interrupt status is cleared, as every checked wait leaves it
     */
    public void await() throws InterruptedException {
        released.await();
    }

    public void awaitUninterruptibly() {
        boolean interrupted = false;
        while (released.getCount() > 0) {
            try {
                released.await();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}

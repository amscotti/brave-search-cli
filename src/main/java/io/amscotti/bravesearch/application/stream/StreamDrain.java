package io.amscotti.bravesearch.application.stream;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Terminal-tracking subscriber harness of one streaming output run: it pays unbounded
 * demand — the run's interesting failure modes are cancellation races, not slow consumption
 * — forwards every delivered item to the injected handler on the delivering thread, and
 * records the terminal signal exactly once, so the thread that opened the run can await its
 * end and read the terminal failure back.
 *
 * <p>Every terminal path guarantees a latched cause: a handler that throws latches the
 * subscriber-failure cause — unless the handler already latched a truer cause such as the
 * broken pipe it observed, because {@link CancellationContext#latch} lets the first cause
 * win — and an upstream error latches the transport cause when nothing latched earlier.
 * The harness refuses further callbacks after its terminal, so a late deliverer can neither
 * resurrect the run nor double its terminal signal.
 *
 * <p>Instances are safe for exactly one subscription, the subscription the run opens.
 *
 * @param <T> the item type of the stream this harness drains
 */
public final class StreamDrain<T> implements Flow.Subscriber<T> {

    private final CancellationContext cancellation;
    private final Consumer<T> handler;
    private final CountDownLatch terminal = new CountDownLatch(1);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    /**
     * Serializes handler delivery against {@link #stopNow}: a delivery runs the handler only
     * under this monitor, so the stopping side waits out any in-flight handler and closes the
     * door behind it before returning.
     */
    private final Object delivery = new Object();

    private volatile Flow.Subscription subscription;
    private volatile boolean stopped;

    /**
     * @param cancellation the run's shared terminal-cause latch
     * @param handler receives every delivered item on the delivering thread; a thrown
     *     runtime failure ends the run as a subscriber failure unless the handler already
     *     latched a truer cause first
     */
    public StreamDrain(CancellationContext cancellation, Consumer<T> handler) {
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    @Override
    public void onSubscribe(Flow.Subscription upstream) {
        this.subscription = Objects.requireNonNull(upstream, "upstream");
        upstream.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(T item) {
        synchronized (delivery) {
            if (stopped) {
                throw new IllegalStateException("delivery reached the harness after its terminal");
            }
            try {
                handler.accept(item);
            } catch (RuntimeException brokenHandler) {
                failure.compareAndSet(null, brokenHandler);
                cancellation.latch(CancellationContext.Cause.SUBSCRIBER_FAILURE);
                stop();
                return;
            }
        }
    }

    @Override
    public void onError(Throwable thrown) {
        failure.compareAndSet(null, thrown);
        cancellation.latch(CancellationContext.Cause.TRANSPORT_FAILURE);
        stop();
    }

    @Override
    public void onComplete() {
        // the clean terminal latches the clean-close cause when nothing latched earlier,
        // so the one wait every run performs covers this ending too
        cancellation.latch(CancellationContext.Cause.CLOSED);
        stop();
    }

    /** Whether a terminal signal has been recorded. */
    public boolean terminalReached() {
        return terminal.getCount() == 0;
    }

    /**
     * Waits until the terminal signal; never throws. An interrupt while waiting keeps the
     * wait in place and re-asserts the thread's interrupt flag on return, so the awaiting
     * thread never loses the signal.
     */
    public void awaitTerminal() {
        boolean interrupted = false;
        while (terminal.getCount() > 0) {
            try {
                terminal.await();
            } catch (InterruptedException waiting) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** The terminal failure — the upstream error or the handler failure — or empty on completion. */
    public Optional<Throwable> failure() {
        return Optional.ofNullable(failure.get());
    }

    /**
     * Stops the subscription from the awaiting side: the upstream is cancelled first — which
     * cuts the source without waiting on the delivery monitor — and then the delivery monitor
     * is entered, which waits out any handler still running and sets the stopped mark under
     * the monitor, so once this returns no in-flight or later callback can race the caller's
     * terminal writes on the same output stream. Used when the run's cause latched without
     * the stream itself signaling — a cancelled or deadline-cut exchange delivers no terminal
     * of its own.
     */
    public void stopNow() {
        Flow.Subscription live = subscription;
        if (live != null) {
            live.cancel();
        }
        synchronized (delivery) {
            stopped = true;
        }
        terminal.countDown();
    }

    private void stop() {
        stopped = true;
        Flow.Subscription live = subscription;
        if (live != null && failure.get() != null) {
            live.cancel();
        }
        terminal.countDown();
    }
}

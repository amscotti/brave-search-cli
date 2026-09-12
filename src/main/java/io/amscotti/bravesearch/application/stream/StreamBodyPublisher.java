package io.amscotti.bravesearch.application.stream;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Cold, single-subscription {@link Flow Publisher} that forwards one streaming body to a single
 * subscriber as {@code byte[]} chunks of at most eight KiB, independent of any transport: any
 * blocking {@link InputStream} works, which is why this machinery lives beside the cancellation
 * context it shares rather than beside a specific HTTP client. The publisher instance itself is the
 * {@link Flow.Subscription} handed to that subscriber, so no per-subscription object is created
 * outside a composition root.
 *
 * <p>The publisher is demand-driven: the reader task it schedules delivers an {@code onNext} only
 * while outstanding request credit remains, parks without holding any lock while the credit is
 * zero, and terminates exactly once with either {@code onComplete} (body exhausted) or {@code
 * onError} (every other ending). A second subscriber is refused per Flow conventions with {@code
 * onSubscribe} followed by {@code onError} with an {@link IllegalStateException}, delivered
 * through a throwaway JDK submission publisher so the refused side never holds a live handle;
 * the live subscription is unaffected.
 *
 * <p>Terminal causes are arbitrated by the shared {@link CancellationContext}: subscription
 * cancel, both deadlines, and a failing subscriber each race to latch their cause there, and only
 * the first latched cause wins. The body stream is closed on every terminal path because closing
 * is the only portable way to unblock a read parked inside a blocking HTTP stream: the JDK
 * response streams release a blocked reader with an {@link IOException} as soon as the stream is
 * closed, so {@code cancel} from any thread ends the reader promptly instead of waiting for
 * bytes that may never arrive. Deadlines are evaluated on every reader iteration — between
 * delivered chunks and while parked awaiting credit — so a stream that stays silent with no
 * outstanding credit is still cut by the idle or wall limit.
 *
 * <p>The executor is caller-owned: the publisher schedules exactly one reader task on it and never
 * shuts it down, keeps no thread of its own, and holds no executor slot once the reader reaches
 * its terminal state.
 */
public final class StreamBodyPublisher implements Flow.Publisher<byte[]>, Flow.Subscription {

    /**
     * The largest chunk one raw read delivers; also the byte weight of one semantic-layer
     * pull, so the semantic processor's single outstanding request procures one whole chunk
     * instead of one byte.
     */
    public static final int CHUNK_LIMIT = 8 * 1024;

    /**
     * Waiting granularity for the zero-credit park and for re-evaluating the deadlines while no
     * bytes arrive; short enough that cancellation and timeouts register promptly, long enough
     * that an idle reader costs nothing measurable.
     */
    private static final long PARK_SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

    /**
     * The error a subscription arriving after the terminal receives; the run's cause was
     * already latched on the path that set the terminal, so this error reports the ending
     * without deciding it.
     */
    private static final String LATE_SUBSCRIPTION = "the body publisher reached its terminal before this subscription";

    private final InputStream body;
    private final ExecutorService executor;
    private final CancellationContext cancellation;
    private final Clock clock;
    private final Duration idleTimeout;
    private final Instant wallDeadline;

    private final AtomicBoolean subscribed = new AtomicBoolean();
    private final AtomicBoolean terminal = new AtomicBoolean();
    private final AtomicLong demand = new AtomicLong();
    private final AtomicReference<Thread> reader = new AtomicReference<>();
    private volatile Flow.Subscriber<? super byte[]> subscriber;

    /**
     * Creates a publisher over an open body stream.
     *
     * @param body the streaming response body; owned by this publisher from construction on and
     *             closed on every terminal path
     * @param executor caller-owned executor that runs the single reader task; never shut down by
     *                 this publisher
     * @param cancellation shared terminal-cause latch consulted and latched by the reader
     * @param clock time source for both deadlines
     * @param idleTimeout maximum silence between bytes, or {@code null} for no idle limit
     * @param wallDeadline absolute cut-off instant, or {@code null} for no wall limit
     */
    public StreamBodyPublisher(
            InputStream body,
            ExecutorService executor,
            CancellationContext cancellation,
            Clock clock,
            Duration idleTimeout,
            Instant wallDeadline) {
        this.body = Objects.requireNonNull(body, "body");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.idleTimeout = idleTimeout;
        this.wallDeadline = wallDeadline;
    }

    /**
     * Registers {@code subscriber} as the single subscriber, hands it this publisher as its
     * subscription, and starts the reader task. A second subscriber is refused per Flow
     * conventions with {@code onSubscribe} followed by {@code onError} and no effect on the live
     * subscription. A subscription that arrives after the publisher already reached its
     * terminal — a cancelled or watchdog-cut exchange closes before its caller subscribes — or
     * onto an executor that refuses the reader task likewise receives {@code onSubscribe}
     * followed by {@code onError}, because a terminal publisher never throws out of {@code
     * subscribe}: the run's latched cause, not a crash, decides its ending.
     *
     * @throws NullPointerException if {@code subscriber} is {@code null}
     */
    @Override
    public void subscribe(Flow.Subscriber<? super byte[]> newcomer) {
        Objects.requireNonNull(newcomer, "newcomer");
        if (!subscribed.compareAndSet(false, true)) {
            refuse(newcomer);
            return;
        }
        subscriber = newcomer;
        newcomer.onSubscribe(this);
        if (terminal.get()) {
            deliverError(newcomer, new IllegalStateException(LATE_SUBSCRIPTION));
            return;
        }
        try {
            executor.execute(() -> {
                reader.set(Thread.currentThread());
                try {
                    pump(newcomer);
                } finally {
                    reader.set(null);
                }
            });
        } catch (RejectedExecutionException refusedReader) {
            cancellation.latch(CancellationContext.Cause.TRANSPORT_FAILURE);
            beginTerminal();
            closeBodyQuietly();
            deliverError(newcomer, refusedReader);
        }
    }

    /**
     * Adds request credit for the single live subscriber; a saturated counter means unbounded
     * demand. Zero is a legal no-op, and a negative amount fails the subscriber with {@code
     * onError(IllegalArgumentException)} per the Flow rules — latching the subscriber-failure
     * cause first so the terminal path always decides a cause — and is otherwise a no-op once
     * terminal.
     */
    @Override
    public void request(long n) {
        if (n < 0) {
            Flow.Subscriber<? super byte[]> observer = subscriber;
            if (observer != null && beginTerminal()) {
                cancellation.latch(CancellationContext.Cause.SUBSCRIBER_FAILURE);
                closeBodyQuietly();
                deliverError(observer, new IllegalArgumentException("negative request amount: " + n));
            }
            return;
        }
        if (n > 0) {
            demand.accumulateAndGet(n, (current, addition) -> {
                long summed = current + addition;
                return summed < 0 ? Long.MAX_VALUE : summed;
            });
            wakeReader();
        }
    }

    /**
     * Ends the live subscription: latches the close cause, closes the body stream to unblock a
     * pending read, and stops the reader without any further subscriber callback. Repeated calls
     * are no-ops.
     */
    @Override
    public void cancel() {
        cancellation.latch(CancellationContext.Cause.CLOSED);
        beginTerminal();
        closeBodyQuietly();
        wakeReader();
    }

    private void pump(Flow.Subscriber<? super byte[]> target) {
        byte[] window = new byte[CHUNK_LIMIT];
        Instant lastByteAt = clock.instant();
        try {
            while (!terminal.get() && !cancellation.cancelled()) {
                Instant now = clock.instant();
                if (expiredWall(target, now) || expiredIdle(target, now, lastByteAt)) {
                    return;
                }
                long credit = demand.get();
                if (credit <= 0) {
                    parkSlice();
                    continue;
                }
                int read = body.read(window, 0, (int) Math.min(credit, window.length));
                if (read < 0) {
                    completeAtEndOfBody(target);
                    return;
                }
                if (read == 0) {
                    parkSlice();
                    continue;
                }
                consumeDemand(read);
                lastByteAt = clock.instant();
                try {
                    target.onNext(Arrays.copyOf(window, read));
                } catch (RuntimeException subscriberThrown) {
                    failFromSubscriber(target, subscriberThrown);
                    return;
                }
            }
        } catch (IOException streamFailure) {
            // A closed stream after cancel or a deadline latched elsewhere is a cancellation,
            // not a failure to report; anything else fails the subscriber exactly once.
            if (cancellation.cause().isEmpty() && beginTerminal()) {
                deliverError(target, streamFailure);
            }
        } catch (RuntimeException unexpected) {
            failFromSubscriber(target, unexpected);
        } finally {
            terminal.set(true);
            closeBodyQuietly();
        }
    }

    /**
     * Hands a duplicate subscriber a dead subscription and the double-subscription error, using a
     * throwaway JDK submission publisher on the calling thread so this adapter never constructs
     * another subscription object itself.
     */
    private static void refuse(Flow.Subscriber<? super byte[]> newcomer) {
        try (SubmissionPublisher<byte[]> refusal = new SubmissionPublisher<>(Runnable::run, 1)) {
            refusal.subscribe(newcomer);
            refusal.closeExceptionally(new IllegalStateException("this publisher accepts a single subscription"));
        }
    }

    private boolean expiredWall(Flow.Subscriber<? super byte[]> target, Instant now) {
        if (wallDeadline == null || now.isBefore(wallDeadline)) {
            return false;
        }
        if (cancellation.latch(CancellationContext.Cause.WALL_TIMEOUT) && beginTerminal()) {
            deliverError(target, new TimeoutException("wall deadline of " + wallDeadline + " elapsed"));
        }
        return true;
    }

    private boolean expiredIdle(Flow.Subscriber<? super byte[]> target, Instant now, Instant lastByteAt) {
        if (idleTimeout == null || Duration.between(lastByteAt, now).compareTo(idleTimeout) <= 0) {
            return false;
        }
        if (cancellation.latch(CancellationContext.Cause.IDLE_TIMEOUT) && beginTerminal()) {
            deliverError(target, new TimeoutException("idle for " + idleTimeout + " without bytes"));
        }
        return true;
    }

    private void completeAtEndOfBody(Flow.Subscriber<? super byte[]> target) {
        if (cancellation.latch(CancellationContext.Cause.CLOSED) && beginTerminal()) {
            try {
                target.onComplete();
            } catch (RuntimeException ignored) {
                // a terminal callback that throws cannot be reported anywhere else
            }
        }
    }

    private void failFromSubscriber(Flow.Subscriber<? super byte[]> target, RuntimeException thrown) {
        cancellation.latch(CancellationContext.Cause.SUBSCRIBER_FAILURE);
        if (beginTerminal()) {
            deliverError(target, thrown);
        }
    }

    private static void deliverError(Flow.Subscriber<? super byte[]> target, Throwable error) {
        try {
            target.onError(error);
        } catch (RuntimeException ignored) {
            // a failing error callback must not mask the reader's own terminal state
        }
    }

    private boolean beginTerminal() {
        return terminal.compareAndSet(false, true);
    }

    private void consumeDemand(long count) {
        demand.updateAndGet(current -> current == Long.MAX_VALUE ? current : current - count);
    }

    private void parkSlice() {
        LockSupport.parkNanos(PARK_SLICE_NANOS);
    }

    private void closeBodyQuietly() {
        try {
            body.close();
        } catch (IOException ignored) {
            // closing is best-effort cleanup on terminal paths
        }
    }

    private void wakeReader() {
        Thread worker = reader.get();
        if (worker != null) {
            LockSupport.unpark(worker);
        }
    }
}

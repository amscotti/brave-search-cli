package io.amscotti.bravesearch.application.port.out;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.DeadlineWatchdog;
import io.amscotti.bravesearch.application.stream.StreamBodyPublisher;
import io.amscotti.bravesearch.domain.answer.AnswerStreamEvent;
import io.amscotti.bravesearch.domain.metadata.RequestMeta;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The watchdog-release contract of the live streaming exchange: whichever of closing and
 * arming happens last still stops the armed watchdog, so no terminal path leaves a worker
 * that can later fire a deadline cause on a run that already ended.
 */
final class LiveStreamExchangeTest {

    @Test
    void closingAfterArmingStopsTheWatchdog() throws Exception {
        MutableClock clock = new MutableClock();
        CancellationContext run = new CancellationContext();
        AtomicInteger watchdogCloses = new AtomicInteger();
        try (ExecutorService reader = Executors.newSingleThreadExecutor()) {
            LiveStreamExchange exchange = exchange(clock, run, reader);
            DeadlineWatchdog armed = DeadlineWatchdog.armed(
                    watchdogCloses::incrementAndGet,
                    new CancellationContext(),
                    Duration.ofSeconds(1),
                    null,
                    new AtomicReference<>(clock.instant()),
                    clock);
            exchange.armWatchdog(armed);

            exchange.close();
            clock.advance(Duration.ofSeconds(5));

            assertFalse(
                    firedWithinSpinWindow(watchdogCloses),
                    "closing the exchange stops the armed watchdog before its budget can elapse");
            assertEquals(
                    CancellationContext.Cause.CLOSED,
                    run.cause().orElse(null),
                    "the exchange's own close decides the run's cause");
        }
    }

    @Test
    void armingOntoAnAlreadyClosedExchangeStopsTheWatchdog() throws Exception {
        MutableClock clock = new MutableClock();
        CancellationContext run = new CancellationContext();
        AtomicInteger watchdogCloses = new AtomicInteger();
        try (ExecutorService reader = Executors.newSingleThreadExecutor()) {
            LiveStreamExchange exchange = exchange(clock, run, reader);
            exchange.close();

            // the watchdog's idle budget spans a window the clock has not crossed, so its
            // worker can only fire after the advance below — and its context is deliberately
            // unlatched: through the exchange's own latch the already-decided CLOSED cause
            // would mask whether arming stopped the worker or the worker stopped itself
            DeadlineWatchdog lateArmed = DeadlineWatchdog.armed(
                    watchdogCloses::incrementAndGet,
                    new CancellationContext(),
                    Duration.ofSeconds(1),
                    null,
                    new AtomicReference<>(clock.instant()),
                    clock);
            exchange.armWatchdog(lateArmed);
            // give the arming path's stop time to reach the parked worker
            Thread.sleep(200);
            clock.advance(Duration.ofSeconds(5));

            assertFalse(
                    firedWithinSpinWindow(watchdogCloses),
                    "a watchdog armed onto a closed exchange is stopped before it can fire"
                            + " a deadline cause on the ended run");
        }
    }

    @Test
    void closingReleasesEveryOwnedResourceExactlyOnce() throws Exception {
        MutableClock clock = new MutableClock();
        CancellationContext run = new CancellationContext();
        AtomicInteger transportCloses = new AtomicInteger();
        AtomicInteger detaches = new AtomicInteger();
        try (ExecutorService reader = Executors.newSingleThreadExecutor()) {
            LiveStreamExchange exchange = new LiveStreamExchange(
                    new RequestMeta(null, 200, null, List.of(), null),
                    new StreamBodyPublisher(
                            new ByteArrayInputStream(new byte[0]), reader, run, clock, null, null),
                    noSemanticFrames(),
                    run,
                    new AtomicReference<>(clock.instant()),
                    clock::instant,
                    reader,
                    transportCloses::incrementAndGet,
                    detaches::incrementAndGet);

            exchange.close();
            exchange.close();

            assertEquals(1, transportCloses.get(), "the transport closes exactly once");
            assertEquals(1, detaches.get(), "the registry detaches exactly once");
            assertTrue(reader.isShutdown(), "the reader executor is shut down on close");
            assertEquals(
                    CancellationContext.Cause.CLOSED, run.cause().orElse(null), "close latches the run's cause");
        }
    }

    private static LiveStreamExchange exchange(
            MutableClock clock, CancellationContext run, ExecutorService reader) {
        return new LiveStreamExchange(
                new RequestMeta(null, 200, null, List.of(), null),
                new StreamBodyPublisher(
                        new ByteArrayInputStream(new byte[0]), reader, run, clock, null, null),
                noSemanticFrames(),
                run,
                new AtomicReference<>(clock.instant()),
                clock::instant,
                reader,
                () -> {},
                () -> {});
    }

    private static Flow.Publisher<AnswerStreamEvent> noSemanticFrames() {
        return subscriber -> {};
    }

    /**
     * Whether the watchdog fired within the spin window: an elapsed budget reaches its close
     * within a few of the worker's short ticks, so a bounded wait is enough to observe a
     * firing without waiting on one that must never happen.
     */
    private static boolean firedWithinSpinWindow(AtomicInteger watchdogCloses) {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() - deadlineNanos < 0) {
            if (watchdogCloses.get() > 0) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return watchdogCloses.get() > 0;
    }

    /** Clock whose instant moves only when the test advances it. */
    private static final class MutableClock extends Clock {

        private volatile Instant now = Instant.ofEpochSecond(0);

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }
    }
}

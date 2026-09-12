package io.amscotti.bravesearch.application.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Terminal-cause arbitration contracts of the shared cancellation latch. */
final class CancellationContextTest {

    @Test
    void firstLatchWins() throws InterruptedException {
        CancellationContext context = new CancellationContext();
        CancellationContext.Cause[] causes = CancellationContext.Cause.values();
        int racers = 8;
        CyclicBarrier start = new CyclicBarrier(racers);
        List<CancellationContext.Cause> winners = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(racers);
        for (int i = 0; i < racers; i++) {
            CancellationContext.Cause cause = causes[i % causes.length];
            Thread racer =
                    new Thread(
                            () -> {
                                try {
                                    start.await();
                                } catch (Exception e) {
                                    throw new IllegalStateException("start barrier broken", e);
                                }
                                if (context.latch(cause)) {
                                    winners.add(cause);
                                }
                                done.countDown();
                            });
            racer.start();
        }

        assertTrue(done.await(5, TimeUnit.SECONDS), "every racer must finish");
        assertEquals(1, winners.size(), "exactly one racing latch call must win");
        CancellationContext.Cause winner = winners.get(0);

        assertSame(winner, context.cause().orElseThrow(), "cause must be the winning racer's cause");
        for (CancellationContext.Cause late : causes) {
            assertFalse(context.latch(late), "late latch of " + late + " must lose forever");
        }
        assertSame(winner, context.cause().orElseThrow(), "cause must stay stable after every late latch");
        assertTrue(context.cancelled(), "context must read as cancelled once latched");
    }

    @Test
    void repeatLatchIsNoOp() {
        CancellationContext context = new CancellationContext();

        assertEquals(Optional.empty(), context.cause(), "fresh context has no cause");
        assertFalse(context.cancelled(), "fresh context is not cancelled");

        assertTrue(context.latch(CancellationContext.Cause.IDLE_TIMEOUT), "first latch must win");
        assertFalse(context.latch(CancellationContext.Cause.IDLE_TIMEOUT), "second latch of the same cause must lose");
        assertFalse(context.latch(CancellationContext.Cause.CLOSED), "latch of any other cause must lose too");
        assertSame(CancellationContext.Cause.IDLE_TIMEOUT, context.cause().orElseThrow(), "cause must remain the first one");
        assertTrue(context.cancelled(), "context must read as cancelled");
    }

    @Test
    void exitCodeMapping() {
        assertEquals(130, CancellationContext.Cause.SIGINT.exitCode(), "SIGINT maps to the shell's 130");
        assertEquals(0, CancellationContext.Cause.BROKEN_PIPE.exitCode(), "broken pipe is a normal end of stream");
        assertEquals(0, CancellationContext.Cause.CLOSED.exitCode(), "a closed stream is a normal completion");
        assertEquals(6, CancellationContext.Cause.IDLE_TIMEOUT.exitCode(), "idle timeout maps to the stream-failure code");
        assertEquals(6, CancellationContext.Cause.WALL_TIMEOUT.exitCode(), "wall-clock timeout maps to the stream-failure code");
        assertEquals(6, CancellationContext.Cause.SUBSCRIBER_FAILURE.exitCode(), "subscriber failure maps to the stream-failure code");
        assertEquals(6, CancellationContext.Cause.TRANSPORT_FAILURE.exitCode(), "transport failure maps to the stream-failure code");
    }

    @Test
    void awaitUninterruptiblyPreservesInterrupt() throws InterruptedException {
        CancellationContext context = new CancellationContext();
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean returned = new AtomicBoolean(false);
        AtomicBoolean interruptFlagPreserved = new AtomicBoolean(false);
        Thread waiter =
                new Thread(
                        () -> {
                            entered.countDown();
                            context.awaitUninterruptibly();
                            returned.set(true);
                            interruptFlagPreserved.set(Thread.interrupted());
                        });
        waiter.start();

        assertTrue(entered.await(5, TimeUnit.SECONDS), "waiter must reach awaitUninterruptibly");
        Thread.sleep(100);
        assertFalse(returned.get(), "awaitUninterruptibly must block until the first latch");

        waiter.interrupt();
        Thread.sleep(100);
        assertFalse(returned.get(), "an interrupt alone must not release the waiter");

        assertTrue(context.latch(CancellationContext.Cause.SIGINT), "latch must release the waiter");
        waiter.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(waiter.isAlive(), "waiter must return after the first latch");
        assertTrue(returned.get(), "waiter body must complete after the first latch");
        assertTrue(interruptFlagPreserved.get(), "interrupt flag must be set when awaitUninterruptibly returns");
    }
}

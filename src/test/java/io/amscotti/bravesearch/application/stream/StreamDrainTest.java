package io.amscotti.bravesearch.application.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.domain.answer.AnswerStreamEvent;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The terminal-tracking subscriber harness every streaming output mode stands on: it pays
 * unbounded demand, forwards every event to its handler on the delivering thread, records
 * the terminal signal exactly once, and latches a cause on every path so the run awaiting
 * its terminal latch can never hang.
 */
final class StreamDrainTest {

    @Test
    void forwardsEveryEventUnderUnboundedDemandAndCompletes() throws Exception {
        CancellationContext cancellation = new CancellationContext();
        AtomicInteger delivered = new AtomicInteger();
        StreamDrain<AnswerStreamEvent> drain = new StreamDrain<>(cancellation, event -> delivered.incrementAndGet());

        drain.onSubscribe(new ScriptedSubscription());
        drain.onNext(new AnswerStreamEvent.Text("one"));
        drain.onNext(new AnswerStreamEvent.Text("two"));
        drain.onComplete();

        assertEquals(2, delivered.get());
        assertTrue(drain.terminalReached());
        drain.awaitTerminal();
        assertTrue(drain.failure().isEmpty(), "a clean completion records no failure");
        assertEquals(
                Optional.of(CancellationContext.Cause.CLOSED),
                cancellation.cause(),
                "the clean terminal latches the clean-close cause so one wait covers it");
    }

    @Test
    void aHandlerFailureStopsDeliveryLatchesTheSubscriberCauseAndRecordsTheFailure() {
        CancellationContext cancellation = new CancellationContext();
        AtomicInteger delivered = new AtomicInteger();
        IllegalStateException brokenHandler = new IllegalStateException("handler refused");
        StreamDrain<AnswerStreamEvent> drain = new StreamDrain<>(cancellation, event -> {
            delivered.incrementAndGet();
            throw brokenHandler;
        });
        ScriptedSubscription subscription = new ScriptedSubscription();
        drain.onSubscribe(subscription);

        drain.onNext(new AnswerStreamEvent.Text("first"));

        assertEquals(1, delivered.get(), "delivery stops at the failing event");
        assertEquals(Optional.of(CancellationContext.Cause.SUBSCRIBER_FAILURE), cancellation.cause());
        assertTrue(subscription.cancelled, "the failing subscription is cancelled");
        assertThrows(RuntimeException.class, () -> drain.onNext(new AnswerStreamEvent.Text("second")), () ->
                "delivery after a handler failure is refused");
        assertTrue(drain.terminalReached());
        assertEquals(Optional.of(brokenHandler), drain.failure());
    }

    @Test
    void aCauseTheHandlerLatchedFirstWinsOverTheDrainFallback() {
        CancellationContext cancellation = new CancellationContext();
        StreamDrain<AnswerStreamEvent> drain = new StreamDrain<>(cancellation, event -> {
            cancellation.latch(CancellationContext.Cause.BROKEN_PIPE);
            throw new IllegalStateException("writer broke");
        });
        drain.onSubscribe(new ScriptedSubscription());

        drain.onNext(new AnswerStreamEvent.Text("any"));

        assertEquals(Optional.of(CancellationContext.Cause.BROKEN_PIPE), cancellation.cause());
        assertTrue(drain.terminalReached());
    }

    @Test
    void anUpstreamErrorRecordsTheFailureAndGuaranteesACause() {
        CancellationContext cancellation = new CancellationContext();
        StreamDrain<AnswerStreamEvent> drain = new StreamDrain<>(cancellation, event -> {});

        drain.onSubscribe(new ScriptedSubscription());
        drain.onError(new RuntimeException("body broke"));

        assertTrue(drain.terminalReached());
        assertInstanceOf(RuntimeException.class, drain.failure().orElseThrow());
        assertEquals(Optional.of(CancellationContext.Cause.TRANSPORT_FAILURE), cancellation.cause());
    }

    @Test
    void stopNowCancelsTheSubscriptionWithoutADeliveredTerminal() {
        CancellationContext cancellation = new CancellationContext();
        StreamDrain<AnswerStreamEvent> drain = new StreamDrain<>(cancellation, event -> {});
        ScriptedSubscription subscription = new ScriptedSubscription();
        drain.onSubscribe(subscription);

        cancellation.latch(CancellationContext.Cause.SIGINT);
        drain.stopNow();

        assertTrue(subscription.cancelled, "the cancelled exchange stops its subscription");
        assertTrue(drain.terminalReached(), "the awaiting side still observes a terminal");
        assertTrue(drain.failure().isEmpty(), "no failure is invented for a cancelled run");
    }

    @Test
    void stopNowWaitsForAnInFlightDeliveryToClearBeforeReturning() throws Exception {
        CancellationContext cancellation = new CancellationContext();
        CountDownLatch deliveryStarted = new CountDownLatch(1);
        CountDownLatch releaseDelivery = new CountDownLatch(1);
        List<String> ordering = new CopyOnWriteArrayList<>();
        StreamDrain<AnswerStreamEvent> drain = new StreamDrain<>(cancellation, event -> {
            deliveryStarted.countDown();
            try {
                releaseDelivery.await();
            } catch (InterruptedException waiting) {
                Thread.currentThread().interrupt();
            }
            ordering.add("delivery cleared");
        });
        Thread reader = Thread.ofPlatform().start(() -> drain.onNext(new AnswerStreamEvent.Text("one")));
        assertTrue(deliveryStarted.await(5, TimeUnit.SECONDS), "the delivery must be in flight");

        Thread stopper = Thread.ofPlatform().start(() -> {
            drain.stopNow();
            ordering.add("stopNow returned");
        });

        Thread.sleep(200);
        assertTrue(stopper.isAlive(), "stopNow must hold while a delivery is in flight");
        releaseDelivery.countDown();
        reader.join(5000);
        stopper.join(5000);
        assertFalse(stopper.isAlive(), "stopNow must return once the in-flight delivery cleared");
        assertEquals(List.of("delivery cleared", "stopNow returned"), ordering, "the delivery clears before stopNow returns");
        assertThrows(
                IllegalStateException.class,
                () -> drain.onNext(new AnswerStreamEvent.Text("two")),
                "no delivery may start after stopNow returns");
    }

    @Test
    void awaitingTheTerminalSurvivesInterruptionWithoutLosingTheSignal() throws Exception {
        CancellationContext cancellation = new CancellationContext();
        StreamDrain<AnswerStreamEvent> drain = new StreamDrain<>(cancellation, event -> {});
        drain.onSubscribe(new ScriptedSubscription());

        Thread awaiter = Thread.ofPlatform().start(drain::awaitTerminal);
        awaiter.join(50);
        awaiter.interrupt();
        drain.onComplete();
        awaiter.join(2000);
        assertFalse(awaiter.isAlive(), "the await returns once terminal even after an interrupt");
    }

    /** Records request and cancellation signals; the harness only ever cancels. */
    private static final class ScriptedSubscription implements Flow.Subscription {

        volatile boolean cancelled;

        @Override
        public void request(long n) {}

        @Override
        public void cancel() {
            cancelled = true;
        }
    }
}

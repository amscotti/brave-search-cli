package io.amscotti.bravesearch.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.application.stream.CancellationContext;
import org.junit.jupiter.api.Test;

/**
 * Live-exchange bookkeeping of the interrupt registry: every registered context receives the
 * interrupt cause when the process is interrupted, deregistered contexts stay untouched, and an
 * empty registry tolerates interruption.
 */
final class ExchangeRegistryTest {

    @Test
    void interruptLatchesSigintOnEveryLiveContext() {
        ExchangeRegistry registry = new ExchangeRegistry();
        CancellationContext first = new CancellationContext();
        CancellationContext second = new CancellationContext();
        registry.register(first);
        registry.register(second);

        boolean interrupted = registry.interruptLiveExchanges();

        assertTrue(interrupted, "at least one context was live to interrupt");
        assertEquals(CancellationContext.Cause.SIGINT, first.cause().orElseThrow());
        assertEquals(CancellationContext.Cause.SIGINT, second.cause().orElseThrow());
    }

    @Test
    void terminationLatchesSigtermOnEveryLiveContextAndLosesToAnEarlierInterrupt() {
        ExchangeRegistry registry = new ExchangeRegistry();
        CancellationContext first = new CancellationContext();
        CancellationContext second = new CancellationContext();
        registry.register(first);
        registry.register(second);
        first.latch(CancellationContext.Cause.SIGINT);

        boolean terminated = registry.terminateLiveExchanges();

        assertTrue(terminated, "at least one context was live to terminate");
        assertEquals(
                CancellationContext.Cause.SIGINT,
                first.cause().orElseThrow(),
                "a cause that won the latch earlier survives a later termination");
        assertEquals(CancellationContext.Cause.SIGTERM, second.cause().orElseThrow());
    }

    @Test
    void deregistrationDetachesAContextFromLaterInterrupts() {
        ExchangeRegistry registry = new ExchangeRegistry();
        CancellationContext closed = new CancellationContext();
        CancellationContext live = new CancellationContext();

        Runnable detachClosed = registry.register(closed);
        registry.register(live);
        detachClosed.run();

        registry.interruptLiveExchanges();

        assertTrue(closed.cause().isEmpty(), "a deregistered context must not receive the cause");
        assertEquals(CancellationContext.Cause.SIGINT, live.cause().orElseThrow());
    }

    @Test
    void interruptOnEmptyRegistryIsAToleratedNoOp() {
        ExchangeRegistry registry = new ExchangeRegistry();

        boolean interrupted = registry.interruptLiveExchanges();

        org.junit.jupiter.api.Assertions.assertFalse(interrupted, "empty registry reports no interrupted exchanges");
        CancellationContext latecomer = new CancellationContext();
        registry.register(latecomer);
        assertTrue(latecomer.cause().isEmpty(), "a context registered after interruption starts clean");
    }

    @Test
    void terminateOnEmptyRegistryReportsNoExchangesTerminated() {
        ExchangeRegistry registry = new ExchangeRegistry();

        boolean terminated = registry.terminateLiveExchanges();

        org.junit.jupiter.api.Assertions.assertFalse(terminated, "empty registry reports no terminated exchanges");
    }

    @Test
    void interruptKeepsAnAlreadyTerminalContextStable() {
        ExchangeRegistry registry = new ExchangeRegistry();
        CancellationContext closedByPipe = new CancellationContext();
        registry.register(closedByPipe);
        closedByPipe.latch(CancellationContext.Cause.BROKEN_PIPE);

        registry.interruptLiveExchanges();

        assertEquals(
                CancellationContext.Cause.BROKEN_PIPE,
                closedByPipe.cause().orElseThrow(),
                "the first terminal cause must survive a later interrupt");
    }
}

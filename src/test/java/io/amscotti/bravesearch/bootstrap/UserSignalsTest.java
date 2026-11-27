package io.amscotti.bravesearch.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.application.stream.CancellationContext;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * What the process does when a user signal arrives: the first delivery with a live exchange
 * latches its cause and lets the run finish; a signal with nothing live to latch terminates
 * the process; and a repeated signal terminates it at once, because the user has asked twice,
 * while a different signal arriving during the wind-down only latches — the first cause wins.
 */
final class UserSignalsTest {

    @Test
    void theFirstInterruptWithALiveExchangeLatchesItAndLetsTheRunFinish() {
        ExchangeRegistry exchanges = new ExchangeRegistry();
        CancellationContext live = new CancellationContext();
        Runnable detach = exchanges.register(live);
        UserSignals signals = new UserSignals(exchanges);

        assertFalse(signals.interrupt(), "the first delivery hands the run to its own control flow");

        assertEquals(Optional.of(CancellationContext.Cause.SIGINT), live.cause());
        detach.run();
    }

    @Test
    void aSignalWithNoLiveExchangeTerminatesTheProcessAtOnce() {
        UserSignals signals = new UserSignals(new ExchangeRegistry());

        assertTrue(signals.interrupt(), "an interrupt with nothing to latch has nothing to wait for");
        assertTrue(signals.terminate(), "a termination with nothing to latch likewise");
    }

    @Test
    void aRepeatedInterruptTerminatesAtOnceWhileTheLatchedRunIsStillWindingDown() {
        ExchangeRegistry exchanges = new ExchangeRegistry();
        CancellationContext live = new CancellationContext();
        Runnable detach = exchanges.register(live);
        UserSignals signals = new UserSignals(exchanges);

        assertFalse(signals.interrupt(), "the first delivery latches");
        assertTrue(signals.interrupt(), "the second delivery ends the wait: the user asked twice");
        assertEquals(Optional.of(CancellationContext.Cause.SIGINT), live.cause(), "the first cause stays latched");
        detach.run();
    }

    @Test
    void aRepeatedTerminationTerminatesAtOnceWhileTheLatchedRunIsStillWindingDown() {
        ExchangeRegistry exchanges = new ExchangeRegistry();
        Runnable detach = exchanges.register(new CancellationContext());
        UserSignals signals = new UserSignals(exchanges);

        assertFalse(signals.terminate());
        assertTrue(signals.terminate());
        detach.run();
    }

    @Test
    void aDifferentSignalDuringTheWindDownOnlyLatchesBecauseTheFirstCauseWins() {
        ExchangeRegistry exchanges = new ExchangeRegistry();
        CancellationContext live = new CancellationContext();
        Runnable detach = exchanges.register(live);
        UserSignals signals = new UserSignals(exchanges);

        assertFalse(signals.interrupt(), "the interrupt latches");
        assertFalse(signals.terminate(), "a termination during the interrupt's wind-down is not a repeat: it only latches");
        assertEquals(Optional.of(CancellationContext.Cause.SIGINT), live.cause(), "the run keeps the interrupt's status");
        detach.run();
    }

    @Test
    void eachSignalCountsItsOwnDeliveries() {
        ExchangeRegistry exchanges = new ExchangeRegistry();
        Runnable detach = exchanges.register(new CancellationContext());
        UserSignals signals = new UserSignals(exchanges);

        assertFalse(signals.terminate(), "a first termination latches");
        assertFalse(signals.interrupt(), "an interrupt afterwards is a first delivery of its own kind");
        detach.run();
    }
}

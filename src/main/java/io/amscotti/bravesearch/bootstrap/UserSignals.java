package io.amscotti.bravesearch.bootstrap;

import java.util.Objects;

/**
 * What the process does when a user signal arrives.
 *
 * <p>The first SIGINT or SIGTERM delivered while an exchange is live latches its cause on
 * every live exchange and lets the run finish under its own control flow: the handler never
 * blocks the signal thread and never decides the run's status. A signal with no live
 * exchange to latch terminates the process itself. A repeated signal — a second SIGINT while
 * an interrupted run is still winding down, or a second SIGTERM likewise — terminates the
 * process at once with that signal's conventional status: the user has asked twice, and no
 * run is allowed to outlast a second request. A different signal arriving during that
 * wind-down only latches, because the first cause latched decides the run's status.
 */
final class UserSignals {

    private final ExchangeRegistry exchanges;

    private boolean interruptDelivered;

    private boolean terminateDelivered;

    UserSignals(ExchangeRegistry exchanges) {
        this.exchanges = Objects.requireNonNull(exchanges, "exchanges");
    }

    /**
     * An interrupt arrived.
     *
     * @return whether the process must terminate at once with the interrupt status
     */
    synchronized boolean interrupt() {
        boolean latched = !interruptDelivered && exchanges.interruptLiveExchanges();
        interruptDelivered |= latched;
        return !latched;
    }

    /**
     * A termination arrived.
     *
     * @return whether the process must terminate at once with the termination status
     */
    synchronized boolean terminate() {
        boolean latched = !terminateDelivered && exchanges.terminateLiveExchanges();
        terminateDelivered |= latched;
        return !latched;
    }
}

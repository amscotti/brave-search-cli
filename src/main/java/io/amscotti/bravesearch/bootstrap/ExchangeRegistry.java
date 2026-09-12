package io.amscotti.bravesearch.bootstrap;

import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.CancellationRegistry;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The process-wide set of cancellation contexts belonging to live streaming exchanges.
 *
 * <p>Instances are ordinary objects on purpose: one process owns one registry, the bootstrap
 * passes it to the commands that drive streams, and each run registers its context when the
 * exchange opens and detaches it on close through the returned handle. The interrupt entry point
 * latches the SIGINT cause on every context still live and is wait-free, so a signal handler can
 * call it without ever blocking the delivering thread; contexts that already latched a terminal
 * cause keep that cause, because {@link CancellationContext#latch} only lets the first cause win.
 */
public final class ExchangeRegistry implements CancellationRegistry {

    private final CopyOnWriteArrayList<CancellationContext> live = new CopyOnWriteArrayList<>();

    /** Tracks {@code cancellation} until the returned handle is run. */
    @Override
    public Runnable register(CancellationContext cancellation) {
        Objects.requireNonNull(cancellation, "cancellation");
        live.add(cancellation);
        return () -> live.remove(cancellation);
    }

    /**
     * Latches the SIGINT cause on every context that is still live, without blocking.
     *
     * @return true if at least one live context was interrupted, false if no contexts were live
     */
    public boolean interruptLiveExchanges() {
        boolean any = false;
        for (CancellationContext cancellation : live) {
            cancellation.latch(CancellationContext.Cause.SIGINT);
            any = true;
        }
        return any;
    }

    /**
     * Latches the SIGTERM cause on every context that is still live, without blocking.
     *
     * @return true if at least one live context was terminated, false if no contexts were live
     */
    public boolean terminateLiveExchanges() {
        boolean any = false;
        for (CancellationContext cancellation : live) {
            cancellation.latch(CancellationContext.Cause.SIGTERM);
            any = true;
        }
        return any;
    }
}

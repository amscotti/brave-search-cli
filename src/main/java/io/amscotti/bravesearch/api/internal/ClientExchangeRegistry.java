package io.amscotti.bravesearch.api.internal;

import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.CancellationRegistry;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The library client's own attachment point for the cancellation contexts of its in-flight
 * streaming opens. Closing the client closes this registry, latching {@code CLOSED} on every
 * tracked context so an in-flight stream open unblocks promptly instead of waiting out the
 * headers-phase ceiling; any context registered after close is latched immediately.
 */
final class ClientExchangeRegistry implements CancellationRegistry, AutoCloseable {

    private final CopyOnWriteArrayList<CancellationContext> live = new CopyOnWriteArrayList<>();

    private final AtomicBoolean closed = new AtomicBoolean();

    /** Tracks {@code cancellation} until the returned handle is run; the handle is safe to run twice. */
    @Override
    public Runnable register(CancellationContext cancellation) {
        Objects.requireNonNull(cancellation, "cancellation");
        live.add(cancellation);
        if (closed.get()) {
            cancellation.latch(CancellationContext.Cause.CLOSED);
            live.remove(cancellation);
            return () -> {};
        }
        return () -> live.remove(cancellation);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (CancellationContext cancellation : live) {
            cancellation.latch(CancellationContext.Cause.CLOSED);
        }
        live.clear();
    }
}

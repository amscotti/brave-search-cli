package io.amscotti.bravesearch.api.internal;

import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.CancellationRegistry;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The library client's own attachment point for the cancellation contexts of its live
 * streaming exchanges. The process-wide signal registry the bootstrap owns has no library
 * counterpart — a library consumer owns interruption policy themselves — so this registry
 * only keeps the contexts the streaming gateway registers, detaching each on its exchange's
 * terminal path.
 */
final class ClientExchangeRegistry implements CancellationRegistry {

    private final CopyOnWriteArrayList<CancellationContext> live = new CopyOnWriteArrayList<>();

    /** Tracks {@code cancellation} until the returned handle is run; the handle is safe to run twice. */
    @Override
    public Runnable register(CancellationContext cancellation) {
        Objects.requireNonNull(cancellation, "cancellation");
        live.add(cancellation);
        return () -> live.remove(cancellation);
    }
}

package io.amscotti.bravesearch.application.port.out;

import io.amscotti.bravesearch.domain.error.Outcome;
import java.util.Objects;

/**
 * Outbound port of one fully parsed remote invocation.
 *
 * <p>The single exchange is the primary contract: one invocation, one self-contained transport
 * lifecycle. A sequential multi-request run — a page walk, a chunk fan-out — instead opens a
 * {@link RemoteWalk} over its scope, so every request of that run rides one transport
 * lifecycle and keeps its connection to the peer.
 *
 * <p>The default walk opens nothing: each of its dispatches is a self-contained exchange, the
 * shape an in-memory double already has. A composition whose exchanges open real transports
 * overrides it to share that transport across the walk.
 *
 * @param <Req> the endpoint's validated domain request
 * @param <Res> the endpoint's completed-exchange type
 */
@FunctionalInterface
public interface RemoteExchange<Req, Res> {

    /**
     * Performs the exchange of {@code invocation} as a self-contained lifecycle.
     *
     * @throws NullPointerException when {@code invocation} is null
     */
    Outcome<Res> dispatch(RemoteDispatch<Req> invocation);

    /**
     * Opens the exchanges of one sequential multi-request run over the scope of {@code scope}:
     * its origin, credential, budgets, version pin, and cancellation latch apply to every
     * request of the run, and only the request varies per dispatch. The caller dispatches
     * strictly sequentially and closes the walk when the run ends.
     *
     * @throws NullPointerException when {@code scope} is null
     */
    default RemoteWalk<Req, Res> openWalk(RemoteDispatch<Req> scope) {
        Objects.requireNonNull(scope, "scope");
        return new RemoteWalk<>() {

            @Override
            public Outcome<Res> dispatch(Req request) {
                return RemoteExchange.this.dispatch(scope.withRequest(request));
            }

            @Override
            public void close() {
                // every dispatch closed its own lifecycle: the default walk owns nothing
            }
        };
    }
}

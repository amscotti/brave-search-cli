package io.amscotti.bravesearch.application.port.out;

import io.amscotti.bravesearch.domain.error.Outcome;

/**
 * The exchanges of one sequential multi-request run, opened from a {@link RemoteExchange}:
 * every dispatch sends one request under the walk's shared scope, and {@link #close()}
 * releases the one transport lifecycle the whole run rode. Requests are never in flight at
 * once — the caller dispatches strictly sequentially — because parallel requests would burst
 * a user's quota.
 *
 * @param <Req> the endpoint's validated domain request
 * @param <Res> the endpoint's completed-exchange type
 */
public interface RemoteWalk<Req, Res> extends AutoCloseable {

    /**
     * Sends one request of the run under the walk's scope.
     *
     * @throws NullPointerException when {@code request} is null
     */
    Outcome<Res> dispatch(Req request);

    /** Releases the walk's transport lifecycle; never throws. */
    @Override
    void close();
}

package io.amscotti.bravesearch.api;

import java.util.concurrent.Flow;

/**
 * One open public Answers stream: the projected frame publisher, the cancellation handle,
 * and the close action.
 *
 * <p>Subscription rules, inherited from the exchange underneath: {@link #frames()} hands
 * out the same cold publisher on every call; it accepts exactly one subscription, a second
 * subscriber is refused with an {@code IllegalStateException} through reactive-streams
 * conventions; a subscriber that arrives after the stream already terminated — the handle
 * was closed before any subscription — receives the terminal signal immediately instead of
 * hanging; demand travels through verbatim — one requested item delivers one projected
 * frame; framing noise consumes its pull on a transparent refill drained without recursion;
 * subscriber callbacks are strictly serialized and never concurrent; terminal failure
 * arrives exactly once as {@link AnswersStreamException} — whose {@code kind()} carries the
 * stable failure category — through {@code onError}.
 *
 * <p>Lifecycle: the handle releases its owning client's reference as soon as its stream
 * terminates — by completion, failure, or close — but only {@link #close()} latches the
 * closed cause and releases the exchange's resources, so consumers must close every handle
 * (try-with-resources) even after a natural termination. Cancellation and close are
 * idempotent, end the run without a failure signal, and release every resource the
 * exchange owns.
 */
public interface AnswersStreamHandle extends AutoCloseable {

    /** The single-subscription publisher of projected answer frames; the same instance on every call. */
    Flow.Publisher<PublicStreamFrame> frames();

    /** The public cancellation handle of this exchange. */
    StreamCancellation cancellation();

    /**
     * Ends the exchange and releases its resources; idempotent. Closing signals nothing to
     * a live subscriber beyond what the reactive-streams cancellation rules prescribe.
     */
    @Override
    void close();
}

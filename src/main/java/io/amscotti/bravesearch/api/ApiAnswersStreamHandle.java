package io.amscotti.bravesearch.api;

import io.amscotti.bravesearch.api.internal.OpenedAnswersStream;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

/**
 * The public handle around one opened streaming exchange: the memoized frame projection, the
 * public cancellation view of the exchange's terminal-cause latch, and an idempotent close
 * that detaches from the owning client, latches the closed cause, and releases every
 * resource the exchange owns.
 *
 * <p>The handle detaches from its owning client as soon as its stream terminates — by
 * completion, by failure, or by close — so a naturally completed stream releases the
 * client's reference without waiting for close. Closing is still required by contract: it
 * latches the closed cause and releases the exchange's resources, and only close does.
 */
final class ApiAnswersStreamHandle implements AnswersStreamHandle {

    private final OpenedAnswersStream stream;

    private final AtomicBoolean closed = new AtomicBoolean();

    private final Flow.Publisher<PublicStreamFrame> frames;

    private final AtomicReference<StreamCancellation> cancellation = new AtomicReference<>();

    private volatile Runnable detachFromClient = () -> {};

    ApiAnswersStreamHandle(OpenedAnswersStream stream, Function<String, JsonNode> tagPayloadReader) {
        this.stream = Objects.requireNonNull(stream, "stream");
        Objects.requireNonNull(tagPayloadReader, "tagPayloadReader");
        this.frames = new PublicFramePublisher(stream.semanticFrames(), tagPayloadReader, this::detach);
    }

    /**
     * Registers the owning client's detach action, run when this handle's stream terminates
     * or the handle closes.
     */
    void detachFromClient(Runnable detachFromClient) {
        this.detachFromClient = Objects.requireNonNull(detachFromClient, "detachFromClient");
    }

    private void detach() {
        detachFromClient.run();
    }

    @Override
    public Flow.Publisher<PublicStreamFrame> frames() {
        return frames;
    }

    @Override
    public StreamCancellation cancellation() {
        cancellation.compareAndSet(null, new ApiCancellation());
        return cancellation.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        detach();
        ((PublicFramePublisher) frames).terminatedWithoutSubscription();
        // latch first so a racing reader observes the closed cause, then release everything
        stream.latchClosed().run();
        stream.release().run();
    }

    /** The public cancellation view: end the stream through the same path close takes. */
    private final class ApiCancellation implements StreamCancellation {

        @Override
        public void cancel() {
            close();
        }

        @Override
        public boolean cancelled() {
            return stream.cancelled().getAsBoolean();
        }
    }
}

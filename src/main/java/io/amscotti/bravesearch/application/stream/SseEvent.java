package io.amscotti.bravesearch.application.stream;

import java.util.Objects;

/**
 * One dispatched server-sent event: the newline-joined payload of every {@code data} line of its
 * block, the block's {@code event} name if it carried one, and the most recently seen {@code id}
 * and {@code retry} values at dispatch time. Every member except {@code data} may be {@code
 * null}; a block without a {@code data} line never dispatches at all.
 *
 * <p>The event vocabulary of the streaming run lives here, beside the cancellation machinery it
 * reports through, so the transport adapter that produces events and every consumer of them
 * share one type without any adapter instantiation crossing a composition boundary.
 */
public record SseEvent(String name, String data, String lastEventId, Long retryMillis) {

    public SseEvent {
        Objects.requireNonNull(data, "data");
    }
}

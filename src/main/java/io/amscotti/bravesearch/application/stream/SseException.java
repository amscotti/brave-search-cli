package io.amscotti.bravesearch.application.stream;

import io.amscotti.bravesearch.domain.error.ClassifiedFailure;
import io.amscotti.bravesearch.domain.error.FailureKind;

/**
 * Typed failures of the incremental event-stream parser. Every diagnostic is content-free: it
 * names the violated rule and its bound, never the bytes that violated them, because wire text
 * is untrusted input. After any of these surfaces, the parser that threw one is finished and
 * rejects further input.
 *
 * <p>The failure vocabulary of the streaming run lives here, beside the cancellation machinery
 * that bounds breaches latch, so the transport adapter can raise these without any adapter
 * instantiation crossing a composition boundary.
 */
public sealed class SseException extends RuntimeException implements ClassifiedFailure {

    SseException(String message) {
        super(message);
    }

    SseException(String message, Throwable cause) {
        super(message, cause);
    }

    /** An event-stream rule violation is a malformed stream, never a transport or upstream one. */
    @Override
    public FailureKind kind() {
        return FailureKind.MALFORMED;
    }

    /** A line or an event block grew past its injected byte ceiling. */
    public static final class Overflow extends SseException {

        public Overflow(String message) {
            super(message);
        }
    }

    /**
     * The event listener itself threw while receiving a dispatched event; the original failure
     * rides as the cause. The parser that wrapped it is finished and never re-delivers the
     * event, so a caller that resumes feeding cannot double-dispatch.
     */
    public static final class ListenerFailed extends SseException {

        public ListenerFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** A completed line's value was not well-formed UTF-8. */
    public static final class Encoding extends SseException {

        public Encoding(String message) {
            super(message);
        }
    }

    /** A data event dispatched after the stream's terminal marker had already dispatched. */
    public static final class DataAfterTerminal extends SseException {

        public DataAfterTerminal(String message) {
            super(message);
        }
    }

    /** The stream ended before its terminal marker dispatched. */
    public static final class Incomplete extends SseException {

        public Incomplete(String message) {
            super(message);
        }
    }
}

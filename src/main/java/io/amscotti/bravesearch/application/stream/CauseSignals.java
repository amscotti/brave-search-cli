package io.amscotti.bravesearch.application.stream;

import io.amscotti.bravesearch.domain.error.FailureSignal;
import java.util.Optional;

/**
 * The bridge from streaming terminal causes to the shared failure-signal vocabulary.
 *
 * <p>User interruption and user termination each carry their own signal; every way a stream
 * can fail — an idle or wall-clock deadline, a subscriber failure, a transport failure — is a
 * transport condition; and the two normal ends of a stream, a downstream broken pipe and a
 * clean close, are successes that carry no failure signal at all. Keeping this mapping next to
 * the cause vocabulary means the CLI exit mapping and the streaming latch can never drift
 * apart.
 */
public final class CauseSignals {

    private CauseSignals() {}

    /**
     * The failure signal of {@code cause}, or empty when the cause is one of the successful
     * ends of a stream.
     */
    public static Optional<FailureSignal> failureSignal(CancellationContext.Cause cause) {
        return switch (cause) {
            case SIGINT -> Optional.of(FailureSignal.INTERRUPTED);
            case SIGTERM -> Optional.of(FailureSignal.TERMINATED);
            case IDLE_TIMEOUT, WALL_TIMEOUT, SUBSCRIBER_FAILURE, TRANSPORT_FAILURE ->
                    Optional.of(FailureSignal.TRANSPORT);
            case BROKEN_PIPE, CLOSED -> Optional.empty();
        };
    }
}

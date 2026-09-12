package io.amscotti.bravesearch.api.internal;

import io.amscotti.bravesearch.domain.answer.AnswerStreamEvent;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.function.BooleanSupplier;

/**
 * One opened streaming exchange as the composition hands it to the public package: the
 * semantic-frames publisher and the terminal-cause machinery, each reduced to a plain
 * function over domain or JDK types.
 *
 * <p>The record is deliberately free of {@code api} types and of the internal exchange and
 * cancellation-latch interfaces: the public package constructs its public handle from these
 * pieces without any internal application type ever appearing in a public signature, and
 * this package never depends on the public one, so the package hierarchy stays cycle-free.
 *
 * @param semanticFrames the exchange's semantic event publisher, cold and single-subscription
 * @param latchClosed latches the closed cause of the run, first cause wins
 * @param cancelled reports whether any terminal cause has latched
 * @param release releases every resource the exchange owns; idempotent at the exchange level
 */
public record OpenedAnswersStream(
        Flow.Publisher<AnswerStreamEvent> semanticFrames,
        Runnable latchClosed,
        BooleanSupplier cancelled,
        Runnable release) {

    public OpenedAnswersStream {
        Objects.requireNonNull(semanticFrames, "semanticFrames");
        Objects.requireNonNull(latchClosed, "latchClosed");
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(release, "release");
    }
}

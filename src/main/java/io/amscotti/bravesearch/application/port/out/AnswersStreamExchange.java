package io.amscotti.bravesearch.application.port.out;

import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.domain.answer.AnswerStreamEvent;
import io.amscotti.bravesearch.domain.metadata.RequestMeta;
import java.time.Instant;
import java.util.concurrent.Flow;

/**
 * One open streaming answers exchange: the two representations of its single decoded body,
 * the shared terminal-cause latch of the run, the metadata observed at header time, and the
 * close action that releases every resource the exchange owns.
 *
 * <p>One HTTP exchange publishes one body, and the two accessors are two views of it, not two
 * streams: {@link #decodedRawFrames()} is the raw decoded-byte publisher — the body's SSE
 * bytes as they were read, bypassing every parser — while {@link #semanticFrames()} is the
 * same body decoded into semantic events by a subscriber-side processor standing on that raw
 * publisher. Exactly one representation may be subscribed per exchange, because the
 * underlying publisher accepts a single subscription; a caller selects its representation
 * before subscribing.
 *
 * <p>The exchange owns its reader executor and its transport and releases both — together
 * with the raw publisher's own terminal cleanup and the registry detachment — on every
 * terminal path: {@link #close()}, a fired deadline, and the watchdog's cut all converge on
 * the same release. The two progress instants exist for verbose diagnostics: the transport
 * mark advances on every decoded body byte, the semantic mark on every decoded event.
 */
public interface AnswersStreamExchange extends AutoCloseable {

    /** The stream-open snapshot captured at header time: status, identifiers, rate limits. */
    RequestMeta openMeta();

    /**
     * The raw decoded-byte publisher of this exchange's body: demand-driven SSE bytes,
     * bypassing semantic parsing entirely — the representation raw output modes consume.
     */
    Flow.Publisher<byte[]> decodedRawFrames();

    /**
     * The semantic event publisher of this exchange's body: the same decoded bytes, decoded
     * into answer events by the subscriber-side processor — the representation renderers
     * consume.
     */
    Flow.Publisher<AnswerStreamEvent> semanticFrames();

    /** The run's shared terminal-cause latch; the first terminal cause decides the outcome. */
    CancellationContext cancellation();

    /** The instant of the last decoded body byte; the exchange's opening instant before any. */
    Instant lastTransportActivity();

    /** The instant of the last decoded semantic event, or {@code null} before the first. */
    Instant lastSemanticProgress();

    /** Ends the exchange on every path: cancels the body, its reader, the transport, and the registry attachment. */
    @Override
    void close();
}

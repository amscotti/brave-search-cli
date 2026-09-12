package io.amscotti.bravesearch.api.internal;

import io.amscotti.bravesearch.adapter.bravehttp.json.AnswerTagDecoder;
import io.amscotti.bravesearch.adapter.bravehttp.sse.AnswerStreamProcessor;
import io.amscotti.bravesearch.adapter.bravehttp.sse.SemanticStreamAssembly;
import io.amscotti.bravesearch.adapter.bravehttp.sse.SseEventParser;
import java.time.Clock;

/**
 * The one standard semantic-stream wiring both composition roots install, so the CLI process
 * and the embedded library decode the same stream bytes into the same events by construction:
 * neither root can widen, narrow, or re-configure the parser, decoder, or processor bounds
 * without changing them for both.
 *
 * <p>The bounds are the adapter families' documented ceilings — the SSE line and event
 * ceilings of {@link SseEventParser} and the tag-payload byte ceiling of {@link
 * AnswerTagDecoder} — read from their constants, never re-spelled here. Composition roots may
 * call this factory (both {@code api.internal} and {@code bootstrap} are composition roots);
 * no other class may, because the factory instantiates the concrete stream adapters.
 */
public final class StandardSemanticStream {

    private StandardSemanticStream() {}

    /**
     * The standard assembly: one processor over the raw frames, armed with the SSE parser and
     * the tag decoder at their documented ceilings, on the system UTC clock.
     */
    public static SemanticStreamAssembly standard() {
        return (rawFrames, cancellation) -> {
            AnswerStreamProcessor processor = new AnswerStreamProcessor(rawFrames, cancellation, Clock.systemUTC());
            return processor.armedWith(
                    new SseEventParser(
                            processor::onDispatchedEvent,
                            cancellation,
                            SseEventParser.DEFAULT_MAX_LINE_BYTES,
                            SseEventParser.DEFAULT_MAX_EVENT_BYTES),
                    new AnswerTagDecoder(cancellation, AnswerTagDecoder.DEFAULT_MAX_TAG_PAYLOAD_BYTES));
        };
    }
}

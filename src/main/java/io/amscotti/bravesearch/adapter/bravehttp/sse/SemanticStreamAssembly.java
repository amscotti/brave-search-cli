package io.amscotti.bravesearch.adapter.bravehttp.sse;

import io.amscotti.bravesearch.application.stream.CancellationContext;
import java.util.concurrent.Flow;

/**
 * Builds the semantic layer of one Answers stream: the stateful transport parser, the delta
 * decoder, and the processor that wires them over the run's raw body publisher.
 *
 * <p>The implementation is composition-root code — a lambda or method whose enclosing class
 * is a composition root — because the parser, the decoder, and the processor are concrete
 * adapters and only composition roots instantiate concrete adapters. The streaming gateway
 * holds one assembly and calls it once per open exchange; the returned processor arrives
 * fully armed, so nothing downstream ever constructs an adapter.
 */
@FunctionalInterface
public interface SemanticStreamAssembly {

    /**
     * Assembles the armed semantic layer for one exchange.
     *
     * @param rawFrames the exchange's single raw decoded-byte publisher
     * @param cancellation the run's shared terminal-cause latch
     * @return the armed processor, ready for its single subscription
     */
    AnswerStreamProcessor assemble(Flow.Publisher<byte[]> rawFrames, CancellationContext cancellation);
}

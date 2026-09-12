package io.amscotti.bravesearch.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.amscotti.bravesearch.application.stream.AbruptEofException;
import io.amscotti.bravesearch.application.stream.SseException;
import io.amscotti.bravesearch.domain.answer.AnswerDecodeException;
import io.amscotti.bravesearch.domain.error.FailureKind;
import org.junit.jupiter.api.Test;

/**
 * The terminal failure of a public Answers stream carries the stable failure category: the
 * exception's kind round-trips for every kind a stream failure can be, the typed internal
 * failures classify through their own declared category, an unclassified throwable reads as a
 * transport failure, and the redacted diagnostic and the absent internal cause both survive.
 */
final class AnswersStreamExceptionTest {

    @Test
    void everyFailureKindRoundTripsThroughTheException() {
        for (FailureKind kind : FailureKind.values()) {
            AnswersStreamException exception = new AnswersStreamException("diagnostic of " + kind, kind);
            assertSame(kind, exception.kind(), "the kind a consumer reads back is the kind constructed");
            assertEquals("diagnostic of " + kind, exception.getMessage());
        }
    }

    @Test
    void aOneArgumentExceptionReadsAsATransportFailure() {
        AnswersStreamException exception = new AnswersStreamException("the stream ended");

        assertSame(FailureKind.TRANSPORT, exception.kind(), "an unclassified stream failure is a transport failure");
    }

    @Test
    void typedInternalFailuresSurfaceTheirDeclaredCategory() {
        assertSame(
                FailureKind.MALFORMED,
                AnswersStreamException.of(new AnswerDecodeException.MalformedChunk("the chunk's delta content is not text"))
                        .kind(),
                "a malformed answer chunk is a malformed stream failure");
        assertSame(
                FailureKind.MALFORMED,
                AnswersStreamException.of(new SseException.Encoding("a completed line's value was not well-formed UTF-8"))
                        .kind(),
                "a malformed event-stream line is a malformed stream failure");
        assertSame(
                FailureKind.MALFORMED,
                AnswersStreamException.of(new SseException.Incomplete("the stream ended before its terminal marker"))
                        .kind(),
                "an incomplete event stream is a malformed stream failure");
        assertSame(
                FailureKind.TRANSPORT,
                AnswersStreamException.of(new AbruptEofException("the streaming body broke before its terminator", null))
                        .kind(),
                "an abruptly ended body is a transport stream failure");
    }

    @Test
    void anUnclassifiedThrowableDefaultsToTransportAndNeverLeaksItsCause() {
        AnswersStreamException exception = AnswersStreamException.of(new UnsupportedOperationException("the body read failed"));

        assertSame(FailureKind.TRANSPORT, exception.kind(), "an unknown failure type reads as transport");
        assertEquals("the body read failed", exception.getMessage(), "the redacted diagnostic travels");
        assertNull(exception.getCause(), "the internal cause stays internal");
    }

    @Test
    void aMissingDiagnosticFallsBackToAFixedOne() {
        assertEquals(
                "the answers stream ended in failure",
                AnswersStreamException.of(new IllegalStateException()).getMessage());
        assertSame(FailureKind.TRANSPORT, AnswersStreamException.of(new IllegalStateException()).kind());
    }
}

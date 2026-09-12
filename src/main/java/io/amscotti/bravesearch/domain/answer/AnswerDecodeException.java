package io.amscotti.bravesearch.domain.answer;

import io.amscotti.bravesearch.domain.error.ClassifiedFailure;
import io.amscotti.bravesearch.domain.error.FailureKind;

/**
 * Typed failures of the Answers delta decoder. Every diagnostic is content-free: it names the
 * malformed shape and, where relevant, the tag, never the payload text itself, because upstream
 * text is untrusted input. After any of these surfaces, the decoder that threw one is finished
 * and rejects further input.
 */
public sealed class AnswerDecodeException extends RuntimeException implements ClassifiedFailure {

    AnswerDecodeException(String message) {
        super(message);
    }

    /** A decode failure is a malformed answer stream, never a transport or upstream one. */
    @Override
    public FailureKind kind() {
        return FailureKind.MALFORMED;
    }

    /** A dispatched event payload was not a JSON object. */
    public static final class MalformedChunk extends AnswerDecodeException {

        public MalformedChunk(String message) {
            super(message);
        }
    }

    /** A documented tag's payload was missing, blank, or not valid JSON. */
    public static final class MalformedTagPayload extends AnswerDecodeException {

        public MalformedTagPayload(String message) {
            super(message);
        }
    }

    /** A tag was still open when the stream ended. */
    public static final class UnterminatedTag extends AnswerDecodeException {

        public UnterminatedTag(String message) {
            super(message);
        }
    }

    /** A tag payload grew past its injected byte ceiling. */
    public static final class TagOverflow extends AnswerDecodeException {

        public TagOverflow(String message) {
            super(message);
        }
    }
}

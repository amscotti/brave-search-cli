package io.amscotti.bravesearch.domain.answer;

/**
 * One decoded element of an Answers stream: ordinary answer text, the validated payload of a
 * documented Brave tag, a well-formed tag outside the documented set preserved verbatim, or a
 * passthrough chunk that carried no answer content. The sequence of these events is the decoded
 * answer, and it is what renderers consume — no JSON-library type ever escapes the decoding
 * adapter.
 */
public sealed interface AnswerStreamEvent {

    /** A run of ordinary answer text; the concatenation of every run is the full text, exactly. */
    record Text(String text) implements AnswerStreamEvent {}

    /** A documented Brave tag whose payload text parsed as JSON and rides along unchanged. */
    record Tagged(String tag, String payload) implements AnswerStreamEvent {}

    /**
     * A well-formed tag outside the documented set, surfaced with its name and its raw payload
     * text so machine surfaces can record it as an upstream event instead of dropping it. The
     * three trailing members preserve the server-sent-event envelope the block carried — its
     * {@code event} name and the {@code id} and {@code retry} values current at dispatch, each
     * {@code null} when the block offered none — because a preserved upstream event keeps the
     * wire context it arrived in, not only its payload.
     */
    record UnknownTag(String name, String rawText, String sseName, String sseId, Long sseRetryMillis)
            implements AnswerStreamEvent {

        /** The envelope-free form: a block that carried no id, retry, or event name. */
        public UnknownTag(String name, String rawText) {
            this(name, rawText, null, null, null);
        }
    }

    /**
     * A chunk that carried no answer content. The reason names what it held instead — {@code
     * role}, {@code tool_calls}, {@code finish_reason}, {@code empty choices}, or {@code no
     * content} — so callers can distinguish framing noise from silence without seeing the chunk.
     */
    record Passthrough(String reason) implements AnswerStreamEvent {}
}

package io.amscotti.bravesearch.api;

import tools.jackson.databind.JsonNode;

/**
 * One projected element of a public Answers stream: the decoded answer, as plain public
 * records. The projection is deliberately narrow — no JSON-library type except the parsed
 * tag tree, no internal machinery — so the frame vocabulary stays stable even while the
 * stream implementation evolves.
 *
 * <p>The concatenation of every {@link Text#text()} is the full answer text, exactly.
 * Framing noise that carried no answer content (role deltas, tool-call deltas, finish
 * markers, heartbeats) is deliberately not projected: a public consumer observes the
 * answer, not the transport.
 */
public sealed interface PublicStreamFrame {

    /**
     * A run of ordinary answer text; the concatenation of every run is the full text,
     * exactly.
     *
     * @param text the answer text of this run
     */
    record Text(String text) implements PublicStreamFrame {}

    /**
     * A documented Brave tag whose payload parsed as JSON and rides along twice: the exact
     * payload text — not a re-encoded view of it — and the parsed {@code payloadTree},
     * produced through the api-boundary codec so consumers need no JSON tooling of their own
     * for the common case. The documented tag family today is {@code citation},
     * {@code entity}, {@code usage}, {@code queries}, {@code analyzing}, {@code thinking},
     * {@code progress}, {@code blindspots}, and {@code answer}. The parse is
     * parse-or-empty: a payload that is not exactly one JSON document yields Jackson's
     * missing node, never an exception and never a broken tree — {@link #payload()} keeps
     * the exact text either way.
     *
     * @param tag the documented tag name
     * @param payload the tag's exact JSON payload text
     * @param payloadTree the payload parsed as one JSON document, or the missing node when
     *     it did not parse
     */
    record Tagged(String tag, String payload, JsonNode payloadTree) implements PublicStreamFrame {}

    /**
     * A well-formed tag outside the documented set, surfaced verbatim with its name, raw
     * payload, and the server-sent-event envelope the block carried — each envelope member
     * {@code null} when the block offered none — so machine consumers can record upstream
     * evolution instead of dropping it.
     *
     * @param name the undocumented tag's name
     * @param rawText the tag's raw payload text
     * @param sseName the event-stream event name current at dispatch, or null
     * @param sseId the event-stream id current at dispatch, or null
     * @param sseRetryMillis the event-stream retry hint current at dispatch, or null
     */
    record UnknownTag(String name, String rawText, String sseName, String sseId, Long sseRetryMillis)
            implements PublicStreamFrame {

        /** The envelope-free form: a block that carried no id, retry, or event name. */
        public UnknownTag(String name, String rawText) {
            this(name, rawText, null, null, null);
        }
    }
}

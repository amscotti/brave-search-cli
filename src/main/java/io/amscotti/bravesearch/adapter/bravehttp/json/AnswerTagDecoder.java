package io.amscotti.bravesearch.adapter.bravehttp.json;

import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.domain.answer.AnswerDecodeException;
import io.amscotti.bravesearch.domain.answer.AnswerStreamEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Decoder of the Answers delta stream, one layer above the event-stream transport: each
 * dispatched event payload is one OpenAI-compatible chunk, parsed for {@code
 * choices[0].delta.content}, and the content is tokenized into ordinary text and Brave-tagged
 * payloads. This is the only Answers layer that touches JSON binding; its output is the
 * domain-safe {@link AnswerStreamEvent} sequence.
 *
 * <p>Chunk rules: a payload that is not a JSON object, or whose content member is not text, is a
 * typed {@link AnswerDecodeException.MalformedChunk} — the same tolerance the transport shows
 * unknown fields does not extend to a chunk that cannot identify itself. A chunk without usable
 * content — a role delta, a tool-call delta, a finish marker, an empty choices array around the
 * trailing usage, a contentless delta, or an empty dispatched payload (a heartbeat's empty data
 * value, tolerated exactly like an empty {@code delta.content}) — decodes to a passthrough event
 * whose reason names what it held, never to invented text.
 *
 * <p>Tag rules: the nine documented tags ({@code citation}, {@code entity}, {@code usage},
 * {@code queries}, {@code analyzing}, {@code thinking}, {@code progress}, {@code blindspots},
 * {@code answer}) must carry exactly one JSON document as their payload — validated through the
 * tolerant codec's strict upstream reader, the same settings whole bodies satisfy, so a
 * malformed payload or one with trailing tokens fails typed, because silently dropping or
 * truncating a structured payload would corrupt the answer; unknown well-formed tags surface
 * verbatim with name and raw payload for upstream-event records. A tag opens on {@code
 * <name>} where the name starts with an ASCII letter and continues with letters, digits,
 * hyphens, or underscores, and closes on the exact {@code </name>}; anything else after a
 * bracket is ordinary text. Because the first exact {@code </name>} always closes, a documented
 * tag's payload cannot contain its own closer: such a payload truncates at the embedded closer
 * and the truncated text fails JSON validation as the typed {@link
 * AnswerDecodeException.MalformedTagPayload} — loud, never a silent corruption of the answer.
 * The tokenizer holds its state across chunks, so a tag split across
 * chunks, across events, or mid-payload decodes identically to an unsplit one, and the exact
 * text concatenation of every {@code Text} event equals the content stream. A tag still open at
 * {@link #finish()} fails typed, while a partial opener that never completed flushes as text.
 *
 * <p>The payload byte ceiling is constructor-injected; a breach latches the run's
 * subscriber-failure cause — the cancel signal that unblocks the body reader — before the typed
 * {@link AnswerDecodeException.TagOverflow} surfaces. After any typed failure or {@code
 * finish()} the decoder is finished and rejects further input with {@link
 * IllegalStateException}. Instances are not thread-safe: the single reader thread that owns the
 * stream feeds them.
 */
public final class AnswerTagDecoder {

    /** Default ceiling for one tag payload's decoded UTF-8 bytes. */
    public static final long DEFAULT_MAX_TAG_PAYLOAD_BYTES = 1024 * 1024;

    private static final Set<String> DOCUMENTED_TAGS =
            Set.of("citation", "entity", "usage", "queries", "analyzing", "thinking", "progress", "blindspots", "answer");

    /** A name longer than this cannot open a tag; the bracket run flushes as ordinary text. */
    private static final int MAX_TAG_NAME_CHARS = 64;

    private final CancellationContext cancellation;
    private final long maxTagPayloadBytes;

    private final StringBuilder textRun = new StringBuilder();
    private final StringBuilder pendingOpener = new StringBuilder();
    private final StringBuilder payload = new StringBuilder();

    private String openTagName;
    private boolean openTagDocumented;
    private String closerTail = "";
    private long payloadBytes;
    private boolean finished;

    /**
     * Creates a decoder with an explicitly supplied payload ceiling; production composition
     * passes {@link #DEFAULT_MAX_TAG_PAYLOAD_BYTES} and contract tests run at kibibyte scale
     * against the same code path.
     *
     * @throws NullPointerException when {@code cancellation} is null
     */
    public AnswerTagDecoder(CancellationContext cancellation, long maxTagPayloadBytes) {
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.maxTagPayloadBytes = maxTagPayloadBytes;
    }

    /**
     * Decodes one dispatched event payload.
     *
     * @throws AnswerDecodeException.MalformedChunk when the payload is not a JSON object or its
     *     content member is not text
     * @throws AnswerDecodeException.TagOverflow when an open tag's payload exceeds its ceiling;
     *     the run's subscriber-failure cause is latched first, which cancels the stream
     * @throws AnswerDecodeException.MalformedTagPayload when a documented tag closes on a
     *     payload that is not valid JSON
     * @throws IllegalStateException when called after a typed failure or {@link #finish()}
     */
    public List<AnswerStreamEvent> decode(String data) {
        Objects.requireNonNull(data, "data");
        if (finished) {
            throw new IllegalStateException("the decoder is finished");
        }
        if (data.isEmpty()) {
            return List.of(new AnswerStreamEvent.Passthrough("no content"));
        }
        try {
            JsonNode root = readChunk(data);
            JsonNode choice = root.path("choices").path(0);
            JsonNode delta = choice.path("delta");
            JsonNode content = delta.path("content");
            if (content.isMissingNode() || content.isNull() || (content.isTextual() && content.stringValue().isEmpty())) {
                return List.of(new AnswerStreamEvent.Passthrough(passthroughReason(root, choice, delta)));
            }
            if (!content.isTextual()) {
                throw new AnswerDecodeException.MalformedChunk("the chunk's delta content is not text");
            }
            return consume(content.stringValue());
        } catch (AnswerDecodeException typed) {
            finished = true;
            throw typed;
        }
    }

    /**
     * Closes the decode: flushes a pending partial opener as ordinary text.
     *
     * @throws AnswerDecodeException.UnterminatedTag when a tag is still open
     * @throws IllegalStateException when called twice or after a typed failure
     */
    public List<AnswerStreamEvent> finish() {
        if (finished) {
            throw new IllegalStateException("the decoder is finished");
        }
        finished = true;
        if (openTagName != null) {
            throw new AnswerDecodeException.UnterminatedTag(
                    "a <" + openTagName + "> tag never closed before the stream ended");
        }
        List<AnswerStreamEvent> events = new ArrayList<>();
        textRun.append(pendingOpener);
        pendingOpener.setLength(0);
        flushText(events);
        return events;
    }

    private static JsonNode readChunk(String data) {
        JsonNode root;
        try {
            root = UpstreamJsonCodec.readFirstDocument(data);
        } catch (RuntimeException unreadable) {
            throw new AnswerDecodeException.MalformedChunk("the dispatched event payload is not valid JSON");
        }
        if (!root.isObject()) {
            throw new AnswerDecodeException.MalformedChunk("the dispatched event payload is not a JSON object");
        }
        return root;
    }

    private static String passthroughReason(JsonNode root, JsonNode choice, JsonNode delta) {
        if (delta.hasNonNull("role")) {
            return "role";
        }
        if (delta.hasNonNull("tool_calls")) {
            return "tool_calls";
        }
        if (choice.hasNonNull("finish_reason")) {
            return "finish_reason";
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return "empty choices";
        }
        return "no content";
    }

    private List<AnswerStreamEvent> consume(String text) {
        List<AnswerStreamEvent> events = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (openTagName != null) {
                index = consumePayload(events, text, index);
            } else if (pendingOpener.isEmpty()) {
                if (current == '<') {
                    pendingOpener.append(current);
                } else {
                    textRun.append(current);
                }
                index++;
            } else if (continuesTagName(current)) {
                pendingOpener.append(current);
                index++;
            } else if (current == '>' && pendingOpener.length() >= 2) {
                openTag(events);
                index++;
            } else {
                // the bracket run can no longer open a tag; it is ordinary text
                textRun.append(pendingOpener);
                pendingOpener.setLength(0);
            }
        }
        flushText(events);
        return events;
    }

    private boolean continuesTagName(char current) {
        if (pendingOpener.length() > MAX_TAG_NAME_CHARS) {
            return false;
        }
        if (pendingOpener.length() == 1) {
            return (current >= 'a' && current <= 'z') || (current >= 'A' && current <= 'Z');
        }
        return isNameChar(current);
    }

    private static boolean isNameChar(char current) {
        return (current >= 'a' && current <= 'z')
                || (current >= 'A' && current <= 'Z')
                || (current >= '0' && current <= '9')
                || current == '-'
                || current == '_';
    }

    private void openTag(List<AnswerStreamEvent> events) {
        flushText(events);
        openTagName = pendingOpener.substring(1);
        pendingOpener.setLength(0);
        openTagDocumented = DOCUMENTED_TAGS.contains(openTagName);
        payload.setLength(0);
        payloadBytes = 0;
        closerTail = "";
    }

    /**
     * Consumes payload text from {@code from}, hunting the exact closer across chunk boundaries
     * with index arithmetic over the fresh text plus the bounded carried tail — never a copy of
     * the remaining text — so a chunk packed with adjacent tags costs work proportional to its
     * length, not to its length times its tag count. A tail of up to closer-length-minus-one
     * characters is carried so a closer split across chunks is found exactly once, whether it
     * begins in the tail or in the fresh text.
     */
    private int consumePayload(List<AnswerStreamEvent> events, String text, int from) {
        String closer = "</" + openTagName + ">";
        int tailLength = closerTail.length();
        // a closer beginning inside the carried tail always outranks one beginning in the fresh
        // text: its combined position is smaller than the tail's length, which is where every
        // fresh-text position begins — the same earliest match materializing the concatenation
        // would find
        int straddling = -1;
        for (int start = 0; start < tailLength && straddling < 0; start++) {
            if (closerMatchesAt(closer, start, text, from)) {
                straddling = start;
            }
        }
        int fresh = text.indexOf(closer, from);
        if (straddling >= 0) {
            // the closer began inside the carried tail: cut it back out of the payload
            payload.setLength(payload.length() - (tailLength - straddling));
            payloadBytes = payload.toString().getBytes(StandardCharsets.UTF_8).length;
            closeTag(events);
            return from + straddling + closer.length() - tailLength;
        }
        if (fresh < 0) {
            appendPayload(text, from, text.length());
            closerTail = carryTail(closer.length() - 1, text, from);
            return text.length();
        }
        appendPayload(text, from, fresh);
        closeTag(events);
        return fresh + closer.length();
    }

    /** Whether the closer matches the combined text at {@code start}: the tail's suffix and the fresh text's prefix spell it. */
    private boolean closerMatchesAt(String closer, int start, String text, int from) {
        int inTail = closerTail.length() - start;
        for (int i = 0; i < inTail; i++) {
            if (closer.charAt(i) != closerTail.charAt(start + i)) {
                return false;
            }
        }
        int inText = closer.length() - inTail;
        if (inText > text.length() - from) {
            return false;
        }
        for (int i = 0; i < inText; i++) {
            if (closer.charAt(inTail + i) != text.charAt(from + i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The next carried tail: the suffix of at most {@code carried} characters of tail plus
     * fresh text, built from bounded slices so no whole remainder is ever materialized.
     */
    private String carryTail(int carried, String text, int from) {
        int fresh = text.length() - from;
        if (fresh >= carried) {
            return text.substring(text.length() - carried);
        }
        int keep = carried - fresh;
        return keep >= closerTail.length()
                ? closerTail + text.substring(from)
                : closerTail.substring(closerTail.length() - keep) + text.substring(from);
    }

    private void appendPayload(String text, int from, int end) {
        payload.append(text, from, end);
        payloadBytes += utf8Bytes(text, from, end);
        if (payloadBytes > maxTagPayloadBytes) {
            cancellation.latch(CancellationContext.Cause.SUBSCRIBER_FAILURE);
            throw new AnswerDecodeException.TagOverflow(
                    "a tag payload exceeded its byte ceiling of " + maxTagPayloadBytes);
        }
    }

    /**
     * The exact UTF-8 byte length of the character range — the same length
     * {@code getBytes(UTF_8)} would measure — computed per character so the range is never
     * materialized: a surrogate pair encodes as four bytes and an unpaired surrogate as the
     * one-byte replacement, exactly as the encoder writes it.
     */
    private static long utf8Bytes(String text, int from, int end) {
        long bytes = 0;
        for (int index = from; index < end; index++) {
            char current = text.charAt(index);
            if (current < 0x80) {
                bytes += 1;
            } else if (Character.isHighSurrogate(current)
                    && index + 1 < end
                    && Character.isLowSurrogate(text.charAt(index + 1))) {
                bytes += 4;
                index++;
            } else if (Character.isSurrogate(current)) {
                bytes += 1;
            } else if (current < 0x800) {
                bytes += 2;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    private void closeTag(List<AnswerStreamEvent> events) {
        String text = payload.toString();
        if (openTagDocumented) {
            if (text.isBlank()) {
                throw new AnswerDecodeException.MalformedTagPayload(
                        "the payload of a <" + openTagName + "> tag is empty");
            }
            if (!UpstreamJsonCodec.isExactlyOneJsonDocument(text)) {
                throw new AnswerDecodeException.MalformedTagPayload(
                        "the payload of a <" + openTagName + "> tag is not exactly one JSON document");
            }
            events.add(new AnswerStreamEvent.Tagged(openTagName, text));
        } else {
            events.add(new AnswerStreamEvent.UnknownTag(openTagName, text));
        }
        openTagName = null;
        openTagDocumented = false;
        payload.setLength(0);
        payloadBytes = 0;
        closerTail = "";
    }

    private void flushText(List<AnswerStreamEvent> events) {
        if (textRun.length() > 0) {
            events.add(new AnswerStreamEvent.Text(textRun.toString()));
            textRun.setLength(0);
        }
    }
}

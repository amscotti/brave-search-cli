package io.amscotti.bravesearch.adapter.bravehttp.sse;

import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.SseEvent;
import io.amscotti.bravesearch.application.stream.SseException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Incremental server-sent-event transport parser: bytes are pushed in as they arrive on the wire
 * and dispatched events come out through the listener, with no JSON binding anywhere in this
 * layer. The parser is a byte-level state machine, so every behavior — line endings, field
 * splitting, UTF-8 decoding, the terminal marker — is independent of where delivery boundaries
 * fall; UTF-8 is decoded only after a line completes, which is exact because no UTF-8
 * continuation byte can collide with the ASCII delimiters.
 *
 * <p>Wire rules implemented here: CRLF, LF, and a lone CR each end exactly one line, and a CR
 * followed by LF is one terminator, never two; one leading UTF-8 byte-order mark is stripped at
 * stream start and a mark anywhere else is ordinary content; lines beginning with a colon are
 * comments and never dispatch; a blank line dispatches the block, but a block without a data
 * line dispatches nothing; field names are case-sensitive ({@code data}, {@code event}, {@code
 * id}, {@code retry}) and unknown fields are ignored, though their bytes still count against the
 * bounds; at most one space after the field colon is removed; repeated {@code data} lines join
 * with a newline; an {@code id} carrying a NUL or a non-numeric {@code retry} is ignored while
 * the last valid values persist onto later events.
 *
 * <p>Bound enforcement counts the raw content bytes of the current line (without its terminator)
 * against the line ceiling and of every line since the last dispatch against the event ceiling;
 * both ceilings are constructor-injected so contract tests run at kibibyte scale. A breach
 * latches the run's subscriber-failure cause — the cancel signal the body reader observes, which
 * closes the connection — and then surfaces as the typed {@link SseException.Overflow}.
 *
 * <p>Ending rules: an event block left unterminated at end of stream is discarded without
 * dispatching; a dispatched data payload of exactly {@code [DONE]} marks the stream terminal;
 * any data dispatched after it fails typed, while comments and id/retry blocks after it are
 * ignored; end of stream without the terminal is the distinct {@link SseException.Incomplete}
 * signal. A listener that throws on a dispatched event is wrapped in the typed {@link
 * SseException.ListenerFailed} carrying the listener's failure as its cause. After any typed
 * failure or the end of stream the parser is finished and rejects further input with {@link
 * IllegalStateException}.
 *
 * <p>Instances are not thread-safe: the single reader thread that owns the stream feeds them.
 */
public final class SseEventParser {

    /** Default ceiling for one line's content bytes. */
    public static final long DEFAULT_MAX_LINE_BYTES = 64 * 1024;

    /** Default ceiling for one event block's accumulated content bytes. */
    public static final long DEFAULT_MAX_EVENT_BYTES = 1024 * 1024;

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static final String TERMINAL_MARKER = "[DONE]";

    /** Receives each dispatched event on the feeding thread. */
    public interface Listener {

        void onEvent(SseEvent event);
    }

    private final Listener listener;
    private final CancellationContext cancellation;
    private final long maxLineBytes;
    private final long maxEventBytes;

    private final ByteArrayOutputStream lineBytes = new ByteArrayOutputStream();
    private final StringBuilder dataBuffer = new StringBuilder();
    private final byte[] bomHeld = new byte[UTF8_BOM.length - 1];

    private String eventName;
    private String lastEventId;
    private Long retryMillis;
    private boolean anyData;
    private long lineBytesCounted;
    private long eventBytesCounted;
    private int bomPrefix;
    private boolean bomDecided;
    private boolean sawCr;
    private boolean terminalSeen;
    private boolean finished;

    /**
     * Creates a parser with explicitly supplied ceilings; production composition passes the
     * {@code DEFAULT_*} constants and contract tests run at kibibyte scale against the same code
     * path.
     *
     * @throws NullPointerException when {@code listener} or {@code cancellation} is null
     */
    public SseEventParser(Listener listener, CancellationContext cancellation, long maxLineBytes, long maxEventBytes) {
        this.listener = Objects.requireNonNull(listener, "listener");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.maxLineBytes = maxLineBytes;
        this.maxEventBytes = maxEventBytes;
    }

    /**
     * Consumes the given slice of delivered stream bytes.
     *
     * @throws SseException.Overflow when a line or event ceiling is breached; the run's
     *     subscriber-failure cause is latched first, which cancels the stream
     * @throws SseException.Encoding when a completed line's value is not well-formed UTF-8
     * @throws SseException.DataAfterTerminal when a data event dispatches after the terminal
     * @throws SseException.ListenerFailed when the listener itself throws on a dispatched
     *     event; the listener's failure rides as the cause and the parser finishes without
     *     ever re-delivering that event
     * @throws IllegalStateException when called after a typed failure or {@link #endOfStream()}
     */
    public void feed(byte[] buffer, int offset, int length) {
        Objects.requireNonNull(buffer, "buffer");
        if (finished) {
            throw new IllegalStateException("the parser is finished");
        }
        if (offset < 0 || length < 0 || offset + length > buffer.length || offset + length < 0) {
            throw new IndexOutOfBoundsException("feed slice [" + offset + ", " + (offset + length) + ") leaves the buffer");
        }
        try {
            for (int index = offset; index < offset + length; index++) {
                consume(buffer[index]);
            }
        } catch (SseException typed) {
            finished = true;
            throw typed;
        }
    }

    /**
     * Closes the stream: discards a block left unterminated — including one whose final line
     * ended in a lone CR, which ended that line but never began the block's terminating blank
     * line — then either returns on the terminal marker or fails typed.
     *
     * @throws SseException.Incomplete when the terminal marker never dispatched
     * @throws IllegalStateException when called twice or after a typed failure
     */
    public void endOfStream() {
        if (finished) {
            throw new IllegalStateException("the parser is finished");
        }
        finished = true;
        flushBom();
        sawCr = false;
        if (!terminalSeen) {
            throw new SseException.Incomplete("the event stream ended without its terminal marker");
        }
    }

    private void consume(byte current) {
        if (!bomDecided) {
            if (bomPrefix < UTF8_BOM.length && current == UTF8_BOM[bomPrefix]) {
                if (bomPrefix < bomHeld.length) {
                    bomHeld[bomPrefix] = current;
                }
                bomPrefix++;
                if (bomPrefix == UTF8_BOM.length) {
                    bomDecided = true;
                    // the fully matched mark consumed its own bytes: nothing stays held to
                    // replay, and a later flushBom must inject no phantom content
                    Arrays.fill(bomHeld, (byte) 0);
                    bomPrefix = 0;
                }
                return;
            }
            bomDecided = true;
            flushBom();
        }
        consumeLineByte(current);
    }

    /** Releases held byte-order-mark prefix bytes as ordinary content once the mark is disproven. */
    private void flushBom() {
        for (int index = 0; index < Math.min(bomPrefix, bomHeld.length); index++) {
            consumeLineByte(bomHeld[index]);
        }
        bomPrefix = 0;
    }

    private void consumeLineByte(byte current) {
        if (sawCr) {
            sawCr = false;
            if (current == '\n') {
                return;
            }
        }
        if (current == '\r') {
            completeLine();
            sawCr = true;
            return;
        }
        if (current == '\n') {
            completeLine();
            return;
        }
        lineBytesCounted++;
        if (lineBytesCounted > maxLineBytes) {
            breach("a line", maxLineBytes);
        }
        eventBytesCounted++;
        if (eventBytesCounted > maxEventBytes) {
            breach("an event block", maxEventBytes);
        }
        lineBytes.write(current);
    }

    private void completeLine() {
        byte[] raw = lineBytes.toByteArray();
        lineBytes.reset();
        lineBytesCounted = 0;
        if (raw.length == 0) {
            dispatch();
            return;
        }
        if (raw[0] == ':') {
            return;
        }
        int colon = indexOf(raw, (byte) ':');
        byte[] name = colon < 0 ? raw : Arrays.copyOfRange(raw, 0, colon);
        byte[] value = colon < 0 ? new byte[0] : Arrays.copyOfRange(raw, colon + 1, raw.length);
        if (value.length > 0 && value[0] == ' ') {
            value = Arrays.copyOfRange(value, 1, value.length);
        }
        applyField(name, value);
    }

    private void applyField(byte[] name, byte[] value) {
        if (matches(name, "data")) {
            if (anyData) {
                dataBuffer.append('\n');
            }
            dataBuffer.append(decodeUtf8(value));
            anyData = true;
        } else if (matches(name, "event")) {
            eventName = decodeUtf8(value);
        } else if (matches(name, "id")) {
            String candidate = decodeUtf8(value);
            if (candidate.indexOf('\0') < 0) {
                lastEventId = candidate;
            }
        } else if (matches(name, "retry")) {
            try {
                long parsed = Long.parseLong(decodeUtf8(value));
                if (parsed >= 0) {
                    retryMillis = parsed;
                }
            } catch (NumberFormatException ignored) {
                // a non-numeric retry is ignored per the wire format; the last valid one persists
            }
        }
    }

    private void dispatch() {
        if (anyData) {
            String data = dataBuffer.toString();
            if (terminalSeen) {
                throw new SseException.DataAfterTerminal("a data event dispatched after the terminal marker");
            }
            try {
                listener.onEvent(new SseEvent(eventName, data, lastEventId, retryMillis));
            } catch (RuntimeException listenerFailure) {
                eventName = null;
                dataBuffer.setLength(0);
                anyData = false;
                eventBytesCounted = 0;
                throw new SseException.ListenerFailed(
                        "the event listener failed while receiving a dispatched event", listenerFailure);
            }
            if (TERMINAL_MARKER.equals(data)) {
                terminalSeen = true;
            }
        }
        eventName = null;
        dataBuffer.setLength(0);
        anyData = false;
        eventBytesCounted = 0;
    }

    private void breach(String what, long limit) {
        cancellation.latch(CancellationContext.Cause.SUBSCRIBER_FAILURE);
        throw new SseException.Overflow(what + " exceeded its byte ceiling of " + limit);
    }

    private static String decodeUtf8(byte[] value) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value))
                    .toString();
        } catch (CharacterCodingException malformed) {
            throw new SseException.Encoding("a completed line's value is not well-formed UTF-8");
        }
    }

    private static int indexOf(byte[] raw, byte sought) {
        for (int index = 0; index < raw.length; index++) {
            if (raw[index] == sought) {
                return index;
            }
        }
        return -1;
    }

    private static boolean matches(byte[] raw, String ascii) {
        if (raw.length != ascii.length()) {
            return false;
        }
        for (int index = 0; index < raw.length; index++) {
            if (raw[index] != (byte) ascii.charAt(index)) {
                return false;
            }
        }
        return true;
    }
}

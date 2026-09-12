package io.amscotti.bravesearch.adapter.bravehttp.sse;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.SseEvent;
import io.amscotti.bravesearch.application.stream.SseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Byte-level contract of the incremental event-stream parser: the line/event layer is proven by a
 * boundary-split replay harness — the same byte sequence fed whole, at every fixed chunk size, and
 * at every two-cut split must yield byte-identical event sequences and the same ending — so no
 * behavior may depend on where delivery boundaries fall. Line endings, joining, comment handling,
 * field case, the single-space rule, captured id/retry, bounds with injectable limits, UTF-8
 * integrity, and the terminal-marker matrix are each pinned by their own behavioral test.
 */
final class SseEventParserTest {

    private static final long DEFAULT_LINE_BOUND = 64 * 1024;
    private static final long DEFAULT_EVENT_BOUND = 1024 * 1024;

    /** One full parse of a stream: every dispatched event plus how the stream ended. */
    private record Replay(List<SseEvent> events, String ending) {}

    private static final class Collecting implements SseEventParser.Listener {
        final List<SseEvent> events = new ArrayList<>();

        @Override
        public void onEvent(SseEvent event) {
            events.add(event);
        }
    }

    private static Replay replay(byte[] stream, int... cuts) {
        return replay(stream, DEFAULT_LINE_BOUND, DEFAULT_EVENT_BOUND, cuts);
    }

    private static Replay replay(byte[] stream, long maxLineBytes, long maxEventBytes, int... cuts) {
        Collecting listener = new Collecting();
        SseEventParser parser = new SseEventParser(listener, new CancellationContext(), maxLineBytes, maxEventBytes);
        int start = 0;
        String ending;
        try {
            for (int cut : cuts) {
                parser.feed(stream, start, cut - start);
                start = cut;
            }
            parser.endOfStream();
            ending = "clean";
        } catch (SseException.Incomplete incomplete) {
            ending = "incomplete";
        } catch (SseException typed) {
            ending = typed.getClass().getSimpleName();
        }
        return new Replay(List.copyOf(listener.events), ending);
    }

    private static Replay replayWhole(String stream) {
        byte[] bytes = stream.getBytes(UTF_8);
        return replay(bytes, bytes.length);
    }

    private static int[] cutsOfEveryChunkSize(int length, int chunkSize) {
        List<Integer> cuts = new ArrayList<>();
        for (int position = chunkSize; position < length; position += chunkSize) {
            cuts.add(position);
        }
        cuts.add(length);
        return cuts.stream().mapToInt(Integer::intValue).toArray();
    }

    /** A stream exercising every ending, a leading byte-order mark, comments, and id/retry. */
    private static byte[] matrixStream() {
        return (": opening comment\r\n"
                        + "id: m-1\r"
                        + "retry: 1000\n"
                        + "event: named\r\n"
                        + "data: alpha\r\n"
                        + "data: beta\r"
                        + "\r"
                        + ": beat\r\n"
                        + "unknown: ignored\n"
                        + "data: gamma\n"
                        + "\r\n"
                        + "data: [DONE]\r\n"
                        + "\n"
                        + ": trailing comment\n\n")
                .getBytes(UTF_8);
    }

    @Test
    void lineEndingMatrixReplaysIdenticallyAtEveryChunkSplit() {
        byte[] stream = prependBom(matrixStream());
        Replay whole = replay(stream, stream.length);

        Replay expected = new Replay(
                List.of(
                        new SseEvent("named", "alpha\nbeta", "m-1", 1000L),
                        new SseEvent(null, "gamma", "m-1", 1000L),
                        new SseEvent(null, "[DONE]", "m-1", 1000L)),
                "clean");
        assertEquals(expected, whole, "the whole-feed parse must match the documented events");

        for (int chunkSize = 1; chunkSize <= stream.length; chunkSize++) {
            assertEquals(
                    expected,
                    replay(stream, cutsOfEveryChunkSize(stream.length, chunkSize)),
                    "chunk size " + chunkSize + " must yield the identical events");
        }
        for (int cut = 1; cut < stream.length; cut++) {
            assertEquals(expected, replay(stream, cut, stream.length), "split at " + cut + " must yield the identical events");
        }
    }

    @Test
    void crlfSplitAcrossFeedsIsOneLineEndNotTwo() {
        assertEquals(
                new Replay(List.of(new SseEvent(null, "a", null, null)), "incomplete"),
                replayWhole("data: a\r" + "\n\r" + "\n"));
    }

    @Test
    void repeatedDataLinesJoinWithNewlineExactly() {
        assertEquals(
                List.of(new SseEvent(null, "first\nsecond\nthird", null, null)),
                replayWhole("data: first\r\ndata: second\ndata: third\n\n").events());
    }

    @Test
    void dataValuesStripAtMostOneSpaceAndKeepTheRest() {
        assertEquals(List.of(new SseEvent(null, "", null, null)), replayWhole("data: \n\n").events());
        assertEquals(List.of(new SseEvent(null, "x", null, null)), replayWhole("data:x\n\n").events());
        assertEquals(List.of(new SseEvent(null, "x", null, null)), replayWhole("data: x\n\n").events());
        assertEquals(List.of(new SseEvent(null, " x", null, null)), replayWhole("data:  x\n\n").events());
        assertEquals(List.of(new SseEvent(null, "\tx", null, null)), replayWhole("data:\tx\n\n").events());
        assertEquals(List.of(new SseEvent(null, "\nx", null, null)), replayWhole("data\ndata: x\n\n").events());
    }

    @Test
    void commentsAndHeartbeatsNeverDispatchOrJoin() {
        assertEquals(List.of(), replayWhole(": heartbeat\n\n: another\r\n\r\n").events());
        assertEquals(
                List.of(new SseEvent(null, "payload", null, null)),
                replayWhole(": before\n\n: mid\ndata: payload\n: after\n\n: tail\n\n").events());
    }

    @Test
    void fieldNamesAreCaseSensitiveSoMisspelledFieldsAreUnknown() {
        assertEquals(List.of(), replayWhole("Data: x\n\n").events());
        assertEquals(List.of(), replayWhole("EVENT: add\nID: 9\nRETRY: 5\nData: x\n\n").events());
        assertEquals(List.of(), replayWhole("event: add\nid: 9\nretry: 5\nData: x\n\n").events());
    }

    @Test
    void unknownFieldsAreIgnoredButStillCountedAgainstTheBounds() {
        CancellationContext cancellation = new CancellationContext();
        Collecting listener = new Collecting();
        SseEventParser parser = new SseEventParser(listener, cancellation, 8, DEFAULT_EVENT_BOUND);

        byte[] ignored = "unknown: 0123456789abcdef\n".getBytes(UTF_8);
        SseException.Overflow breached =
                assertThrows(SseException.Overflow.class, () -> parser.feed(ignored, 0, ignored.length));

        assertTrue(breached.getMessage().contains("line"), "the diagnostic must name the breached bound: " + breached.getMessage());
        assertEquals(Optional.of(CancellationContext.Cause.SUBSCRIBER_FAILURE), cancellation.cause());
        assertEquals(List.of(), listener.events);
    }

    @Test
    void idAndRetryAreCapturedAndCarriedToLaterEvents() {
        assertEquals(
                List.of(
                        new SseEvent(null, "one", "evt-7", 2500L),
                        new SseEvent("add", "two", "evt-7", 2500L),
                        new SseEvent(null, "three", "evt-8", 2500L),
                        new SseEvent(null, "four", "evt-8", 2500L),
                        new SseEvent(null, "[DONE]", "evt-8", 2500L)),
                replayWhole("id: evt-7\nretry: 2500\ndata: one\n\n"
                                + "event: add\ndata: two\n\n"
                                + "id: evt-8\ndata: three\n\n"
                                + "retry: nope\ndata: four\n\n"
                                + "data: [DONE]\n\n")
                        .events());
    }

    @Test
    void idCarryingANulByteIsIgnoredPerTheWireFormat() {
        assertEquals(
                List.of(
                        new SseEvent(null, "x", "keep", null),
                        new SseEvent(null, "[DONE]", "keep", null)),
                replayWhole("id: keep\ndata: x\n\nid: bad\u0000id\ndata: [DONE]\n\n").events());
    }

    @Test
    void lineAndEventBoundsBreachWithTypedOverflowAndCancellation() {
        CancellationContext cancellation = new CancellationContext();
        SseEventParser lineBound =
                new SseEventParser(event -> {}, cancellation, 8, DEFAULT_EVENT_BOUND);
        SseException.Overflow lineBreach = assertThrows(
                SseException.Overflow.class,
                () -> lineBound.feed("data: 0123456789abcdef\n".getBytes(UTF_8), 0, 21));
        assertTrue(lineBreach.getMessage().contains("8"), "the diagnostic must name the limit: " + lineBreach.getMessage());

        CancellationContext eventCancellation = new CancellationContext();
        Collecting listener = new Collecting();
        SseEventParser eventBound = new SseEventParser(listener, eventCancellation, DEFAULT_LINE_BOUND, 16);
        byte[] growingEvent = "data: 0123456789\r\ndata: 0123456789\r\n\r\n".getBytes(UTF_8);
        SseException.Overflow eventBreach = assertThrows(
                SseException.Overflow.class, () -> eventBound.feed(growingEvent, 0, growingEvent.length));
        assertTrue(eventBreach.getMessage().contains("event"), "the diagnostic must name the breached bound");

        assertEquals(Optional.of(CancellationContext.Cause.SUBSCRIBER_FAILURE), cancellation.cause());
        assertEquals(Optional.of(CancellationContext.Cause.SUBSCRIBER_FAILURE), eventCancellation.cause());
        assertEquals(List.of(), listener.events, "a breached event never dispatches");
    }

    @Test
    void malformedUtf8FailsWithTypeEncodingEvenAcrossFeeds() {
        byte[] truncatedSequence = {'d', 'a', 't', 'a', ':', ' ', (byte) 0xC3, '(', '\n', '\n'};
        assertEquals("Encoding", replay(truncatedSequence, truncatedSequence.length).ending());
        assertEquals("Encoding", replay(truncatedSequence, 7, truncatedSequence.length).ending());
        byte[] invalidStarter = {'d', 'a', 't', 'a', ':', ' ', (byte) 0xFF, '\n', '\n'};
        assertEquals("Encoding", replay(invalidStarter, invalidStarter.length).ending());
        assertEquals(
                "incomplete",
                replay(new byte[] {'d', 'a', 't', 'a', ':', ' ', (byte) 0xC3}, 7).ending(),
                "a line discarded at end of stream is never decoded, so it cannot fail decoding");
    }

    @Test
    void multibyteCharactersSplitAtEveryBoundaryReplayIdentically() {
        byte[] stream = "data: café ☕ 😀 ok\r\n\r\ndata: [DONE]\n\n".getBytes(UTF_8);
        Replay expected = new Replay(
                List.of(new SseEvent(null, "café ☕ 😀 ok", null, null), new SseEvent(null, "[DONE]", null, null)),
                "clean");
        assertEquals(expected, replay(stream, stream.length));
        for (int cut = 1; cut < stream.length; cut++) {
            assertEquals(expected, replay(stream, cut, stream.length), "split at " + cut + " must decode identically");
        }
        for (int chunkSize = 1; chunkSize <= 4; chunkSize++) {
            assertEquals(
                    expected,
                    replay(stream, cutsOfEveryChunkSize(stream.length, chunkSize)),
                    "chunk size " + chunkSize + " must decode identically");
        }
    }

    @Test
    void oneLeadingBomIsStrippedAndAMidStreamBomStaysContent() {
        assertEquals(
                List.of(new SseEvent(null, "start", null, null)),
                replay(prependBom("data: start\n\n".getBytes(UTF_8)), 3, 16).events());
        assertEquals(
                List.of(new SseEvent(null, "\uFEFFx", null, null)),
                replayWhole("data: " + new String(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, UTF_8) + "x\n\n")
                        .events());
        byte[] partialBomThenEof = {(byte) 0xEF, (byte) 0xBB};
        assertEquals("incomplete", replay(partialBomThenEof, 2).ending());
    }

    @Test
    void aFullyMatchedLeadingBomIsNeverReplayedAtEndOfStream() {
        byte[] loneCrTruncation = "data: x\r".getBytes(UTF_8);
        assertEquals(
                replay(loneCrTruncation, loneCrTruncation.length),
                replay(prependBom(loneCrTruncation), loneCrTruncation.length + 3),
                "a stripped mark must leave the truncated ending exactly like the no-mark control");
        byte[] cleanThenUnterminated = "data: x\n\ndata: [DONE]\n\n".getBytes(UTF_8);
        assertEquals(
                replay(cleanThenUnterminated, cleanThenUnterminated.length),
                replay(prependBom(cleanThenUnterminated), cleanThenUnterminated.length + 3),
                "a stripped mark must leave the clean ending exactly like the no-mark control");
        byte[] doneCrTruncation = "data: [DONE]\r".getBytes(UTF_8);
        assertEquals(
                replay(doneCrTruncation, doneCrTruncation.length),
                replay(prependBom(doneCrTruncation), doneCrTruncation.length + 3),
                "a stripped mark must leave the terminal-marker truncation incomplete");

        CancellationContext cancellation = new CancellationContext();
        Collecting listener = new Collecting();
        SseEventParser tight = new SseEventParser(listener, cancellation, DEFAULT_LINE_BOUND, 7);
        byte[] bomThenTruncated = prependBom(loneCrTruncation);
        tight.feed(bomThenTruncated, 0, bomThenTruncated.length);
        SseException.Incomplete incomplete = assertThrows(SseException.Incomplete.class, tight::endOfStream);
        assertEquals(
                "the event stream ended without its terminal marker",
                incomplete.getMessage(),
                "the mark's held bytes must not breach a bound the no-mark control satisfies");
        assertEquals(Optional.empty(), cancellation.cause(), "a fully stripped mark must never cancel the run");
    }

    @Test
    void eofAndTerminalMarkerMatrixBehavesDeterministically() {
        assertEquals("clean", replayWhole("data: [DONE]\n\n").ending());
        assertEquals("clean", replayWhole("data: x\n\ndata: [DONE]\n\n").ending());
        assertEquals("incomplete", replayWhole("data: x\n\n").ending());
        assertEquals("incomplete", replayWhole("data: [DONE]\n").ending());
        assertEquals("incomplete", replayWhole("data: [DONE]\r").ending());
        assertEquals("clean", replayWhole("data: [DONE]\r\n\r").ending());
        assertEquals("DataAfterTerminal", replayWhole("data: [DONE]\n\ndata: y\n\n").ending());
        assertEquals("clean", replayWhole("data: [DONE]\n\n: comment\n\n").ending());
        assertEquals("clean", replayWhole("data: [DONE]\n\nid: later\nretry: 9\n\n").ending());
        assertEquals(
                new Replay(List.of(new SseEvent(null, "x", null, null)), "incomplete"),
                replayWhole("data: x\n\ndata: dropped\n"));
    }

    @Test
    void aLoneCrAtEndOfStreamNeverDispatchesTheUnterminatedBlock() {
        assertEquals(
                new Replay(List.of(new SseEvent(null, "x", null, null)), "incomplete"),
                replayWhole("data: x\n\ndata: [DONE]\r"),
                "a block whose last line ended in a lone CR never received its terminating blank"
                        + " line, so end of stream must discard it instead of completing the stream");
        assertEquals(new Replay(List.of(), "incomplete"), replayWhole("data: x\r"));
        assertEquals(new Replay(List.of(), "incomplete"), replayWhole("data: [DONE]\r"));
    }

    @Test
    void theDoneEventItselfIsDispatchedBeforeTheStreamCloses() {
        assertEquals(
                List.of(new SseEvent(null, "x", null, null), new SseEvent(null, "[DONE]", null, null)),
                replayWhole("data: x\n\ndata: [DONE]\n\n").events());
    }

    @Test
    void feedingAfterEndOfStreamOrAfterAFailureIsRejected() {
        SseEventParser parser =
                new SseEventParser(event -> {}, new CancellationContext(), DEFAULT_LINE_BOUND, DEFAULT_EVENT_BOUND);
        byte[] done = "data: [DONE]\n\n".getBytes(UTF_8);
        parser.feed(done, 0, done.length);
        parser.endOfStream();
        assertThrows(IllegalStateException.class, () -> parser.feed(done, 0, done.length));
        assertThrows(IllegalStateException.class, parser::endOfStream);

        SseEventParser breached = new SseEventParser(event -> {}, new CancellationContext(), 4, DEFAULT_EVENT_BOUND);
        assertThrows(SseException.Overflow.class, () -> breached.feed("data: 0123456\n".getBytes(UTF_8), 0, 14));
        assertThrows(IllegalStateException.class, () -> breached.feed(done, 0, 0));
    }

    @Test
    void aListenerThrowFinishesTheParserAndNeverDispatchesTwice() {
        List<SseEvent> dispatched = new ArrayList<>();
        IllegalStateException listenerFailure = new IllegalStateException("the listener rejected the event");
        SseEventParser parser = new SseEventParser(
                event -> {
                    dispatched.add(event);
                    throw listenerFailure;
                },
                new CancellationContext(),
                DEFAULT_LINE_BOUND,
                DEFAULT_EVENT_BOUND);

        byte[] first = "data: a\n\n".getBytes(UTF_8);
        SseException.ListenerFailed typed =
                assertThrows(SseException.ListenerFailed.class, () -> parser.feed(first, 0, first.length));
        assertEquals(listenerFailure, typed.getCause(), "the typed wrapper must carry the listener's own failure");

        byte[] second = "data: a\n\n".getBytes(UTF_8);
        assertThrows(IllegalStateException.class, () -> parser.feed(second, 0, second.length));
        assertThrows(IllegalStateException.class, parser::endOfStream);
        assertEquals(
                List.of(new SseEvent(null, "a", null, null)),
                dispatched,
                "the event the listener rejected must never be delivered a second time");
    }

    @Test
    void feedRejectsOutOfRangeSlices() {
        SseEventParser parser =
                new SseEventParser(event -> {}, new CancellationContext(), DEFAULT_LINE_BOUND, DEFAULT_EVENT_BOUND);
        byte[] bytes = "data: x\n\n".getBytes(UTF_8);
        assertThrows(IndexOutOfBoundsException.class, () -> parser.feed(bytes, 1, bytes.length));
        assertThrows(IndexOutOfBoundsException.class, () -> parser.feed(bytes, 0, bytes.length + 1));
        assertThrows(IndexOutOfBoundsException.class, () -> parser.feed(bytes, -1, 2));
    }

    private static byte[] prependBom(byte[] stream) {
        byte[] withBom = new byte[stream.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(stream, 0, withBom, 3, stream.length);
        return withBom;
    }
}

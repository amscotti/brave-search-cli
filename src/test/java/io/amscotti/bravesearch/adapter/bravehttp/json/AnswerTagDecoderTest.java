package io.amscotti.bravesearch.adapter.bravehttp.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.bravehttp.sse.SseEventParser;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.SseEvent;
import io.amscotti.bravesearch.domain.answer.AnswerDecodeException;
import io.amscotti.bravesearch.domain.answer.AnswerStreamEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Decoding contract of the Answers delta stream: each dispatched event payload is one
 * OpenAI-compatible chunk whose content mixes ordinary text with Brave-tagged JSON payloads. The
 * tokenizer must survive any split — across chunks, inside tag names, inside payload JSON, inside
 * closers — ordinary text must survive character-for-character, unknown tags must surface with
 * their name and raw payload, and every malformed shape must fail with its own typed exception
 * instead of being silently dropped.
 */
final class AnswerTagDecoderTest {

    private static final List<String> DOCUMENTED_TAGS =
            List.of("citation", "entity", "usage", "queries", "analyzing", "thinking", "progress", "blindspots", "answer");

    private static AnswerTagDecoder decoder() {
        return new AnswerTagDecoder(new CancellationContext(), AnswerTagDecoder.DEFAULT_MAX_TAG_PAYLOAD_BYTES);
    }

    private static String chunkOf(String content) {
        return "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + jsonEscape(content) + "\"},\"finish_reason\":null}]}";
    }

    private static String jsonEscape(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\t", "\\t")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static List<AnswerStreamEvent> decodeAll(AnswerTagDecoder decoder, String... contents) {
        List<AnswerStreamEvent> events = new ArrayList<>();
        for (String content : contents) {
            events.addAll(decoder.decode(chunkOf(content)));
        }
        return events;
    }

    /** Decodes the content split at the given interior per-character positions, one chunk each. */
    private static List<AnswerStreamEvent> decodeSplit(String content, int... cuts) {
        AnswerTagDecoder decoder = decoder();
        List<AnswerStreamEvent> events = new ArrayList<>();
        int start = 0;
        for (int cut : cuts) {
            events.addAll(decoder.decode(chunkOf(content.substring(start, cut))));
            start = cut;
        }
        events.addAll(decoder.decode(chunkOf(content.substring(start))));
        return events;
    }

    @Test
    void closersSplitAcrossThreeAndFourChunksDecodeIdenticallyToTheWhole() {
        String content = "a<citation>{\"n\":1}</citation>b<thinking>{}</thinking>c";
        List<AnswerStreamEvent> expected = List.of(
                new AnswerStreamEvent.Text("a"),
                new AnswerStreamEvent.Tagged("citation", "{\"n\":1}"),
                new AnswerStreamEvent.Text("b"),
                new AnswerStreamEvent.Tagged("thinking", "{}"),
                new AnswerStreamEvent.Text("c"));
        assertEquals(expected, decodeAll(decoder(), content));

        for (int first = 1; first < content.length() - 1; first++) {
            for (int second = first + 1; second < content.length(); second++) {
                assertEquals(
                        expected,
                        decodeSplit(content, first, second),
                        "the 3-way split at " + first + "," + second + " must decode identically");
            }
        }
        for (int first = 1; first < content.length() - 2; first++) {
            for (int second = first + 1; second < content.length() - 1; second++) {
                for (int third = second + 1; third < content.length(); third++) {
                    assertEquals(
                            expected,
                            decodeSplit(content, first, second, third),
                            "the 4-way split at " + first + "," + second + "," + third
                                    + " must decode identically");
                }
            }
        }
    }

    @Test
    void ordinaryTextIsPreservedExactlyAroundTags() {
        List<AnswerStreamEvent> events = decodeAll(
                decoder(),
                "Résumé — a < b and <3 engines\ttab\nline <citation>{\"n\":1}</citation> tail «» 😀");

        assertEquals(
                List.of(
                        new AnswerStreamEvent.Text("Résumé — a < b and <3 engines\ttab\nline "),
                        new AnswerStreamEvent.Tagged("citation", "{\"n\":1}"),
                        new AnswerStreamEvent.Text(" tail «» 😀")),
                events);
    }

    @Test
    void everyDocumentedTagIsRecognizedAndItsPayloadValidated() {
        for (String tag : DOCUMENTED_TAGS) {
            List<AnswerStreamEvent> events = decodeAll(decoder(), "<" + tag + ">{\"marker\":1}</" + tag + ">");
            assertEquals(
                    List.of(new AnswerStreamEvent.Tagged(tag, "{\"marker\":1}")),
                    events,
                    "the <" + tag + "> tag must decode as a known tagged payload");
        }
    }

    @Test
    void tagsSplitAcrossChunksAndMidPayloadStillDecode() {
        AnswerTagDecoder decoder = decoder();

        assertEquals(
                List.of(new AnswerStreamEvent.Text("see ")),
                decoder.decode(chunkOf("see <cit")));
        assertEquals(List.of(), decoder.decode(chunkOf("ation>{\"n\":1")));
        assertEquals(
                List.of(
                        new AnswerStreamEvent.Tagged("citation", "{\"n\":1}"),
                        new AnswerStreamEvent.Text(" done")),
                decoder.decode(chunkOf("}</citation> done")));

        AnswerTagDecoder midJson = decoder();
        midJson.decode(chunkOf("edge <usage>{\"X"));
        assertEquals(
                List.of(new AnswerStreamEvent.Tagged("usage", "{\"X-Request-Requests\":\"1\"}")),
                midJson.decode(chunkOf("-Request-Requests\":\"1\"}</usage>")));

        AnswerTagDecoder midCloser = decoder();
        midCloser.decode(chunkOf("<thinking>{}</thin"));
        assertEquals(
                List.of(new AnswerStreamEvent.Tagged("thinking", "{}")),
                midCloser.decode(chunkOf("king>")));
    }

    @Test
    void adjacentTagsEmitSeparatelyWithNoInventedTextBetween() {
        assertEquals(
                List.of(
                        new AnswerStreamEvent.Tagged("answer", "{}"),
                        new AnswerStreamEvent.Tagged("usage", "{\"c\":1}")),
                decodeAll(decoder(), "<answer>{}</answer><usage>{\"c\":1}</usage>"));
    }

    @Test
    void adjacentTagsPackedIntoOneHugeChunkDecodeWithinABoundedTime() {
        String unit = "<e>{}</e>";
        int tags = 900_000;
        AnswerTagDecoder decoder = decoder();

        List<AnswerStreamEvent> events = assertTimeoutPreemptively(
                Duration.ofSeconds(5), () -> decoder.decode(chunkOf(unit.repeat(tags))));

        assertEquals(tags, events.size(), "every adjacent tag closes as its own event");
        assertEquals(new AnswerStreamEvent.UnknownTag("e", "{}"), events.getFirst());
        assertEquals(new AnswerStreamEvent.UnknownTag("e", "{}"), events.getLast());
    }

    @Test
    void angleBracketsInsidePayloadStringsStayInThePayload() {
        String payload = "{\"snippet\":\"a < b and </not> too\"}";
        assertEquals(
                List.of(new AnswerStreamEvent.Tagged("citation", payload)),
                decodeAll(decoder(), "<citation>" + payload + "</citation>"));

        String partialCloserInside = "{\"q\":\"</thinkin\"}";
        assertEquals(
                List.of(new AnswerStreamEvent.Tagged("thinking", partialCloserInside)),
                decodeAll(decoder(), "<thinking>" + partialCloserInside + "</thinking>"));
    }

    @Test
    void unknownTagsSurfaceWithNameAndRawPayloadText() {
        assertEquals(
                List.of(new AnswerStreamEvent.UnknownTag("weather", "{\"temperature_c\":21}")),
                decodeAll(decoder(), "<weather>{\"temperature_c\":21}</weather>"));
        assertEquals(
                List.of(new AnswerStreamEvent.UnknownTag("a-b_2", "not json at all")),
                decodeAll(decoder(), "<a-b_2>not json at all</a-b_2>"));
        assertEquals(
                List.of(new AnswerStreamEvent.UnknownTag("brave-tomorrow", "{}")),
                decodeAll(decoder(), "<brave-tomorrow>{}</brave-tomorrow>"));
    }

    @Test
    void nonTagAngleBracketsStayOrdinaryText() {
        assertEquals(
                List.of(new AnswerStreamEvent.Text("5 < 6 and <3 brave and <> and <3x>")),
                decodeAll(decoder(), "5 < 6 and <3 brave and <> and <3x>"));
    }

    @Test
    void roleToolFinishAndContentlessChunksPassThrough() {
        AnswerTagDecoder decoder = decoder();

        assertEquals(
                List.of(new AnswerStreamEvent.Passthrough("role")),
                decoder.decode(
                        "{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":null}]}"));
        assertEquals(
                List.of(new AnswerStreamEvent.Passthrough("no content")),
                decoder.decode(chunkOf("")));
        assertEquals(
                List.of(new AnswerStreamEvent.Passthrough("tool_calls")),
                decoder.decode(
                        "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"id\":\"call_1\"}]},\"finish_reason\":null}]}"));
        assertEquals(
                List.of(new AnswerStreamEvent.Passthrough("finish_reason")),
                decoder.decode("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}"));
        assertEquals(
                List.of(new AnswerStreamEvent.Passthrough("empty choices")),
                decoder.decode("{\"choices\":[],\"usage\":{\"total_tokens\":1020}}"));
        assertEquals(
                List.of(new AnswerStreamEvent.Passthrough("no content")),
                decoder.decode("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":null}]}"));
    }

    @Test
    void malformedKnownTagPayloadFailsWithTheTypedException() {
        AnswerTagDecoder decoder = decoder();
        AnswerDecodeException.MalformedTagPayload malformed = assertThrows(
                AnswerDecodeException.MalformedTagPayload.class,
                () -> decodeAll(decoder, "<usage>{not json}</usage>"));
        assertTrue(
                malformed.getMessage().contains("usage"),
                "the diagnostic must name the tag without quoting its payload: " + malformed.getMessage());

        assertThrows(
                AnswerDecodeException.MalformedTagPayload.class,
                () -> decodeAll(decoder(), "<citation></citation>"));
        assertThrows(
                AnswerDecodeException.MalformedTagPayload.class,
                () -> decodeAll(decoder(), "<answer>{\"open\": true</answer>"));
    }

    @Test
    void documentedTagPayloadsWithTrailingTokensFailLikeEveryMalformedPayload() {
        assertThrows(
                AnswerDecodeException.MalformedTagPayload.class,
                () -> decodeAll(
                        decoder(),
                        "<citation>{\"url\":\"https://example.com\"} trailing</citation>"),
                "a payload that continues after its first JSON document is malformed, exactly like"
                        + " the tolerant upstream reader judges whole bodies");
        assertThrows(
                AnswerDecodeException.MalformedTagPayload.class,
                () -> decodeAll(
                        decoder(),
                        "<usage>{\"requests\":1}{\"requests\":2}</usage>"),
                "two concatenated documents are one malformed payload, never a silently truncated"
                        + " first document");
    }

    @Test
    void malformedUsagePayloadFailsIdenticallyAtEverySplit() {
        String content = "<usage>not json</usage>";
        assertThrows(
                AnswerDecodeException.MalformedTagPayload.class,
                () -> decodeAll(decoder(), content),
                "the whole malformed usage payload must fail");

        for (int cut = 1; cut < content.length(); cut++) {
            AnswerTagDecoder decoder = decoder();
            int split = cut;
            assertThrows(
                    AnswerDecodeException.MalformedTagPayload.class,
                    () -> {
                        decoder.decode(chunkOf(content.substring(0, split)));
                        decoder.decode(chunkOf(content.substring(split)));
                    },
                    "the split at " + cut + " must fail exactly like the whole payload");
        }
    }

    @Test
    void malformedUnknownTagPayloadIsRawTextNotAFailure() {
        assertEquals(
                List.of(new AnswerStreamEvent.UnknownTag("weather", "{not json}")),
                decodeAll(decoder(), "<weather>{not json}</weather>"));
    }

    @Test
    void anEmptyDataPayloadIsAToleratedHeartbeatNotAMalformedChunk() {
        assertEquals(
                List.of(new AnswerStreamEvent.Passthrough("no content")),
                decoder().decode(""),
                "an event whose data value is empty — a heartbeat — carries no answer content and"
                        + " must pass through exactly like a chunk whose delta content is empty");
    }

    @Test
    void malformedChunkJsonFailsWithTheTypedException() {
        assertThrows(AnswerDecodeException.MalformedChunk.class, () -> decoder().decode("not json"));
        assertThrows(AnswerDecodeException.MalformedChunk.class, () -> decoder().decode("[1,2]"));
        assertThrows(AnswerDecodeException.MalformedChunk.class, () -> decoder().decode("null"));
        assertThrows(
                AnswerDecodeException.MalformedChunk.class,
                () -> decoder().decode("{\"choices\":[{\"delta\":{\"content\":7}}]}"));
    }

    @Test
    void anUnterminatedTagAtFinishFailsWhileAPendingOpenerFlushesAsText() {
        AnswerTagDecoder unterminated = decoder();
        unterminated.decode(chunkOf("<thinking>{\"plan\""));
        assertThrows(AnswerDecodeException.UnterminatedTag.class, unterminated::finish);

        AnswerTagDecoder pending = decoder();
        assertEquals(
                List.of(new AnswerStreamEvent.Text("tail ")), pending.decode(chunkOf("tail <ci")));
        assertEquals(List.of(new AnswerStreamEvent.Text("<ci")), pending.finish());
        assertThrows(IllegalStateException.class, pending::finish);
    }

    @Test
    void tagPayloadBoundIsInjectableAndBreachLatchesCancellation() {
        CancellationContext cancellation = new CancellationContext();
        AnswerTagDecoder decoder = new AnswerTagDecoder(cancellation, 16);

        AnswerDecodeException.TagOverflow breached = assertThrows(
                AnswerDecodeException.TagOverflow.class,
                () -> decodeAll(decoder, "<usage>{\"a\":\"0123456789abcdef\"}</usage>"));

        assertTrue(
                breached.getMessage().contains("16"), "the diagnostic must name the limit: " + breached.getMessage());
        assertEquals(Optional.of(CancellationContext.Cause.SUBSCRIBER_FAILURE), cancellation.cause());
    }

    @Test
    void aRealisticAnswerStreamFixtureDecodesEndToEnd() throws Exception {
        byte[] fixture = readFixture();
        List<SseEvent> dispatched = new ArrayList<>();
        CancellationContext cancellation = new CancellationContext();
        AnswerTagDecoder decoder =
                new AnswerTagDecoder(cancellation, AnswerTagDecoder.DEFAULT_MAX_TAG_PAYLOAD_BYTES);
        SseEventParser parser = new SseEventParser(
                dispatched::add,
                cancellation,
                SseEventParser.DEFAULT_MAX_LINE_BYTES,
                SseEventParser.DEFAULT_MAX_EVENT_BYTES);

        parser.feed(fixture, 0, fixture.length);
        parser.endOfStream();

        assertEquals(
                "ans-0142", dispatched.get(1).lastEventId(), "the remembered id rides every later dispatched event");
        assertNull(dispatched.getFirst().lastEventId(), "the id block follows the first chunk, so the first event carries none");
        assertEquals(2500L, dispatched.get(1).retryMillis());
        assertEquals("[DONE]", dispatched.getLast().data(), "the fixture ends on its terminal marker");

        List<AnswerStreamEvent> events = new ArrayList<>();
        for (SseEvent event : dispatched) {
            if (!"[DONE]".equals(event.data())) {
                events.addAll(decoder.decode(event.data()));
            }
        }
        events.addAll(decoder.finish());

        assertEquals(
                List.of(
                        new AnswerStreamEvent.Passthrough("role"),
                        new AnswerStreamEvent.Text("Brave Search "),
                        new AnswerStreamEvent.Text(
                                "is an independent index that does not track users, with per-query costs < 0.002."),
                        new AnswerStreamEvent.Tagged(
                                "progress", "{\"completed\":2,\"total\":5,\"action\":\"searching\"}"),
                        new AnswerStreamEvent.Text("Research trace: "),
                        new AnswerStreamEvent.Tagged("thinking", "{\"plan\":\"verify sources, then draft\"}"),
                        new AnswerStreamEvent.Tagged(
                                "queries",
                                "{\"queries\":[\"brave search independence\",\"brave search privacy\"]}"),
                        new AnswerStreamEvent.Tagged("analyzing", "{\"documents\":12,\"relevant\":7}"),
                        new AnswerStreamEvent.Tagged("blindspots", "{\"gaps\":[\"non-English sources\"]}"),
                        new AnswerStreamEvent.Text("Early coverage "),
                        new AnswerStreamEvent.Tagged(
                                "citation",
                                "{\"start_index\":0,\"end_index\":12,\"number\":1,\"url\":\"https://search.brave.com/\","
                                        + "\"favicon\":\"https://search.brave.com/favicon.ico\","
                                        + "\"snippet\":\"an independent index where a < b holds\"}"),
                        new AnswerStreamEvent.Text("establishes the baseline."),
                        new AnswerStreamEvent.Tagged("entity", "{\"name\":\"Brave Software\",\"url\":\"https://brave.com\"}"),
                        new AnswerStreamEvent.UnknownTag("weather", "{\"temperature_c\":21}"),
                        new AnswerStreamEvent.Tagged("answer", "{\"text\":\"An independent index with its own crawler.\"}"),
                        new AnswerStreamEvent.Tagged(
                                "usage",
                                "{\"X-Request-Requests\":\"1\",\"X-Request-Queries\":\"2\",\"X-Request-Tokens-In\":\"900\","
                                        + "\"X-Request-Tokens-Out\":\"120\",\"X-Request-Total-Cost\":\"0.0042\"}"),
                        new AnswerStreamEvent.Passthrough("finish_reason"),
                        new AnswerStreamEvent.Passthrough("empty choices")),
                events);
    }

    private static byte[] readFixture() {
        InputStream stream = AnswerTagDecoderTest.class.getResourceAsStream("/fixtures/brave/answers/stream.sse");
        if (stream == null) {
            throw new IllegalStateException("the answers stream fixture is missing");
        }
        try (stream) {
            return stream.readAllBytes();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}

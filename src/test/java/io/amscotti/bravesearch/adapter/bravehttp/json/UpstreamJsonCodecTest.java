package io.amscotti.bravesearch.adapter.bravehttp.json;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The tolerant upstream codec of the library path: every bounded body parses into exactly one
 * lossless tree, decimals keep their exact scale so upstream numbers round-trip value-exactly,
 * unknown members ride along unchanged, and a body that is not exactly one JSON document is
 * rejected typed instead of silently truncated.
 */
final class UpstreamJsonCodecTest {

    @Test
    void decimalsRoundTripValueExactlyWithScaleAndLongSignificandsIntact() {
        JsonNode tree = UpstreamJsonCodec.readTree(new UpstreamPayload(
                "{\"exact\":0.1000000000000000000001,\"trailing\":1.10,\"integer\":3}".getBytes(UTF_8)));

        assertEquals(new BigDecimal("0.1000000000000000000001"), tree.path("exact").decimalValue());
        assertEquals(new BigDecimal("1.10"), tree.path("trailing").decimalValue());
        assertEquals(3, tree.path("integer").intValue());
    }

    @Test
    void unknownMembersAndDeepStructureRideAlongUnchanged() {
        byte[] body = "{\"web\":{\"results\":[{\"title\":\"a\",\"future\":{\"nested\":[true,null,-2.5]}}]}}"
                .getBytes(UTF_8);

        JsonNode tree = UpstreamJsonCodec.readTree(new UpstreamPayload(body));

        assertEquals("a", tree.path("web").path("results").path(0).path("title").stringValue());
        assertEquals(-2.5, tree.path("web")
                .path("results")
                .path(0)
                .path("future")
                .path("nested")
                .path(2)
                .doubleValue());
        assertEquals(true, tree.path("web").path("results").path(0).path("future").path("nested").path(0).booleanValue());
    }

    @Test
    void aBodyThatIsNotExactlyOneJsonDocumentIsRejected() {
        assertThrows(
                tools.jackson.core.JacksonException.class,
                () -> UpstreamJsonCodec.readTree(new UpstreamPayload("{\"a\":1} trailing".getBytes(UTF_8))));
        assertThrows(
                tools.jackson.core.JacksonException.class,
                () -> UpstreamJsonCodec.readTree(new UpstreamPayload("{\"a\":1} {\"b\":2}".getBytes(UTF_8))));
        assertThrows(
                tools.jackson.core.JacksonException.class,
                () -> UpstreamJsonCodec.readTree(new UpstreamPayload("not json at all".getBytes(UTF_8))));
    }

    @Test
    void parsedTreesAreMutableCallerOwnedValueObjects() {
        JsonNode tree = UpstreamJsonCodec.readTree(new UpstreamPayload("{\"greeting\":\"hello\"}".getBytes(UTF_8)));

        ((ObjectNode) tree).put("added", "mine");

        assertEquals("mine", tree.path("added").stringValue());
    }

    @Test
    void aNullBodyIsRejected() {
        assertThrows(NullPointerException.class, () -> UpstreamJsonCodec.readTree((UpstreamPayload) null));
    }

    @Test
    void aTagPayloadStringParsesIntoItsOneLosslessTree() {
        JsonNode tree = UpstreamJsonCodec.readTagPayload("{\"url\":\"https://example.test\",\"score\":0.5}");

        assertEquals("https://example.test", tree.path("url").stringValue());
        assertEquals(new BigDecimal("0.5"), tree.path("score").decimalValue());
    }

    @Test
    void aTagPayloadStringThatDoesNotParseYieldsTheMissingNode() {
        assertTrue(
                UpstreamJsonCodec.readTagPayload("{\"url\": \"https://example.test\"").isMissingNode(),
                "an unparseable tag payload parses to empty, never an exception");
        assertTrue(UpstreamJsonCodec.readTagPayload("not json at all").isMissingNode());
        assertTrue(UpstreamJsonCodec.readTagPayload("{\"a\":1} {\"b\":2}").isMissingNode());
        assertThrows(NullPointerException.class, () -> UpstreamJsonCodec.readTagPayload(null));
    }
}

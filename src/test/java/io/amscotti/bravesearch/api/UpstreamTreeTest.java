package io.amscotti.bravesearch.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.bravehttp.json.UpstreamJsonCodec;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The caller-owned snapshot semantics of the public boundary: every snapshot is a fresh
 * deep copy of the lossless upstream tree — decimals keep their exact scale, and mutating
 * one snapshot never reaches another snapshot of the same response.
 */
final class UpstreamTreeTest {

    @Test
    void eachSnapshotIsAnIndependentDeepCopy() {
        UpstreamTree tree = UpstreamTree.of(
                UpstreamJsonCodec.readTree(new UpstreamPayload("{\"greeting\":\"hello\"}".getBytes(UTF_8))));

        JsonNode first = tree.snapshot();
        JsonNode second = tree.snapshot();

        assertNotSame(first, second, "two snapshots are two trees");
        ((ObjectNode) first).put("greeting", "mutated");
        assertEquals("hello", second.path("greeting").stringValue(), "a caller's mutation stays in its own copy");
        assertEquals("hello", tree.snapshot().path("greeting").stringValue(), "later snapshots stay pristine");
    }

    @Test
    void decimalsKeepTheirExactScaleAcrossTheSnapshot() {
        UpstreamTree tree = UpstreamTree.of(UpstreamJsonCodec.readTree(
                new UpstreamPayload("{\"exact\":0.1000000000000000000001,\"trailing\":1.10}".getBytes(UTF_8))));

        JsonNode snapshot = tree.snapshot();

        assertEquals(new BigDecimal("0.1000000000000000000001"), snapshot.path("exact").decimalValue());
        assertEquals(new BigDecimal("1.10"), snapshot.path("trailing").decimalValue());
    }

    @Test
    void constructionRejectsANullRoot() {
        assertThrows(NullPointerException.class, () -> UpstreamTree.of(null));
    }

    @Test
    void treesCompareStructurallyOverTheirHeldRoots() {
        UpstreamTree first = tree("{\"greeting\":\"hello\"}");
        UpstreamTree same = tree("{\"greeting\":\"hello\"}");
        UpstreamTree different = tree("{\"greeting\":\"goodbye\"}");

        assertEquals(first, same, "two trees over equal roots compare equal");
        assertEquals(first.hashCode(), same.hashCode(), "equal trees hash equal");
        assertNotEquals(first, different, "a different root is a different tree");
        assertNotEquals(first, null, "no tree equals null");
        assertTrue(first.toString().contains("hello"), "the rendering names the tree it holds");
    }

    private static UpstreamTree tree(String json) {
        return UpstreamTree.of(UpstreamJsonCodec.readTree(new UpstreamPayload(json.getBytes(UTF_8))));
    }
}

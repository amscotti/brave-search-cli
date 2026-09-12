package io.amscotti.bravesearch.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.bravehttp.json.UpstreamJsonCodec;
import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The public response shape: every endpoint response is a plain class whose only upstream
 * surface is the {@code upstream()} snapshot — the tree-holding collaborator is a private
 * field, not a public component — while the structural equality and the independent
 * caller-owned snapshots the record shape carried survive.
 */
final class PublicResponsesTest {

    @Test
    void noPublicResponseExposesItsUpstreamSourceAsAPublicComponent() {
        for (Class<?> response : responses()) {
            assertNull(
                    response.getRecordComponents(),
                    response.getSimpleName() + " hides its upstream source: it is not a record");
        }
    }

    @Test
    void everyResponseComparesStructurallyOverMetadataAndUpstreamTree() {
        for (ResponseShape shape : shapes()) {
            Object first = shape.build().apply(200, "req-1");
            Object same = shape.build().apply(200, "req-1");
            Object otherRequest = shape.build().apply(200, "req-2");
            Object otherStatus = shape.build().apply(206, "req-1");

            assertEquals(first, same, shape.name + ": equal metadata and equal trees compare equal");
            assertEquals(first.hashCode(), same.hashCode(), shape.name + ": equal exchanges hash equal");
            assertNotEquals(
                    first, otherRequest, shape.name + ": a different request id is a different exchange");
            assertNotEquals(
                    first, otherStatus, shape.name + ": a different status is a different exchange");
            assertTrue(
                    first.toString().contains("200") && first.toString().contains("req-1"),
                    shape.name + ": the debug rendering names the exchange it describes");
        }
    }

    @Test
    void equalityHashingAndRenderingReadTheHeldTreeNeverASnapshot() {
        CopyCountingNode firstRoot = new CopyCountingNode();
        CopyCountingNode secondRoot = new CopyCountingNode();
        WebSearchResponse first =
                new WebSearchResponse(200, RateLimitSnapshot.empty(), null, "req-1", null, UpstreamTree.of(firstRoot));
        WebSearchResponse same =
                new WebSearchResponse(200, RateLimitSnapshot.empty(), null, "req-1", null, UpstreamTree.of(secondRoot));

        assertEquals(first, same, "equal exchanges still compare equal");
        assertEquals(first.hashCode(), same.hashCode(), "equal exchanges still hash equal");
        assertTrue(first.toString().contains("req-1"), "the rendering still names the exchange");

        assertEquals(
                0,
                firstRoot.copies.get(),
                "structural comparison reads the held tree; hashing a response never deep-copies the body");
        assertEquals(0, secondRoot.copies.get(), "the compared side reads its held tree too");
        assertNotNull(first.upstream(), "upstream() keeps handing out snapshots");
        assertEquals(1, firstRoot.copies.get(), "only upstream() snapshots the tree");
    }

    @Test
    void everyUpstreamCallReturnsAnIndependentCallerOwnedTree() {
        for (ResponseShape shape : shapes()) {
            Object response = shape.build().apply(200, "req-1");

            ObjectNode first = (ObjectNode) shape.upstream().apply(response);
            ObjectNode second = (ObjectNode) shape.upstream().apply(response);
            first.put("injected", "mine");

            assertEquals(
                    "pristine",
                    second.path("greeting").stringValue(),
                    shape.name + ": one snapshot never sees another's mutation");
            assertEquals(
                    "pristine",
                    shape.upstream().apply(response).path("greeting").stringValue(),
                    shape.name + ": the held tree stays pristine");
        }
    }

    private record ResponseShape(
            String name, BiFunction<Integer, String, Object> build, Function<Object, JsonNode> upstream) {}

    private static ResponseShape[] shapes() {
        return new ResponseShape[] {
            new ResponseShape("web", PublicResponsesTest::web, o -> ((WebSearchResponse) o).upstream()),
            new ResponseShape("context", PublicResponsesTest::context, o -> ((ContextResponse) o).upstream()),
            new ResponseShape("news", PublicResponsesTest::news, o -> ((NewsSearchResponse) o).upstream()),
            new ResponseShape("videos", PublicResponsesTest::videos, o -> ((VideoSearchResponse) o).upstream()),
            new ResponseShape("images", PublicResponsesTest::images, o -> ((ImageSearchResponse) o).upstream()),
            new ResponseShape("suggest", PublicResponsesTest::suggest, o -> ((SuggestResponse) o).upstream()),
            new ResponseShape(
                    "spellcheck", PublicResponsesTest::spellcheck, o -> ((SpellcheckResponse) o).upstream()),
            new ResponseShape("places", PublicResponsesTest::places, o -> ((PlaceSearchResponse) o).upstream()),
            new ResponseShape(
                    "enrichment",
                    PublicResponsesTest::enrichment,
                    o -> ((PlaceEnrichmentResponse) o).upstream()),
            new ResponseShape("rich", PublicResponsesTest::rich, o -> ((RichResponse) o).upstream()),
            new ResponseShape("answers", PublicResponsesTest::answers, o -> ((AnswersResponse) o).upstream()),
        };
    }

    private static WebSearchResponse web(int status, String requestId) {
        return new WebSearchResponse(
                status, RateLimitSnapshot.empty(), null, requestId, null, upstreamTree());
    }

    private static ContextResponse context(int status, String requestId) {
        return new ContextResponse(
                status, RateLimitSnapshot.empty(), null, requestId, null, upstreamTree());
    }

    private static NewsSearchResponse news(int status, String requestId) {
        return new NewsSearchResponse(
                status, RateLimitSnapshot.empty(), null, requestId, null, upstreamTree());
    }

    private static VideoSearchResponse videos(int status, String requestId) {
        return new VideoSearchResponse(
                status, RateLimitSnapshot.empty(), null, requestId, null, upstreamTree());
    }

    private static ImageSearchResponse images(int status, String requestId) {
        return new ImageSearchResponse(
                status, RateLimitSnapshot.empty(), null, requestId, null, upstreamTree());
    }

    private static SuggestResponse suggest(int status, String requestId) {
        return new SuggestResponse(
                status, RateLimitSnapshot.empty(), null, requestId, null, upstreamTree());
    }

    private static SpellcheckResponse spellcheck(int status, String requestId) {
        return new SpellcheckResponse(
                status, RateLimitSnapshot.empty(), null, requestId, null, upstreamTree());
    }

    private static PlaceSearchResponse places(int status, String requestId) {
        return new PlaceSearchResponse(
                status, RateLimitSnapshot.empty(), null, requestId, null, upstreamTree());
    }

    private static PlaceEnrichmentResponse enrichment(int status, String requestId) {
        return new PlaceEnrichmentResponse(
                status, RateLimitSnapshot.empty(), null, requestId, null, upstreamTree());
    }

    private static RichResponse rich(int status, String requestId) {
        return new RichResponse(
                status, RateLimitSnapshot.empty(), null, requestId, null, upstreamTree());
    }

    private static AnswersResponse answers(int status, String requestId) {
        return new AnswersResponse(
                status, RateLimitSnapshot.empty(), null, requestId, null, upstreamTree());
    }

    private static UpstreamTree upstreamTree() {
        return UpstreamTree.of(UpstreamJsonCodec.readTree(
                new UpstreamPayload("{\"greeting\":\"pristine\"}".getBytes(UTF_8))));
    }

    private static Class<?>[] responses() {
        return new Class<?>[] {
            WebSearchResponse.class,
            ContextResponse.class,
            NewsSearchResponse.class,
            VideoSearchResponse.class,
            ImageSearchResponse.class,
            SuggestResponse.class,
            SpellcheckResponse.class,
            PlaceSearchResponse.class,
            PlaceEnrichmentResponse.class,
            RichResponse.class,
            AnswersResponse.class
        };
    }

    /**
     * An empty object node that counts every deep copy taken of it, so a test observes
     * exactly which operations snapshot the tree: only {@code upstream()} ever should.
     */
    private static final class CopyCountingNode extends ObjectNode {

        final AtomicInteger copies = new AtomicInteger();

        CopyCountingNode() {
            super(JsonNodeFactory.instance);
        }

        @Override
        public ObjectNode deepCopy() {
            copies.incrementAndGet();
            return super.deepCopy();
        }
    }
}

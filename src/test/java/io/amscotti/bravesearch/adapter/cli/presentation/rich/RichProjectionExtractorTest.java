package io.amscotti.bravesearch.adapter.cli.presentation.rich;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.domain.error.UnreadableBodyException;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.result.RichVerticals;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tolerant enumeration of the rich response's vertical blocks from the lossless body:
 * every top-level member whose value is an array is one vertical block, counted by its
 * array size and carrying its object elements' usable textual members; non-array
 * top-level members — the shared {@code type}, {@code query}, and any future block —
 * are never verticals. The upstream response shape is undocumented, so nothing else is
 * modeled: unknown vertical names enumerate exactly like known ones, non-object
 * elements still count as items while yielding no members, and only a body that is not
 * one readable JSON document fails.
 */
final class RichProjectionExtractorTest {

    private final RichProjectionExtractor extractor = new RichProjectionExtractor(new JsonMappers());

    @Test
    void enumeratesEveryTopLevelArrayMemberAsAVerticalInDocumentOrder() {
        RichVerticals verticals = extractor.extract(body("full-results.json"));

        assertEquals(4, verticals.verticals().size());
        assertEquals("videos", verticals.verticals().get(0).name());
        assertEquals("images", verticals.verticals().get(1).name());
        assertEquals("faqs", verticals.verticals().get(2).name());
        assertEquals("future_widgets", verticals.verticals().get(3).name());
        for (int position = 0; position < 4; position++) {
            assertEquals(position, verticals.verticals().get(position).position());
        }
    }

    @Test
    void itemCountsNameTheWholeArraySizeWhileMembersComeOnlyFromObjectElements() {
        RichVerticals verticals = extractor.extract(body("full-results.json"));

        assertEquals(7, verticals.itemCount());
        RichVerticals.Vertical images = verticals.verticals().get(1);
        assertEquals(2, images.itemCount());
        assertEquals(2, images.items().size(), "an object element yields a slot even without usable members");
        assertNull(images.items().get(1).title(), "a non-textual member is omitted, never coerced");
    }

    @Test
    void aNonObjectElementCountsAsAnItemWithoutYieldingAMemberSlot() {
        RichVerticals verticals = extractor.extract(
                new UpstreamPayload("{\"boxes\": [\"bare\", {\"title\": \"T\"}]}".getBytes(UTF_8)));

        RichVerticals.Vertical boxes = verticals.verticals().getFirst();
        assertEquals("boxes", boxes.name());
        assertEquals(2, boxes.itemCount(), "the whole array size is the item count");
        assertEquals(1, boxes.items().size(), "a non-object element yields no member slot");
        assertEquals("T", boxes.items().getFirst().title());
    }

    @Test
    void usableTextualMembersTravelAndAbsentOnesStayNull() {
        RichVerticals verticals = extractor.extract(body("full-results.json"));

        RichVerticals.Vertical videos = verticals.verticals().get(0);
        assertEquals(2, videos.items().size());
        assertEquals("First Provider Video", videos.items().get(0).title());
        assertEquals("https://videos.example.com/first", videos.items().get(0).url());
        assertEquals("First provider video description.", videos.items().get(0).description());
        assertEquals("Example Video Provider", videos.items().get(0).source());
        assertNull(videos.items().get(1).description(), "an absent member is null, never an empty placeholder");
        assertEquals("Second Provider Video", videos.items().get(1).title());

        RichVerticals.Vertical images = verticals.verticals().get(1);
        assertEquals("Provider Image", images.items().get(0).title());
        assertNull(images.items().get(0).description());

        RichVerticals.Vertical unknown = verticals.verticals().get(3);
        assertEquals(2, unknown.items().size(), "every object element yields an item slot, members or not");
        assertNull(unknown.items().get(0).title());
        assertNull(unknown.items().get(0).url());
        assertNull(unknown.items().get(0).description());
        assertNull(unknown.items().get(0).source());
    }

    @Test
    void aBodyWithoutArrayMembersCarriesNoVerticals() {
        RichVerticals verticals = extractor.extract(body("zero-results.json"));

        assertEquals(List.of(), verticals.verticals());
        assertEquals(0, verticals.itemCount());
    }

    @Test
    void aBodyThatIsNotOneReadableJsonDocumentFailsLoudly() {
        assertThrows(
                UnreadableBodyException.class,
                () -> extractor.extract(new UpstreamPayload("not json".getBytes(UTF_8))));
    }

    private static UpstreamPayload body(String name) {
        String resource = "/fixtures/brave/rich/" + name;
        try (InputStream bytes = RichProjectionExtractorTest.class.getResourceAsStream(resource)) {
            if (bytes == null) {
                throw new IllegalStateException("missing fixture " + resource);
            }
            return new UpstreamPayload(bytes.readAllBytes());
        } catch (IOException missing) {
            throw new UncheckedIOException(missing);
        }
    }
}

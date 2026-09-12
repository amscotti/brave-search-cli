package io.amscotti.bravesearch.adapter.cli.presentation.places;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.domain.error.UnreadableBodyException;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.result.OrderReconstructor;
import io.amscotti.bravesearch.domain.result.PlaceDetails;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tolerant enumeration of the POI detail entries from the lossless upstream body: the
 * {@code results} array is walked in order, every usable object element with a textual
 * {@code id} becomes one identified payload, and everything the element carried beyond
 * the projected inventory is deliberately ignored. Only a body that is not exactly one
 * readable JSON document fails, loudly and without quoting the body.
 */
final class PlaceDetailsExtractorTest {

    private final PlaceDetailsExtractor extractor = new PlaceDetailsExtractor(new JsonMappers());

    @Test
    void everyUsableMemberOfAnEntryIsCarriedById() {
        String body = """
                {"type":"local_pois","results":[
                  {"id":"poi-a","title":"First POI","url":"https://example.com/first",
                   "description":"First text.","postal_address":{"displayAddress":"1 Main St"},
                   "contact":{"telephone":"+1 215 555 0100","email":"info@example.com"},
                   "price_range":"$$","timezone":"America/New_York",
                   "thumbnail":{"src":"https://example.com/thumb.jpg"}},
                  {"id":"poi-b","title":"Second POI"}
                ]}
                """;

        List<OrderReconstructor.Identified<PlaceDetails>> entries = extractor.extract(payload(body));

        assertEquals(2, entries.size());
        assertEquals("poi-a", entries.getFirst().id());
        PlaceDetails first = entries.getFirst().value();
        assertEquals("First POI", first.title());
        assertEquals("https://example.com/first", first.url());
        assertEquals("First text.", first.description());
        assertEquals("1 Main St", first.displayAddress());
        assertEquals("+1 215 555 0100", first.phone());
        assertEquals("info@example.com", first.email());
        assertEquals("$$", first.priceRange());
        assertEquals("America/New_York", first.timezone());
        assertEquals("https://example.com/thumb.jpg", first.thumbnail());
        PlaceDetails second = entries.get(1).value();
        assertEquals("Second POI", second.title());
        assertEquals(null, second.url(), "an absent member is null, never a placeholder");
    }

    @Test
    void nonObjectElementsAndElementsWithoutATextualIdAreSkippedWithoutBreakingPositions() {
        String body =
                "{\"results\":[\"not an object\",{\"title\":\"no id here\"},{\"id\":\"poi-a\"},null,42]}";

        List<OrderReconstructor.Identified<PlaceDetails>> entries = extractor.extract(payload(body));

        assertEquals(1, entries.size(), "only the element with a textual id enumerates");
        assertEquals("poi-a", entries.getFirst().id());
    }

    @Test
    void aMissingNullOrNonArrayResultsMemberDegradesToNoEntries() {
        assertEquals(0, extractor.extract(payload("{}")).size());
        assertEquals(0, extractor.extract(payload("{\"results\":null}")).size());
        assertEquals(0, extractor.extract(payload("{\"results\":\"nope\"}")).size());
    }

    @Test
    void nonTextualMembersAreOmittedNeverCoerced() {
        String body = "{\"results\":[{\"id\":\"poi-a\",\"title\":7,\"price_range\":true,"
                + "\"thumbnail\":{\"src\":[\"not text\"]}}]}";

        PlaceDetails entry = extractor.extract(payload(body)).getFirst().value();

        assertEquals(null, entry.title());
        assertEquals(null, entry.priceRange());
        assertEquals(null, entry.thumbnail());
    }

    @Test
    void aBodyThatIsNotOneReadableJsonDocumentFailsLoudly() {
        assertThrows(UnreadableBodyException.class, () -> extractor.extract(payload("gateway exploded <html>")));
        assertThrows(UnreadableBodyException.class, () -> extractor.extract(payload("")));
    }

    @Test
    void unknownTopLevelMembersAndOrderingHintsAreIgnored() {
        String body = "{\"type\":\"local_pois\",\"results\":[{\"id\":\"poi-a\",\"unknown_future_block\":{\"x\":1}}],"
                + "\"mixed\":[{\"ignored\":true}],\"unknown\":[1,2]}";

        List<OrderReconstructor.Identified<PlaceDetails>> entries = extractor.extract(payload(body));

        assertEquals(1, entries.size());
        assertTrue(entries.getFirst().value().title() == null, "only the documented inventory carries");
    }

    private static UpstreamPayload payload(String body) {
        return new UpstreamPayload(body.getBytes(UTF_8));
    }
}

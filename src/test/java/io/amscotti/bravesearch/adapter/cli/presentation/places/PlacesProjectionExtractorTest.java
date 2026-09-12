package io.amscotti.bravesearch.adapter.cli.presentation.places;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.result.PlaceResults;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;

/**
 * Tolerant enumeration of the places logical results from the lossless upstream body:
 * every documented response bucket is walked in its documented order, each usable
 * object element becomes one entry carrying its bucket's wire word and its original
 * zero-based position inside that bucket's array, and anything else — a null or
 * omitted bucket, a non-array member, a non-object element — degrades to omitted
 * entries. The {@code mixed} ordering hints are deliberately ignored, because
 * enumeration follows the documented bucket order; a body that is not one readable
 * JSON document is the unreadable-body failure, never an invented result list.
 */
final class PlacesProjectionExtractorTest {

    private final PlacesProjectionExtractor extractor = new PlacesProjectionExtractor(new JsonMappers());

    @Test
    void enumeratesEveryDocumentedBucketInOrderWithPerEntryLabelsAndPositions() {
        PlaceResults results = extractor.extract(new UpstreamPayload(fixture("full-results.json")));

        assertEquals(5, results.entries().size());
        PlaceResults.Entry first = results.entries().get(0);
        assertEquals("results", first.bucket());
        assertEquals(0, first.position());
        assertEquals("place-first", first.id());
        assertEquals("First Place", first.title());
        assertEquals("1 Main St, Philadelphia PA", first.address());
        assertEquals("4.5", first.ratingValue());
        assertEquals("1212", first.ratingCount());
        assertEquals("1.2 km", first.distance());
        assertEquals("+1 215 555 0100", first.phone());
        assertEquals("https://example.com/first", first.website());

        PlaceResults.Entry second = results.entries().get(1);
        assertEquals("results", second.bucket());
        assertEquals(1, second.position());
        assertEquals("4", second.ratingValue(), "a whole-number rating keeps its exact textual form");
        assertNull(second.distance());

        PlaceResults.Entry numericIdOnly = results.entries().get(2);
        assertEquals("results", numericIdOnly.bucket());
        assertEquals(2, numericIdOnly.position());
        assertNull(numericIdOnly.id(), "an id that is not textual is unusable, never invented");
        assertNull(numericIdOnly.title());

        PlaceResults.Entry city = results.entries().get(3);
        assertEquals("cities", city.bucket(), "the enumeration continues into the next populated bucket");
        assertEquals(0, city.position(), "the position counts inside the entry's own bucket");
        assertEquals("Philadelphia", city.title());
        assertEquals("Pennsylvania, United States", city.address());

        PlaceResults.Entry address = results.entries().get(4);
        assertEquals("addresses", address.bucket(), "empty and null buckets in between are skipped whole");
        assertEquals(0, address.position());
        assertEquals("1200 Market St", address.title());
    }

    @Test
    void everyNullOmittedOrEmptyBucketYieldsNoEntries() {
        PlaceResults results = extractor.extract(new UpstreamPayload(fixture("zero-results.json")));

        assertEquals(0, results.entries().size(), "null buckets are not failures, just empty listings");
    }

    @Test
    void aBodyWithoutAnyBucketMemberIsAnEmptyListing() {
        PlaceResults results = extractor.extract(new UpstreamPayload("{\"type\":\"places\"}".getBytes(UTF_8)));

        assertEquals(0, results.entries().size());
    }

    @Test
    void aNonObjectElementLeavesItsPositionGapInsideItsBucket() {
        PlaceResults results = extractor.extract(
                new UpstreamPayload(
                        "{\"streets\":[\"bare\",{\"id\":\"st-market\",\"title\":\"Market St\"}]}".getBytes(UTF_8)));

        assertEquals(1, results.entries().size(), "the bare string element degrades to an omitted entry");
        PlaceResults.Entry street = results.entries().get(0);
        assertEquals("streets", street.bucket());
        assertEquals(1, street.position(), "the position names the original upstream index, never a renumbered one");
        assertEquals("st-market", street.id());
    }

    private static byte[] fixture(String name) {
        String resource = "/fixtures/brave/places/" + name;
        try (InputStream bytes = PlacesProjectionExtractorTest.class.getResourceAsStream(resource)) {
            if (bytes == null) {
                throw new IllegalStateException("missing fixture " + resource);
            }
            return bytes.readAllBytes();
        } catch (IOException missing) {
            throw new UncheckedIOException(missing);
        }
    }
}

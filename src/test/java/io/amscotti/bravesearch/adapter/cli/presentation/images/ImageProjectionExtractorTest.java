package io.amscotti.bravesearch.adapter.cli.presentation.images;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.domain.error.UnreadableBodyException;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.result.ImageResults;
import org.junit.jupiter.api.Test;

/**
 * Tolerant enumeration of the images logical results: the upstream top-level {@code
 * results} array is the whole list, positions name the original upstream index, unusable
 * members degrade to omitted entries, and only a body that is not one readable JSON
 * document fails.
 */
final class ImageProjectionExtractorTest {

    private final ImageProjectionExtractor extractor = new ImageProjectionExtractor(new JsonMappers());

    @Test
    void enumeratesTheImageResultsWithTheirPositionsAndTextualMembers() {
        ImageResults extracted = extractor.extract(body("""
                {"type":"images","query":{"original":"three word query"},"results":[
                  {"type":"image_result","title":"First Image","url":"https://example.com/first","image":"https://images.example.com/first-full.jpg","thumbnail":"https://images.example.com/first-thumb.jpg","dimension":{"width":1200,"height":800}},
                  {"type":"image_result","title":"Second Image","url":"https://example.com/second","image":"https://images.example.com/second-full.jpg"},
                  {"type":"image_result","title":"Third Image","url":"https://example.com/third"}
                ],"extra":{"might_be_offensive":false}}
                """));

        assertEquals(3, extracted.entries().size());
        assertEquals(
                new ImageResults.Entry(
                        0,
                        "First Image",
                        "https://example.com/first",
                        "https://images.example.com/first-full.jpg",
                        "https://images.example.com/first-thumb.jpg"),
                extracted.entries().getFirst());
        assertEquals(
                new ImageResults.Entry(
                        1, "Second Image", "https://example.com/second", "https://images.example.com/second-full.jpg", null),
                extracted.entries().get(1));
        assertEquals(new ImageResults.Entry(2, "Third Image", "https://example.com/third", null, null),
                extracted.entries().get(2));
    }

    @Test
    void nonTextualKnownMembersAreOmittedRatherThanRendered() {
        ImageResults extracted = extractor.extract(
                body("{\"results\":[{\"title\":42,\"url\":\"https://example.com/first\",\"image\":7,\"thumbnail\":true}]}"));

        assertEquals(1, extracted.entries().size());
        assertEquals(new ImageResults.Entry(0, null, "https://example.com/first", null, null),
                extracted.entries().getFirst());
    }

    @Test
    void aSkippedElementLeavesItsPositionGap() {
        ImageResults extracted = extractor.extract(
                body("{\"results\":[\"not an object\",{\"title\":\"Second\",\"url\":\"https://example.com/second\"}]}"));

        assertEquals(1, extracted.entries().size());
        assertEquals(1, extracted.entries().getFirst().position(), "the position names the original upstream index");
    }

    @Test
    void aMissingResultsArrayDegradesToZeroResults() {
        assertEquals(0, extractor.extract(body("{\"query\":{\"original\":\"nothing\"}}")).entries().size());
        assertEquals(0, extractor.extract(body("{\"results\":\"not an array\"}")).entries().size());
        assertEquals(0, extractor.extract(body("{}")).entries().size());
    }

    @Test
    void aBodyThatIsNotOneReadableJsonDocumentFailsLoudly() {
        assertThrows(UnreadableBodyException.class, () -> extractor.extract(body("gateway exploded <html>")));
        assertThrows(UnreadableBodyException.class, () -> extractor.extract(body("")));
    }

    @Test
    void nestedBucketArraysNeverContributeImageResults() {
        ImageResults extracted = extractor.extract(
                body("{\"news\":{\"results\":[{\"title\":\"A News Result\",\"url\":\"https://example.com/news\"}]}}"));

        assertTrue(extracted.entries().isEmpty(), "only the top-level results array enumerates image results");
    }

    private static UpstreamPayload body(String text) {
        return new UpstreamPayload(text.getBytes(UTF_8));
    }
}

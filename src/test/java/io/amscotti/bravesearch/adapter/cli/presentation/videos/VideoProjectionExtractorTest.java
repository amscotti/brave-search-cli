package io.amscotti.bravesearch.adapter.cli.presentation.videos;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.domain.error.UnreadableBodyException;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.result.VideoResults;
import org.junit.jupiter.api.Test;

/**
 * Tolerant enumeration of the videos logical results: the upstream top-level {@code
 * results} array is the whole list, positions name the original upstream index, unusable
 * members degrade to omitted entries, and only a body that is not one readable JSON
 * document fails.
 */
final class VideoProjectionExtractorTest {

    private final VideoProjectionExtractor extractor = new VideoProjectionExtractor(new JsonMappers());

    @Test
    void enumeratesTheVideoResultsWithTheirPositionsAndTextualMembers() {
        VideoResults extracted = extractor.extract(body("""
                {"type":"videos","query":{"original":"three word query"},"results":[
                  {"type":"video_result","title":"First Video","url":"https://example.com/first","description":"First text.","age":"2 days ago"},
                  {"type":"video_result","title":"Second Video","url":"https://example.com/second","age":"2026-08-30T12:00:00Z"},
                  {"type":"video_result","title":"Third Video","url":"https://example.com/third"}
                ],"extra":{"might_be_offensive":false}}
                """));

        assertEquals(3, extracted.entries().size());
        assertEquals(
                new VideoResults.Entry(
                        0, "First Video", "https://example.com/first", "First text.", "2 days ago"),
                extracted.entries().getFirst());
        assertEquals(new VideoResults.Entry(1, "Second Video", "https://example.com/second", null, "2026-08-30T12:00:00Z"),
                extracted.entries().get(1));
        assertEquals(new VideoResults.Entry(2, "Third Video", "https://example.com/third", null, null),
                extracted.entries().get(2));
    }

    @Test
    void nonTextualKnownMembersAreOmittedRatherThanRendered() {
        VideoResults extracted = extractor.extract(
                body("{\"results\":[{\"title\":42,\"url\":\"https://example.com/first\",\"age\":7}]}"));

        assertEquals(1, extracted.entries().size());
        assertEquals(new VideoResults.Entry(0, null, "https://example.com/first", null, null), extracted.entries().getFirst());
    }

    @Test
    void aSkippedElementLeavesItsPositionGap() {
        VideoResults extracted = extractor.extract(
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
    void nestedBucketArraysNeverContributeVideoResults() {
        VideoResults extracted = extractor.extract(
                body("{\"news\":{\"results\":[{\"title\":\"A News Result\",\"url\":\"https://example.com/news\"}]}}"));

        assertTrue(extracted.entries().isEmpty(), "only the top-level results array enumerates video results");
    }

    private static UpstreamPayload body(String text) {
        return new UpstreamPayload(text.getBytes(UTF_8));
    }
}

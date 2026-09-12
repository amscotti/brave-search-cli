package io.amscotti.bravesearch.adapter.cli.presentation.news;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.domain.error.UnreadableBodyException;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.result.NewsResults;
import org.junit.jupiter.api.Test;

/**
 * Tolerant enumeration of the news logical results: the upstream {@code news.results} array
 * is the whole list, with a shared top-level {@code results} array of tagged elements as
 * the fallback while the bucket is absent — other result types never leak in, and the
 * documented bucket always wins. Positions name the original upstream index, unusable
 * members degrade to omitted entries, and only a body that is not one readable JSON
 * document fails.
 */
final class NewsProjectionExtractorTest {

    private final NewsProjectionExtractor extractor = new NewsProjectionExtractor(new JsonMappers());

    @Test
    void enumeratesTheNewsResultsWithTheirPositionsAndTextualMembers() {
        NewsResults extracted = extractor.extract(body("""
                {"news":{"results":[
                  {"title":"First Headline","url":"https://example.com/first","description":"First text.","age":"2 hours ago"},
                  {"title":"Second Headline","url":"https://example.com/second","age":"2026-08-30T12:00:00Z"},
                  {"title":"Third Headline","url":"https://example.com/third"}
                ]}}
                """));

        assertEquals(3, extracted.entries().size());
        assertEquals(
                new NewsResults.Entry(
                        0, "First Headline", "https://example.com/first", "First text.", "2 hours ago"),
                extracted.entries().getFirst());
        assertEquals(new NewsResults.Entry(1, "Second Headline", "https://example.com/second", null, "2026-08-30T12:00:00Z"),
                extracted.entries().get(1));
        assertEquals(new NewsResults.Entry(2, "Third Headline", "https://example.com/third", null, null),
                extracted.entries().get(2));
    }

    @Test
    void nonTextualKnownMembersAreOmittedRatherThanRendered() {
        NewsResults extracted = extractor.extract(
                body("{\"news\":{\"results\":[{\"title\":42,\"url\":\"https://example.com/first\",\"age\":7}]}}"));

        assertEquals(1, extracted.entries().size());
        assertEquals(new NewsResults.Entry(0, null, "https://example.com/first", null, null), extracted.entries().getFirst());
    }

    @Test
    void aSkippedElementLeavesItsPositionGap() {
        NewsResults extracted = extractor.extract(
                body("{\"news\":{\"results\":[\"not an object\",{\"title\":\"Second\",\"url\":\"https://example.com/second\"}]}}"));

        assertEquals(1, extracted.entries().size());
        assertEquals(1, extracted.entries().getFirst().position(), "the position names the original upstream index");
    }

    @Test
    void aMissingNewsBlockDegradesToZeroResults() {
        assertEquals(0, extractor.extract(body("{\"query\":{\"original\":\"nothing\"}}")).entries().size());
        assertEquals(0, extractor.extract(body("{\"news\":\"not an object\"}")).entries().size());
        assertEquals(0, extractor.extract(body("{\"news\":{\"results\":\"not an array\"}}")).entries().size());
    }

    @Test
    void aBodyThatIsNotOneReadableJsonDocumentFailsLoudly() {
        assertThrows(UnreadableBodyException.class, () -> extractor.extract(body("gateway exploded <html>")));
        assertThrows(UnreadableBodyException.class, () -> extractor.extract(body("")));
    }

    @Test
    void otherBucketsNeverContributeNewsResults() {
        NewsResults extracted = extractor.extract(
                body("{\"web\":{\"results\":[{\"title\":\"A Web Result\",\"url\":\"https://example.com/web\"}]}}"));

        assertTrue(extracted.entries().isEmpty(), "only the news bucket enumerates news results");
    }

    @Test
    void topLevelTypedNewsResultsEnumerateWhenTheNewsBucketIsAbsent() {
        NewsResults extracted = extractor.extract(body("""
                {"type":"news","query":{"original":"Muse Spark 1.3"},
                  "results":[
                    {"type":"news_result","title":"First Headline","url":"https://example.com/first","description":"First text.","age":"2 days ago"},
                    {"type":"news_result","title":"Second Headline","url":"https://example.com/second"}
                  ]}
                """));

        assertEquals(2, extracted.entries().size());
        assertEquals(
                new NewsResults.Entry(
                        0, "First Headline", "https://example.com/first", "First text.", "2 days ago"),
                extracted.entries().getFirst());
        assertEquals(
                new NewsResults.Entry(1, "Second Headline", "https://example.com/second", null, null),
                extracted.entries().get(1));
    }

    @Test
    void topLevelResultsOfOtherTypesNeverEnumerateAsNews() {
        NewsResults extracted = extractor.extract(body("""
                {"type":"news","query":{"original":"Muse Spark 1.3"},
                  "results":[
                    {"type":"web_result","title":"A Web Result","url":"https://example.com/web"},
                    {"title":"An Untagged Result","url":"https://example.com/untagged"}
                  ]}
                """));

        assertEquals(1, extracted.entries().size());
        assertEquals("https://example.com/untagged", extracted.entries().getFirst().url());
    }

    @Test
    void theDocumentedNewsBucketWinsOverTopLevelResults() {
        NewsResults extracted = extractor.extract(body("""
                {"news":{"results":[
                    {"title":"Bucket Headline","url":"https://example.com/bucket"}
                  ]},
                  "results":[
                    {"type":"news_result","title":"Top-Level Headline","url":"https://example.com/top"}
                  ]}
                """));

        assertEquals(1, extracted.entries().size());
        assertEquals("https://example.com/bucket", extracted.entries().getFirst().url());
    }

    private static UpstreamPayload body(String text) {
        return new UpstreamPayload(text.getBytes(UTF_8));
    }
}

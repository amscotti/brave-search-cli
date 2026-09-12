package io.amscotti.bravesearch.adapter.cli.presentation.suggest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.result.SuggestResults;
import org.junit.jupiter.api.Test;

/**
 * Tolerant enumeration of the suggest logical results from the lossless upstream body:
 * the shared array walk over the upstream top-level {@code results} path, with every
 * usable array element becoming one entry carrying its original zero-based position and
 * its suggested query completion plus whatever usable textual rich members — kind,
 * title, description, image url — it carried. The deprecated upstream {@code is_entity}
 * flag is ignored because {@code type} is its documented replacement; an element whose
 * {@code query} member is not textual is skipped whole, leaving a position gap, because
 * a suggestion without its completion text is not renderable.
 */
final class SuggestProjectionExtractorTest {

    private final SuggestProjectionExtractor extractor = new SuggestProjectionExtractor(new JsonMappers());

    @Test
    void enumeratesTheDocumentedSuggestionMembersWithOriginalPositions() {
        SuggestResults results = extractor.extract(body("""
                        {"results":[
                          {"query":"first completion","type":"query"},
                          {"query":"second completion","type":"entity","is_entity":true,
                           "title":"Enriched Title","description":"Enriched text.","img":"https://e.example/i.png"}
                        ]}
                        """));

        assertEquals(2, results.entries().size());
        SuggestResults.Entry first = results.entries().get(0);
        assertEquals(0, first.position());
        assertEquals("first completion", first.query());
        assertEquals("query", first.type());
        SuggestResults.Entry second = results.entries().get(1);
        assertEquals(1, second.position());
        assertEquals("entity", second.type());
        assertEquals("Enriched Title", second.title());
        assertEquals("Enriched text.", second.description());
        assertEquals("https://e.example/i.png", second.img());
    }

    @Test
    void anElementWithoutUsuableCompletionTextIsSkippedWithItsPositionGapKept() {
        SuggestResults results = extractor.extract(
                body("{\"results\":[{\"query\":42},{\"query\":\"usable completion\"}]}"));

        assertEquals(1, results.entries().size());
        assertEquals(1, results.entries().getFirst().position(), "the position names the original upstream index");
    }

    @Test
    void aMissingOrNonArrayResultsMemberYieldsZeroSuggestions() {
        assertEquals(0, extractor.extract(body("{\"query\":{\"original\":\"q\"}}")).entries().size());
        assertEquals(0, extractor.extract(body("{\"results\":\"nope\"}")).entries().size());
    }

    private static UpstreamPayload body(String json) {
        return new UpstreamPayload(json.getBytes(UTF_8));
    }
}

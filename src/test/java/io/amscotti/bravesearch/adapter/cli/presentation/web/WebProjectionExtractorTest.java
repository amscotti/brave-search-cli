package io.amscotti.bravesearch.adapter.cli.presentation.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.result.WebResults;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tolerant enumeration of the web logical results: {@code web.results} when present, zero
 * results whenever it is absent or unusable, and never a broken render because an unknown or
 * malformed member appeared — only a body that is not one readable JSON document fails, and it
 * fails loudly so the machine surfaces can classify the exchange as malformed.
 */
final class WebProjectionExtractorTest {

    private final WebProjectionExtractor extractor = new WebProjectionExtractor(new JsonMappers());

    @Test
    void fullResultsFixtureYieldsEveryEntryWithTypedFieldsAndOriginalPositions() {
        WebResults results = extractor.extract(fixture("full-results.json"));

        assertEquals(3, results.entries().size());
        assertEquals(
                new WebResults.Entry(0, "First Title", "https://example.com/first", "First description text."),
                results.entries().get(0));
        assertEquals(new WebResults.Entry(1, "Second Title", "https://example.com/second", null), results.entries().get(1));
        assertEquals(new WebResults.Entry(2, null, "https://example.com/third", "Third description."), results.entries().get(2));
    }

    @Test
    void zeroResultsFixtureWithAnEmptyWebObjectYieldsNoEntries() {
        assertEquals(List.of(), extractor.extract(fixture("zero-results.json")).entries());
    }

    @Test
    void aBodyWithoutAWebBucketYieldsNoEntriesEvenWhenOtherBucketsHaveResults() {
        assertEquals(List.of(), extractor.extract(fixture("news-only.json")).entries());
    }

    @Test
    void absentMalformedAndNonMemberResultShapesAreOmittedWithoutBreakingTheirNeighbors() {
        UpstreamPayload body = new UpstreamPayload((
                "{\"web\":{\"results\":["
                        + "{\"url\":\"https://example.com/kept\",\"description\":\"kept\"},"
                        + "\"not-an-object\","
                        + "{\"title\":\"Later Title\",\"url\":\"https://example.com/later\"}"
                        + "]}}")
                .getBytes(UTF_8));

        WebResults results = extractor.extract(body);

        assertEquals(2, results.entries().size(), "the malformed middle entry is omitted");
        assertEquals(new WebResults.Entry(0, null, "https://example.com/kept", "kept"), results.entries().get(0));
        assertEquals(
                new WebResults.Entry(2, "Later Title", "https://example.com/later", null),
                results.entries().get(1),
                "positions stay the original array indices, so omission leaves a gap");
    }

    @Test
    void literalNullElementsAreSkippedWhileThePositionGapIsPreserved() {
        UpstreamPayload body = new UpstreamPayload((
                "{\"web\":{\"results\":["
                        + "null,"
                        + "{\"title\":\"After The Null\"},"
                        + "null"
                        + "]}}")
                .getBytes(UTF_8));

        WebResults results = extractor.extract(body);

        assertEquals(1, results.entries().size(), "the literal null elements become no entry");
        assertEquals(
                new WebResults.Entry(1, "After The Null", null, null),
                results.entries().getFirst(),
                "the surviving entry keeps its original array index, not a renumbered one");
    }

    @Test
    void nonTextualKnownMembersAreOmittedWhileTextualOnesSurvive() {
        UpstreamPayload body = new UpstreamPayload(
                "{\"web\":{\"results\":[{\"title\":true,\"url\":[\"not\",\"text\"],\"description\":7}]}}"
                        .getBytes(UTF_8));

        WebResults results = extractor.extract(body);

        assertEquals(List.of(new WebResults.Entry(0, null, null, null)), results.entries());
    }

    @Test
    void aWebMemberThatIsNotAnObjectOrAResultsMemberThatIsNotAnArrayYieldsNoEntries() {
        assertEquals(List.of(), extractor.extract(body("{\"web\":[\"not\",\"an\",\"object\"]}")).entries());
        assertEquals(List.of(), extractor.extract(body("{\"web\":\"not an object\"}")).entries());
        assertEquals(List.of(), extractor.extract(body("{\"web\":{\"results\":\"not an array\"}}")).entries());
    }

    @Test
    void aNonObjectUpstreamBodyYieldsNoEntries() {
        assertEquals(List.of(), extractor.extract(body("[{\"title\":\"array element\"}]")).entries());
        assertEquals(List.of(), extractor.extract(body("\"just text\"")).entries());
        assertEquals(List.of(), extractor.extract(body("123")).entries());
    }

    @Test
    void aBodyThatIsNotReadableJsonFailsLoudlyWithoutQuotingIt() {
        UpstreamPayload garbage = new UpstreamPayload("gateway exploded <html>".getBytes(UTF_8));

        io.amscotti.bravesearch.domain.error.UnreadableBodyException failure =
                assertThrows(io.amscotti.bravesearch.domain.error.UnreadableBodyException.class, () -> extractor.extract(garbage));

        assertTrue(failure.getMessage().contains("not readable"), failure::getMessage);
        assertFalse(
                failure.getMessage().contains("gateway"),
                "the rejection never quotes body text: " + failure.getMessage());
    }

    @Test
    void anEmptyBodyIsNotAReadableDocument() {
        assertThrows(
                io.amscotti.bravesearch.domain.error.UnreadableBodyException.class,
                () -> extractor.extract(new UpstreamPayload(new byte[0])));
    }

    private static UpstreamPayload body(String json) {
        return new UpstreamPayload(json.getBytes(UTF_8));
    }

    private static UpstreamPayload fixture(String name) {
        String resource = "/fixtures/brave/web/" + name;
        try (java.io.InputStream bytes =
                WebProjectionExtractorTest.class.getResourceAsStream(resource)) {
            if (bytes == null) {
                throw new IllegalStateException("missing fixture " + resource);
            }
            return new UpstreamPayload(bytes.readAllBytes());
        } catch (IOException missing) {
            throw new UncheckedIOException(missing);
        }
    }
}

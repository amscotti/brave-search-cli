package io.amscotti.bravesearch.adapter.cli.presentation.spellcheck;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.result.SpellcheckResults;
import org.junit.jupiter.api.Test;

/**
 * Tolerant enumeration of the spellcheck logical results from the lossless upstream
 * body: the shared array walk over the upstream top-level {@code results} path, with
 * every usable array element becoming one entry carrying its original zero-based
 * position and the spellcheck-corrected query — the single member the upstream
 * reference documents for a spellcheck result. An element without usable correction
 * text is skipped whole, leaving a position gap.
 */
final class SpellcheckProjectionExtractorTest {

    private final SpellcheckProjectionExtractor extractor = new SpellcheckProjectionExtractor(new JsonMappers());

    @Test
    void enumeratesCorrectionsWithOriginalPositions() {
        SpellcheckResults results = extractor.extract(
                body("{\"results\":[{\"query\":\"corrected query\"},{\"query\":\"also corrected\"}]}"));

        assertEquals(2, results.entries().size());
        assertEquals(0, results.entries().get(0).position());
        assertEquals("corrected query", results.entries().get(0).query());
        assertEquals(1, results.entries().get(1).position());
        assertEquals("also corrected", results.entries().get(1).query());
    }

    @Test
    void anElementWithoutUsableCorrectionTextIsSkippedWithItsPositionGapKept() {
        SpellcheckResults results = extractor.extract(body("{\"results\":[{\"query\":7},{\"query\":\"usable\"}]}"));

        assertEquals(1, results.entries().size());
        assertEquals(1, results.entries().getFirst().position(), "the position names the original upstream index");
    }

    @Test
    void aMissingOrNonArrayResultsMemberYieldsZeroCorrections() {
        assertEquals(0, extractor.extract(body("{\"query\":{\"original\":\"q\"}}")).entries().size());
        assertEquals(0, extractor.extract(body("{\"results\":{}}")).entries().size());
    }

    private static UpstreamPayload body(String json) {
        return new UpstreamPayload(json.getBytes(UTF_8));
    }
}

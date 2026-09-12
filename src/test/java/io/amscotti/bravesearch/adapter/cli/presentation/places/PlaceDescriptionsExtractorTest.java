package io.amscotti.bravesearch.adapter.cli.presentation.places;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.domain.error.UnreadableBodyException;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.result.OrderReconstructor;
import io.amscotti.bravesearch.domain.result.PlaceDescriptions;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tolerant enumeration of the AI-description entries from the lossless upstream body:
 * the {@code results} array is walked in order, every usable object element with a
 * textual {@code id} becomes one identified payload carrying its description text, and
 * everything else is deliberately ignored. Only a body that is not exactly one readable
 * JSON document fails, loudly and without quoting the body.
 */
final class PlaceDescriptionsExtractorTest {

    private final PlaceDescriptionsExtractor extractor = new PlaceDescriptionsExtractor(new JsonMappers());

    @Test
    void descriptionTextIsCarriedById() {
        String body = "{\"results\":[{\"id\":\"poi-a\",\"description\":\"An AI-generated summary.\"},"
                + "{\"id\":\"poi-b\"}]}";

        List<OrderReconstructor.Identified<PlaceDescriptions>> entries = extractor.extract(payload(body));

        assertEquals(2, entries.size());
        assertEquals("An AI-generated summary.", entries.getFirst().value().description());
        assertEquals(null, entries.get(1).value().description(), "an absent description is null");
    }

    @Test
    void elementsWithoutATextualIdAreSkippedAndNonTextualDescriptionsStayAbsent() {
        String body = "{\"results\":[{\"description\":\"orphan\"},{\"id\":\"poi-a\",\"description\":7}]}";

        List<OrderReconstructor.Identified<PlaceDescriptions>> entries = extractor.extract(payload(body));

        assertEquals(1, entries.size(), "an element without a textual id cannot be matched to its input position");
        assertEquals("poi-a", entries.getFirst().id());
        assertEquals(
                null, entries.getFirst().value().description(), "a non-textual description is omitted, never coerced");
    }

    @Test
    void aMissingResultsMemberDegradesToNoEntries() {
        assertEquals(0, extractor.extract(payload("{}")).size());
        assertEquals(0, extractor.extract(payload("{\"results\":[]}")).size());
    }

    @Test
    void aBodyThatIsNotOneReadableJsonDocumentFailsLoudly() {
        assertThrows(UnreadableBodyException.class, () -> extractor.extract(payload("<html>")));
    }

    private static UpstreamPayload payload(String body) {
        return new UpstreamPayload(body.getBytes(UTF_8));
    }
}

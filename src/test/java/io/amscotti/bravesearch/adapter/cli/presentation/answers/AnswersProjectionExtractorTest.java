package io.amscotti.bravesearch.adapter.cli.presentation.answers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.domain.error.UnreadableBodyException;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.result.AnswersContent;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;

/**
 * Tolerant enumeration of the blocking answer document from the lossless upstream body:
 * the answer text is the first choice's message content when textual, the citations are
 * the top-level {@code citations} array's object elements with their usable members, and
 * nothing else is modeled — unknown members never break extraction, and only a body that
 * is not one readable JSON document fails.
 */
final class AnswersProjectionExtractorTest {

    private final AnswersProjectionExtractor extractor = new AnswersProjectionExtractor(new JsonMappers());

    @Test
    void theAnswerTextIsTheFirstChoiceMessageContent() {
        AnswersContent content = extractor.extract(body("blocking-answer.json"));

        assertEquals("Brave Search is an independent index with its own crawler.", content.answer());
        assertTrue(content.citations().isEmpty(), "the documented blocking shape carries no citations array");
    }

    @Test
    void aCitationsArrayYieldsOneEntryPerObjectElementWithItsUsableMembers() {
        AnswersContent content = extractor.extract(body("blocking-answer-citations.json"));

        assertEquals(2, content.citations().size(), "non-object elements and object elements without usable members are skipped");
        AnswersContent.Citation first = content.citations().get(0);
        assertEquals(1, first.number());
        assertEquals("https://search.brave.com/", first.url());
        assertEquals("https://search.brave.com/favicon.ico", first.favicon());
        assertEquals("an independent index", first.snippet());
        assertEquals(0L, first.startIndex());
        assertEquals(12L, first.endIndex());
        AnswersContent.Citation second = content.citations().get(1);
        assertEquals(2, second.number());
        assertEquals("https://brave.com", second.url());
        assertNull(second.snippet());
        assertNull(second.startIndex());
    }

    @Test
    void missingOrNonTextualContentLeavesTheAnswerAbsentWithoutFailing() {
        assertEquals(null, extractor.extract(new UpstreamPayload("{}".getBytes())).answer());
        assertEquals(
                null,
                extractor.extract(new UpstreamPayload("{\"choices\":[]}".getBytes())).answer());
        assertEquals(
                null,
                extractor.extract(new UpstreamPayload(
                                "{\"choices\":[{\"message\":{\"content\":{\"nested\":true}}}]}".getBytes()))
                        .answer());
        assertTrue(extractor.extract(new UpstreamPayload("{\"choices\":[]}".getBytes()))
                .citations()
                .isEmpty());
    }

    @Test
    void aCitationsMemberThatIsNotAnArrayIsIgnoredEntirely() {
        AnswersContent content = extractor.extract(
                new UpstreamPayload("{\"choices\":[],\"citations\":\"unexpected\"}".getBytes()));

        assertTrue(content.citations().isEmpty());
    }

    @Test
    void aBodyThatIsNotOneReadableJsonDocumentFailsLoudlyWithoutQuotingIt() {
        org.junit.jupiter.api.Assertions.assertThrows(
                UnreadableBodyException.class,
                () -> extractor.extract(new UpstreamPayload("not json".getBytes())));
        org.junit.jupiter.api.Assertions.assertThrows(
                UnreadableBodyException.class, () -> extractor.extract(new UpstreamPayload(new byte[0])));
    }

    private static UpstreamPayload body(String name) {
        return new UpstreamPayload(fixture(name));
    }

    private static byte[] fixture(String name) {
        String resource = "/fixtures/brave/answers/" + name;
        try (InputStream bytes = AnswersProjectionExtractorTest.class.getResourceAsStream(resource)) {
            if (bytes == null) {
                throw new IllegalStateException("missing fixture " + resource);
            }
            return bytes.readAllBytes();
        } catch (IOException missing) {
            throw new UncheckedIOException(missing);
        }
    }
}

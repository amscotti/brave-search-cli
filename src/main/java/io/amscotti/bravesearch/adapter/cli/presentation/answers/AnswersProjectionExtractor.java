package io.amscotti.bravesearch.adapter.cli.presentation.answers;

import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.domain.error.UnreadableBodyException;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.result.AnswersContent;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Tolerant enumeration of the blocking answer document from the lossless upstream body.
 *
 * <p>The documented response is the OpenAI chat-completions shape: the answer text rides
 * {@code choices[0].message.content}, and usage arrives through the {@code X-Request-*}
 * response headers the shared transport already parses. The blocking form's own citation
 * carriage is undocumented — citations belong to the streaming family — so a top-level
 * {@code citations} array is read tolerantly when one arrives: every object element
 * yields one citation with its usable members, and everything else is ignored, because
 * unknown upstream fields must never break extraction.
 *
 * <p>Only a body that is not exactly one readable JSON document fails, loudly and without
 * quoting the body: that exchange is malformed, and the caller classifies it instead of
 * rendering an invented answer. Decimals parse through the shared presentation upstream
 * reader, so the exact-scale parse of the machine documents and this extraction can never
 * disagree.
 */
public final class AnswersProjectionExtractor {

    private final JsonMappers mappers;

    public AnswersProjectionExtractor(JsonMappers mappers) {
        this.mappers = mappers;
    }

    /** The answer content of {@code body}. */
    public AnswersContent extract(UpstreamPayload body) {
        JsonNode root = readRoot(body);
        return new AnswersContent(answerOf(root), citationsOf(root));
    }

    private static String answerOf(JsonNode root) {
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return null;
        }
        JsonNode content = choices.get(0).path("message").path("content");
        return content.isString() ? content.stringValue() : null;
    }

    private static List<AnswersContent.Citation> citationsOf(JsonNode root) {
        JsonNode citations = root.path("citations");
        if (!citations.isArray()) {
            return List.of();
        }
        List<AnswersContent.Citation> entries = new ArrayList<>(citations.size());
        for (JsonNode element : citations) {
            if (!element.isObject()) {
                continue;
            }
            JsonNode url = element.get("url");
            if (url == null || !url.isString()) {
                // a citation without its url names nothing a human or machine can follow
                continue;
            }
            entries.add(new AnswersContent.Citation(
                    narrow(integer(element.get("number"))),
                    url.stringValue(),
                    text(element.get("favicon")),
                    text(element.get("snippet")),
                    integer(element.get("start_index")),
                    integer(element.get("end_index"))));
        }
        return entries;
    }

    private static Integer narrow(Long value) {
        return value == null ? null : value.intValue();
    }

    /** The member's usable text, or null when the element carried no textual form of it. */
    private static String text(JsonNode member) {
        return member != null && member.isString() ? member.stringValue() : null;
    }

    /** The member's integral value, or null when the element carried no such number. */
    private static Long integer(JsonNode member) {
        return member != null && member.isIntegralNumber() ? member.longValue() : null;
    }

    private JsonNode readRoot(UpstreamPayload body) {
        try {
            JsonNode tree = mappers.upstreamReader().readTree(body.toByteArray());
            if (tree == null || tree.isMissingNode()) {
                throw new UnreadableBodyException("upstream body is not readable JSON: the body is empty");
            }
            return tree;
        } catch (JacksonException unreadable) {
            throw new UnreadableBodyException(
                    "upstream body is not readable JSON: " + unreadable.getClass().getSimpleName());
        }
    }
}

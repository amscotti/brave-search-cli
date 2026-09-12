package io.amscotti.bravesearch.adapter.cli.presentation.context;

import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.domain.error.UnreadableBodyException;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.result.ContextContent;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Tolerant enumeration of the LLM context document from the lossless upstream body.
 *
 * <p>The documented response shape carries {@code grounding.generic[]}, optional {@code
 * grounding.poi} and {@code grounding.map[]} local-recall members, and {@code
 * sources{url→meta}}. The generic array is the context payload: a textual element is one
 * passage as-is, and an object element contributes its {@code text} member when that member
 * is usable text — anything else degrades to a skipped element, because unknown and
 * malformed upstream fields must never break extraction. The source count is the size of
 * the {@code sources} object; the local-recall members never influence enumeration.
 *
 * <p>Only a body that is not exactly one readable JSON document fails, loudly and without
 * quoting the body: that exchange is malformed, and the caller classifies it instead of
 * rendering an invented context. Decimals parse through the shared presentation upstream
 * reader, so the exact-scale parse of the machine documents and this extraction can never
 * disagree.
 */
public final class ContextProjectionExtractor {

    private final JsonMappers mappers;

    public ContextProjectionExtractor(JsonMappers mappers) {
        this.mappers = mappers;
    }

    /** The context content of {@code body}. */
    public ContextContent extract(UpstreamPayload body) {
        JsonNode root = readRoot(body);
        List<ContextContent.Entry> entries = new ArrayList<>();
        JsonNode generic = root.path("grounding").path("generic");
        if (generic.isArray()) {
            for (int position = 0; position < generic.size(); position++) {
                JsonNode element = generic.get(position);
                String text = passageOf(element);
                if (text != null) {
                    entries.add(new ContextContent.Entry(position, text));
                }
            }
        }
        JsonNode sources = root.path("sources");
        return new ContextContent(entries, sources.isObject() ? sources.size() : 0);
    }

    /** The usable passage text of one generic element: itself when textual, else its text member. */
    private static String passageOf(JsonNode element) {
        if (element.isString()) {
            return element.stringValue();
        }
        if (element.isObject()) {
            JsonNode text = element.get("text");
            return text != null && text.isString() ? text.stringValue() : null;
        }
        return null;
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

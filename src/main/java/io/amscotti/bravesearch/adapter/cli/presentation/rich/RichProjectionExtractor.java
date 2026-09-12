package io.amscotti.bravesearch.adapter.cli.presentation.rich;

import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.domain.error.UnreadableBodyException;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.result.RichVerticals;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Tolerant enumeration of the rich response's vertical blocks from the lossless body:
 * every top-level member whose value is an array becomes one vertical — named by its own
 * member name, counted by its whole array size, positioned by its zero-based ordinal
 * among the vertical blocks in document order — and every object element of that array
 * yields one item slot carrying its usable textual members. The upstream response shape
 * is undocumented, so nothing else is modeled: non-array top-level members are never
 * verticals, non-object elements count as items without yielding members, and unknown
 * member names on items are ignored, because unknown upstream fields must never break
 * extraction.
 *
 * <p>Only a body that is not exactly one readable JSON document fails, loudly and
 * without quoting the body: that exchange is malformed, and the caller classifies it
 * instead of rendering an invented vertical list. Decimals parse through the shared
 * presentation upstream reader, so the exact-scale parse of the machine documents and
 * this extraction can never disagree.
 */
public final class RichProjectionExtractor {

    private final JsonMappers mappers;

    public RichProjectionExtractor(JsonMappers mappers) {
        this.mappers = mappers;
    }

    /** The vertical blocks of {@code body}, in document order. */
    public RichVerticals extract(UpstreamPayload body) {
        JsonNode root = readRoot(body);
        if (!root.isObject()) {
            return new RichVerticals(List.of());
        }
        List<RichVerticals.Vertical> verticals = new ArrayList<>();
        int position = 0;
        for (Map.Entry<String, JsonNode> field : root.properties()) {
            JsonNode member = field.getValue();
            if (!member.isArray()) {
                continue;
            }
            List<RichVerticals.Item> items = new ArrayList<>(member.size());
            for (JsonNode element : member) {
                if (element.isObject()) {
                    items.add(new RichVerticals.Item(
                            text(element.get("title")),
                            text(element.get("url")),
                            text(element.get("description")),
                            text(element.get("source"))));
                }
            }
            verticals.add(new RichVerticals.Vertical(position++, field.getKey(), member.size(), items));
        }
        return new RichVerticals(verticals);
    }

    /** The member's usable text, or null when the element carried no textual form of it. */
    private static String text(JsonNode member) {
        return member != null && member.isString() ? member.stringValue() : null;
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

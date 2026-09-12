package io.amscotti.bravesearch.adapter.cli.presentation.places;

import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.domain.error.UnreadableBodyException;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.result.OrderReconstructor;
import io.amscotti.bravesearch.domain.result.PlaceDetails;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Tolerant enumeration of the POI detail entries from the lossless upstream body of one
 * {@code /local/pois} chunk: the {@code results} array is walked in order, and every
 * usable object element with a textual {@code id} becomes one identified payload — the
 * id is the matching key the order reconstruction needs, because the upstream answers
 * each requested id with one entry. A skipped element is simply not enumerated; no
 * renumbering exists here because positions come from the caller's input, never from
 * this array. Everything else — a missing, null, or non-array {@code results} member, a
 * non-object element — degrades to omitted entries, because unknown and malformed
 * upstream fields must never break extraction.
 *
 * <p>Per entry, the payload carries the bounded textual inventory the listing renders:
 * the {@code title}, {@code url}, and {@code description}, the display address of the
 * {@code postal_address} block, the telephone and email of the {@code contact} block,
 * the {@code price_range}, the {@code timezone}, and the source url of the {@code
 * thumbnail} — the rich nested structures the endpoint returns stay with the lossless
 * machine documents inside {@code data.upstream}. Every other upstream member is
 * deliberately not carried. Only a body that is not exactly one readable JSON document
 * fails, loudly and without quoting the body: that exchange is malformed, and the caller
 * classifies it instead of rendering an invented result list. Decimals parse through
 * the shared presentation upstream reader, so the exact-scale parse of the machine
 * documents and this extraction can never disagree.
 */
public final class PlaceDetailsExtractor {

    private final JsonMappers mappers;

    /** @param mappers the shared presentation mappers whose upstream reader parses the body */
    public PlaceDetailsExtractor(JsonMappers mappers) {
        this.mappers = mappers;
    }

    /** The identified POI detail entries of {@code body}, in response order. */
    public List<OrderReconstructor.Identified<PlaceDetails>> extract(UpstreamPayload body) {
        JsonNode root = readRoot(body);
        JsonNode results = root.path("results");
        if (!results.isArray()) {
            return List.of();
        }
        List<OrderReconstructor.Identified<PlaceDetails>> entries = new ArrayList<>(results.size());
        for (JsonNode element : results) {
            if (!element.isObject()) {
                continue;
            }
            String id = text(element.get("id"));
            if (id == null) {
                continue;
            }
            entries.add(new OrderReconstructor.Identified<>(id, entryOf(element)));
        }
        return entries;
    }

    private static PlaceDetails entryOf(JsonNode element) {
        return new PlaceDetails(
                text(element.get("title")),
                text(element.get("url")),
                text(element.get("description")),
                text(element.path("postal_address").get("displayAddress")),
                text(element.path("contact").get("telephone")),
                text(element.path("contact").get("email")),
                text(element.get("price_range")),
                text(element.get("timezone")),
                text(element.path("thumbnail").get("src")));
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

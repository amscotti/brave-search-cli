package io.amscotti.bravesearch.adapter.cli.presentation.places;

import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.domain.error.UnreadableBodyException;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.result.OrderReconstructor;
import io.amscotti.bravesearch.domain.result.PlaceDescriptions;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Tolerant enumeration of the AI-description entries from the lossless upstream body of
 * one {@code /local/descriptions} chunk: the {@code results} array is walked in order,
 * and every usable object element with a textual {@code id} becomes one identified
 * payload carrying the element's description text — the id is the matching key the
 * order reconstruction needs, because the upstream answers each requested id with one
 * entry. Everything else — a missing, null, or non-array {@code results} member, a
 * non-object element, an element without a textual id — degrades to omitted entries,
 * because unknown and malformed upstream fields must never break extraction.
 *
 * <p>Only a body that is not exactly one readable JSON document fails, loudly and
 * without quoting the body: that exchange is malformed, and the caller classifies it
 * instead of rendering an invented result list. Decimals parse through the shared
 * presentation upstream reader, so the exact-scale parse of the machine documents and
 * this extraction can never disagree.
 */
public final class PlaceDescriptionsExtractor {

    private final JsonMappers mappers;

    /** @param mappers the shared presentation mappers whose upstream reader parses the body */
    public PlaceDescriptionsExtractor(JsonMappers mappers) {
        this.mappers = mappers;
    }

    /** The identified AI-description entries of {@code body}, in response order. */
    public List<OrderReconstructor.Identified<PlaceDescriptions>> extract(UpstreamPayload body) {
        JsonNode root = readRoot(body);
        JsonNode results = root.path("results");
        if (!results.isArray()) {
            return List.of();
        }
        List<OrderReconstructor.Identified<PlaceDescriptions>> entries = new ArrayList<>(results.size());
        for (JsonNode element : results) {
            if (!element.isObject()) {
                continue;
            }
            String id = text(element.get("id"));
            if (id == null) {
                continue;
            }
            entries.add(new OrderReconstructor.Identified<>(id, new PlaceDescriptions(text(element.get("description")))));
        }
        return entries;
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

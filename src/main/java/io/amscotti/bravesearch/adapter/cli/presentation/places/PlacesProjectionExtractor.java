package io.amscotti.bravesearch.adapter.cli.presentation.places;

import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.domain.error.UnreadableBodyException;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.result.PlaceResults;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Tolerant enumeration of the places logical results from the lossless upstream body:
 * every documented response bucket of {@link PlaceResults#BUCKETS} is walked in its
 * documented order, and every usable object element becomes one entry carrying its
 * bucket's wire word and its original zero-based position inside that bucket's array.
 * A skipped element leaves a position gap inside its bucket: the position always names
 * the original upstream index, never a renumbered one. Everything else — a missing,
 * null, or non-array bucket member, a non-object element — degrades to omitted
 * entries, because unknown and malformed upstream fields must never break extraction;
 * the {@code mixed} ordering hints and every unknown top-level member are deliberately
 * ignored, because enumeration follows the documented bucket order.
 *
 * <p>Per entry, the projection carries the opaque {@code id}, the {@code title}, the
 * {@code address}, the textual forms of the {@code rating} block's {@code
 * rating_value} and {@code rating_count} — numbers keep their exact textual form —
 * the {@code distance}, and the {@code phone} and {@code website} contact members;
 * every other upstream member is deliberately not carried, because the listing renders
 * only the members it can show. Only a body that is not exactly one readable JSON
 * document fails, loudly and without quoting the body: that exchange is malformed, and
 * the caller classifies it instead of rendering an invented result list. Decimals
 * parse through the shared presentation upstream reader, so the exact-scale parse of
 * the machine documents and this extraction can never disagree.
 */
public final class PlacesProjectionExtractor {

    private final JsonMappers mappers;

    /** @param mappers the shared presentation mappers whose upstream reader parses the body */
    public PlacesProjectionExtractor(JsonMappers mappers) {
        this.mappers = mappers;
    }

    /** The places logical results of {@code body}, enumerated across every documented bucket. */
    public PlaceResults extract(UpstreamPayload body) {
        JsonNode root = readRoot(body);
        List<PlaceResults.Entry> entries = new ArrayList<>();
        for (String bucket : PlaceResults.BUCKETS) {
            JsonNode array = root.path(bucket);
            if (!array.isArray()) {
                continue;
            }
            for (int position = 0; position < array.size(); position++) {
                JsonNode element = array.get(position);
                if (element.isObject()) {
                    entries.add(entryOf(bucket, position, element));
                }
            }
        }
        return new PlaceResults(entries);
    }

    private static PlaceResults.Entry entryOf(String bucket, int position, JsonNode element) {
        JsonNode rating = element.path("rating");
        return new PlaceResults.Entry(
                bucket,
                position,
                text(element.get("id")),
                text(element.get("title")),
                text(element.get("address")),
                scalarText(rating.get("rating_value")),
                scalarText(rating.get("rating_count")),
                scalarText(element.get("distance")),
                text(element.get("phone")),
                text(element.get("website")));
    }

    /** The member's usable text, or null when the element carried no textual form of it. */
    private static String text(JsonNode member) {
        return member != null && member.isString() ? member.stringValue() : null;
    }

    /**
     * The member's usable text or exact decimal form — a rating value, a rating count,
     * or a distance arrives as either a string or a number — or null when the element
     * carried neither.
     */
    private static String scalarText(JsonNode member) {
        if (member == null) {
            return null;
        }
        if (member.isString()) {
            return member.stringValue();
        }
        return member.isNumber() ? member.decimalValue().toPlainString() : null;
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

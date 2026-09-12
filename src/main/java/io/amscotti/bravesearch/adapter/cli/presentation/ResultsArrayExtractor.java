package io.amscotti.bravesearch.adapter.cli.presentation;

import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.domain.error.UnreadableBodyException;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * The shared enumeration machinery of the bucketed results extractors: reading the
 * lossless body into a tree, walking the endpoint's documented path to its results array,
 * and yielding one entry per usable object element carrying its original zero-based
 * position in that array. An endpoint whose upstream shape varies may add fallback
 * arrays, walked only while the documented path yields nothing. Everything else — a
 * missing path member, a non-object one, a non-array leaf, non-object array elements —
 * degrades to omitted entries or zero results, because unknown and malformed upstream
 * fields must never break extraction. A skipped element leaves a position gap: the
 * position always names the original upstream index, never a renumbered one.
 *
 * <p>Only a body that is not exactly one readable JSON document fails, loudly and without
 * quoting the body: that exchange is malformed, and the caller classifies it instead of
 * rendering an invented result list. Decimals parse through the shared presentation
 * upstream reader, so the exact-scale parse of the machine documents and this extraction
 * can never disagree.
 *
 * @param <E> the endpoint's logical result entry type
 */
public abstract class ResultsArrayExtractor<E> {

    private final JsonMappers mappers;

    private final String[] resultsArrayPath;

    /**
     * @param mappers the shared presentation mappers whose upstream reader parses the body
     * @param resultsArrayPath the member path from the body's root to the endpoint's
     *     results array, outermost member first
     */
    protected ResultsArrayExtractor(JsonMappers mappers, String... resultsArrayPath) {
        this.mappers = mappers;
        this.resultsArrayPath = resultsArrayPath.clone();
        if (resultsArrayPath.length == 0) {
            throw new IllegalArgumentException("resultsArrayPath must name at least one member");
        }
    }

    /** The logical result entries of {@code body}, in upstream order. */
    protected final List<E> extractEntries(UpstreamPayload body) {
        JsonNode root = readRoot(body);
        for (JsonNode array : candidateArrays(root)) {
            List<E> entries = entriesOf(array);
            if (!entries.isEmpty()) {
                return entries;
            }
        }
        return List.of();
    }

    /**
     * The results arrays to walk, most authoritative first: extraction returns the
     * first candidate that yields any entry, so the documented bucket always wins
     * over a fallback and two overlapping shapes never double-count.
     */
    protected List<JsonNode> candidateArrays(JsonNode root) {
        JsonNode array = root;
        for (String member : resultsArrayPath) {
            array = array.path(member);
        }
        return List.of(array);
    }

    private List<E> entriesOf(JsonNode array) {
        if (!array.isArray()) {
            return List.of();
        }
        List<E> entries = new ArrayList<>(array.size());
        for (int position = 0; position < array.size(); position++) {
            JsonNode element = array.get(position);
            if (element.isObject()) {
                E entry = entryOf(position, element);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        }
        return entries;
    }

    /**
     * The entry one usable object element yields, carrying its original position —
     * or null to veto an element the endpoint's shape rules out, which keeps its
     * position gap like any other skipped element.
     */
    protected abstract E entryOf(int position, JsonNode element);

    /** The member's usable text, or null when the element carried no textual form of it. */
    protected static String text(JsonNode member) {
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

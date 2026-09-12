package io.amscotti.bravesearch.domain.request;

import io.amscotti.bravesearch.domain.result.PagedSearch;
import java.util.List;
import java.util.Objects;

/**
 * One place-enrichment invocation: the endpoint it travels to — the POI detail or the
 * AI-description endpoint of the local-place family — and the one through two hundred
 * opaque ids it asks about.
 *
 * <p>The ids are opaque and ephemeral upstream tokens: they are carried verbatim, never
 * validated for internal format, and never normalized — duplicates and input order are
 * part of the invocation, because the CLI contract reconstructs the caller's input
 * order from what the endpoint returns. The bounds the contract fixes besides
 * emptiness are the invocation cap of {@value #MAX_IDS} ids and the chunk budget of
 * the shared sequential walk: an invocation may never compose more chunks than {@code
 * PagedSearch.MAX_PAGE}, so a cap raised past that budget is rejected here — as the
 * usage failure the command boundary reports — instead of overflowing the walk as an
 * internal error. The per-request bound of {@value #MAX_PER_REQUEST} belongs to the
 * chunk view below, never to the invocation itself.
 *
 * <p>The chunk view slices the input in order into consecutive upstream requests of at
 * most {@value #MAX_PER_REQUEST} ids: chunk one carries the first twenty input ids,
 * chunk two the next twenty, and so on, so a duplicate input id rides exactly the chunk
 * its input position earned. Two hundred ids — the cap — compose exactly ten chunks.
 */
public record PlaceEnrichmentRequest(Kind kind, List<String> ids) {

    /** The largest id count one upstream enrichment request documents. */
    public static final int MAX_PER_REQUEST = 20;

    /** The largest id count one invocation accepts. */
    public static final int MAX_IDS = 200;

    /** The enrichment endpoints of the local-place family, each naming its wire path. */
    public enum Kind {
        /** The POI detail endpoint, {@code /local/pois}. */
        DETAILS,

        /** The AI-description endpoint, {@code /local/descriptions}. */
        DESCRIPTIONS
    }

    public PlaceEnrichmentRequest {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(ids, "ids");
        ids.forEach(id -> Objects.requireNonNull(id, "id"));
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("an invocation carries at least one id");
        }
        if (ids.size() > MAX_IDS) {
            throw new IllegalArgumentException("an invocation carries at most " + MAX_IDS + " ids");
        }
        if ((ids.size() + MAX_PER_REQUEST - 1) / MAX_PER_REQUEST > PagedSearch.MAX_PAGE) {
            throw new IllegalArgumentException(
                    "an invocation composes at most " + PagedSearch.MAX_PAGE + " chunk requests");
        }
        ids = List.copyOf(ids);
    }

    /** The number of upstream requests this invocation chunks into. */
    public int chunkCount() {
        return (ids.size() + MAX_PER_REQUEST - 1) / MAX_PER_REQUEST;
    }

    /**
     * The input-order id slice of one chunk request.
     *
     * @param chunk the one-based chunk number within this invocation
     * @throws IllegalArgumentException when {@code chunk} is outside one through {@link
     *     #chunkCount()}
     */
    public List<String> idsOfChunk(int chunk) {
        if (chunk < 1 || chunk > chunkCount()) {
            throw new IllegalArgumentException("chunk must be between 1 and " + chunkCount() + ": " + chunk);
        }
        int from = (chunk - 1) * MAX_PER_REQUEST;
        int to = Math.min(from + MAX_PER_REQUEST, ids.size());
        return ids.subList(from, to);
    }
}

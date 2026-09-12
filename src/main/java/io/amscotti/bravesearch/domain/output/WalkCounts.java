package io.amscotti.bravesearch.domain.output;

import java.util.Objects;

/**
 * The count vocabulary of one sequential multi-request walk's documents: the machine
 * field names of the walk's request counts and the noun phrase of the counted human
 * diagnostic, so every output channel states how much of the walk completed in the
 * family's own words — the pagination family counts pages, the place-enrichment chunk
 * fan-out counts requests.
 *
 * <p>Contract vocabulary shared by the walk presenters of the CLI, like {@link
 * CommandOutputProfile} beside it: the buffered machine documents name the budget and
 * the completed-request count with these fields, and the counted diagnostic completes
 * its parenthetical with the noun ("{@code (1 of 3 requests completed)}").
 */
public record WalkCounts(String requestedField, String receivedField, String noun) {

    /** The pagination family's vocabulary: user-facing pages requested and received. */
    public static final WalkCounts PAGES = new WalkCounts("requested_pages", "received_pages", "requested pages");

    /** The chunk fan-out family's vocabulary: chunk requests requested and received. */
    public static final WalkCounts REQUESTS =
            new WalkCounts("requested_requests", "received_requests", "requests");

    public WalkCounts {
        Objects.requireNonNull(requestedField, "requestedField");
        Objects.requireNonNull(receivedField, "receivedField");
        Objects.requireNonNull(noun, "noun");
    }
}

package io.amscotti.bravesearch.domain.metadata;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Answers request, query, and token usage with decimal cost.
 *
 * <p>Every counter and cost is {@code null} when the upstream did not report it, so a
 * partially reported usage keeps exactly the fields that arrived. Token counts are split
 * into the billed input and output directions, and costs keep the per-component decimals
 * the upstream reported, parsed at their exact scale.
 *
 * <p>{@code unknownFields} preserves every unrecognized {@code X-Request-*} header verbatim
 * in first-seen order: the name exactly as observed, the value text unchanged, so machine
 * output can carry fields this version never anticipated. {@code notes} carries the
 * nonfatal parse observations (a malformed value, a repeated header) as diagnostic text
 * that names fields, never observed values.
 */
public record Usage(
        Long requests,
        Long queries,
        Long tokensIn,
        Long tokensOut,
        BigDecimal requestsCost,
        BigDecimal queriesCost,
        BigDecimal tokensInCost,
        BigDecimal tokensOutCost,
        BigDecimal totalCost,
        Map<String, String> unknownFields,
        List<String> notes) {

    public Usage {
        unknownFields =
                unknownFields == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(unknownFields));
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}

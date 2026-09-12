package io.amscotti.bravesearch.domain.result;

import java.util.List;
import java.util.Objects;

/**
 * The logical content of one blocking Answers exchange: the answer text and the citations
 * the response carried.
 *
 * <p>The answer is the first choice's message content when that member arrived as usable
 * text; it is null when the response offered none, because the machine projection omits
 * an absent answer rather than inventing one. A citation entry appears for every object
 * element of the response's top-level {@code citations} array, carrying exactly the
 * members it offered — the upstream contract documents the citation members for the
 * streaming family, so the blocking form is read tolerantly and every unknown member is
 * ignored. The lossless document stays in the upstream body.
 */
public record AnswersContent(String answer, List<Citation> citations) {

    public AnswersContent {
        citations = citations == null ? List.of() : List.copyOf(citations);
        citations.forEach(Objects::requireNonNull);
    }

    /** One citation of the answer, each member null when the response did not offer it. */
    public record Citation(
            Integer number,
            String url,
            String favicon,
            String snippet,
            Long startIndex,
            Long endIndex) {}
}

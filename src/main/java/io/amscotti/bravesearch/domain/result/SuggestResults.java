package io.amscotti.bravesearch.domain.result;

import java.util.List;
import java.util.Objects;

/**
 * The logical suggestions of one suggest exchange in presentation order: the entries of
 * the upstream top-level {@code results} array, each keeping its original zero-based
 * position so output modes can state where the suggestion came from.
 *
 * <p>{@link #BUCKET} is the stable bucket name of every entry. The entry's {@code query}
 * member is the suggested completion — the member the human listing numbers — while
 * {@code type} and the enriched {@code title}, {@code description}, and {@code img}
 * members arrive only in rich suggestions and stay {@code null} otherwise; an absent
 * member is omitted from every rendered form, never rendered as an empty placeholder.
 * The deprecated upstream {@code is_entity} flag is deliberately not carried, because
 * {@code type} is its documented replacement.
 */
public record SuggestResults(List<Entry> entries) {

    /** The bucket name of the suggest logical result list. */
    public static final String BUCKET = "suggest";

    public SuggestResults {
        entries = entries == null ? List.of() : List.copyOf(entries);
        entries.forEach(Objects::requireNonNull);
    }

    /**
     * One logical suggestion: its original zero-based position in the upstream results
     * array, the suggested completion, and the usable textual members a rich suggestion
     * carried.
     */
    public record Entry(int position, String query, String type, String title, String description, String img) {

        public Entry {
            if (position < 0) {
                throw new IllegalArgumentException("result position must not be negative: " + position);
            }
        }
    }
}

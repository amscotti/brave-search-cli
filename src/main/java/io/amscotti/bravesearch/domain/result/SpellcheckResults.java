package io.amscotti.bravesearch.domain.result;

import java.util.List;
import java.util.Objects;

/**
 * The logical corrections of one spellcheck exchange in presentation order: the entries
 * of the upstream top-level {@code results} array, each keeping its original zero-based
 * position so output modes can state where the correction came from.
 *
 * <p>{@link #BUCKET} is the stable bucket name of every entry. The entry's {@code query}
 * member is the spellcheck-corrected query — the only member the live reference
 * documents for a spellcheck result, and therefore the only member the entry carries.
 */
public record SpellcheckResults(List<Entry> entries) {

    /** The bucket name of the spellcheck logical result list. */
    public static final String BUCKET = "spellcheck";

    public SpellcheckResults {
        entries = entries == null ? List.of() : List.copyOf(entries);
        entries.forEach(Objects::requireNonNull);
    }

    /** One logical correction: its original zero-based position and the corrected query. */
    public record Entry(int position, String query) {

        public Entry {
            if (position < 0) {
                throw new IllegalArgumentException("result position must not be negative: " + position);
            }
        }
    }
}

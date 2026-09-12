package io.amscotti.bravesearch.domain.result;

import java.util.List;
import java.util.Objects;

/**
 * The logical content of one LLM context exchange: the usable passages of the upstream
 * {@code grounding.generic} array, each keeping its original zero-based position, and the
 * number of entries of the {@code sources} map.
 *
 * <p>{@link #BUCKET} is the stable bucket name of the context logical result — the whole
 * context document is one logical result, so the JSONL channel emits exactly one record of
 * this bucket per exchange. An entry appears only when its upstream element carried usable
 * text; the optional local-recall members ({@code grounding.poi}, {@code grounding.map})
 * never break enumeration, and the source count is the size of the {@code sources} object
 * when the body offered one.
 */
public record ContextContent(List<Entry> entries, int sourceCount) {

    /** The bucket name of the context logical result. */
    public static final String BUCKET = "context";

    public ContextContent {
        entries = entries == null ? List.of() : List.copyOf(entries);
        entries.forEach(Objects::requireNonNull);
        if (sourceCount < 0) {
            throw new IllegalArgumentException("source count must not be negative: " + sourceCount);
        }
    }

    /** The joined passage text of the whole context, LF-separated in presentation order. */
    public String joinedText() {
        return String.join("\n", entries.stream().map(Entry::text).toList());
    }

    /** One usable context passage at its original zero-based position in the generic array. */
    public record Entry(int position, String text) {

        public Entry {
            Objects.requireNonNull(text, "text");
            if (position < 0) {
                throw new IllegalArgumentException("passage position must not be negative: " + position);
            }
        }
    }
}

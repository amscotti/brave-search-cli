package io.amscotti.bravesearch.domain.request;

/**
 * The measurement system of distance-bearing results: {@code metric} or {@code imperial}.
 *
 * <p>The enum keeps the upstream value tokens as its {@link #wireName()} spellings.
 */
public enum Units {
    METRIC("metric"),
    IMPERIAL("imperial");

    private final String wireName;

    Units(String wireName) {
        this.wireName = wireName;
    }

    /** The endpoint's documented value token. */
    public String wireName() {
        return wireName;
    }
}

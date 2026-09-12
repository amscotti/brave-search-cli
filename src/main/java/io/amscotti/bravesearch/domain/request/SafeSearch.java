package io.amscotti.bravesearch.domain.request;

import io.amscotti.bravesearch.domain.error.UsageValidationError;

/**
 * The SafeSearch level an endpoint accepts: {@code off}, {@code moderate}, or {@code strict}.
 *
 * <p>The enum keeps the upstream value tokens as its {@link #wireName()} spellings, and
 * parsing accepts exactly those tokens — case-sensitive, no surrounding whitespace — so an
 * invalid level is a typed usage failure instead of a silently coerced default.
 */
public enum SafeSearch {
    OFF("off"),
    MODERATE("moderate"),
    STRICT("strict");

    private final String wireName;

    SafeSearch(String wireName) {
        this.wireName = wireName;
    }

    /** The endpoint's documented value token. */
    public String wireName() {
        return wireName;
    }

    /**
     * Parses one documented token spelling.
     *
     * @throws UsageValidationError when the spelling matches no documented token
     */
    public static SafeSearch parse(String raw) {
        for (SafeSearch level : values()) {
            if (level.wireName.equals(raw)) {
                return level;
            }
        }
        throw new UsageValidationError("safe-search must be off, moderate, or strict");
    }
}

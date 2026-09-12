package io.amscotti.bravesearch.domain.request;

import io.amscotti.bravesearch.domain.error.UsageValidationError;

/**
 * The context threshold mode the LLM context endpoint documents: {@code strict}, {@code
 * balanced}, {@code lenient}, or {@code disabled}. An absent value is the API-calibrated
 * default, so no enum constant invents one — an unsupplied threshold is simply omitted from
 * the wire.
 */
public enum ContextThreshold {
    STRICT("strict"),
    BALANCED("balanced"),
    LENIENT("lenient"),
    DISABLED("disabled");

    private final String wireName;

    ContextThreshold(String wireName) {
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
    public static ContextThreshold parse(String raw) {
        for (ContextThreshold mode : values()) {
            if (mode.wireName.equals(raw)) {
                return mode;
            }
        }
        throw new UsageValidationError("threshold must be strict, balanced, lenient, or disabled");
    }
}

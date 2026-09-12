package io.amscotti.bravesearch.domain.output;

import java.util.Locale;
import java.util.Optional;

/**
 * The output channel a CLI invocation selects with {@code --output}.
 *
 * <p>The wire grammar is exactly the four lowercase words {@code human}, {@code json},
 * {@code jsonl}, {@code raw}; every other spelling, including any uppercase variant, is a
 * usage error. Shared by option parsing and the presentation layer because both must agree
 * on the same channel identity.
 */
public enum OutputMode {
    HUMAN("human"),
    JSON("json"),
    JSONL("jsonl"),
    RAW("raw");

    private final String wireName;

    OutputMode(String wireName) {
        this.wireName = wireName;
    }

    /** The exact {@code --output} word of this mode. */
    public String wireName() {
        return wireName;
    }

    /**
     * The mode of {@code value} when it is exactly one of the four lowercase wire words,
     * otherwise empty; matching is case-sensitive by design so no spelling quietly aliases a
     * channel.
     */
    public static Optional<OutputMode> fromWireName(String value) {
        for (OutputMode mode : values()) {
            if (mode.wireName.equals(value)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }

    /** The human-readable name, matching the wire word. */
    @Override
    public String toString() {
        return wireName.toLowerCase(Locale.ROOT);
    }
}

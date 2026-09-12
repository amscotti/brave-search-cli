package io.amscotti.bravesearch.domain.request;

import io.amscotti.bravesearch.domain.error.UsageValidationError;

/**
 * The local-recall tri-state of the LLM context endpoint's {@code --local} option: {@code
 * auto} defers to the endpoint's own detection, {@code on} and {@code off} pin it. Auto is
 * the omitted wire state — {@link #enableLocal()} answers null for it, exactly like an
 * unsupplied flag, because Brave documents {@code null} as auto-detection from the presence
 * of location headers.
 */
public enum LocalRecall {
    AUTO("auto"),
    ON("on"),
    OFF("off");

    private final String token;

    LocalRecall(String token) {
        this.token = token;
    }

    /** The option's documented token spelling. */
    public String token() {
        return token;
    }

    /**
     * The {@code enable_local} wire value: null omits the field so the endpoint
     * auto-detects; on and off pin their boolean.
     */
    public Boolean enableLocal() {
        return this == AUTO ? null : this == ON;
    }

    /**
     * Parses one documented token spelling.
     *
     * @throws UsageValidationError when the spelling matches no documented token
     */
    public static LocalRecall parse(String raw) {
        for (LocalRecall mode : values()) {
            if (mode.token.equals(raw)) {
                return mode;
            }
        }
        throw new UsageValidationError("local must be auto, on, or off");
    }
}

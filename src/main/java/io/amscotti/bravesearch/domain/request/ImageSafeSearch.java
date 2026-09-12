package io.amscotti.bravesearch.domain.request;

import io.amscotti.bravesearch.domain.error.UsageValidationError;

/**
 * The SafeSearch levels the images endpoint documents: exactly {@code off} and {@code
 * strict}. The images family has no {@code moderate} level — a domain-level restriction
 * this type owns — so the shared three-level {@link SafeSearch} grammar is narrowed here
 * and the narrowing is a typed usage failure, never a silent coercion toward a nearby
 * level.
 */
public enum ImageSafeSearch {
    OFF("off"),
    STRICT("strict");

    private final String wireName;

    ImageSafeSearch(String wireName) {
        this.wireName = wireName;
    }

    /** The endpoint's documented value token. */
    public String wireName() {
        return wireName;
    }

    /**
     * Narrows a parsed shared level onto the images pair.
     *
     * @throws UsageValidationError when {@code level} is {@code moderate}, because the
     *     images endpoint documents no such level
     */
    public static ImageSafeSearch of(SafeSearch level) {
        return switch (level) {
            case OFF -> OFF;
            case STRICT -> STRICT;
            case MODERATE -> throw new UsageValidationError("safe-search must be off or strict for images");
        };
    }
}

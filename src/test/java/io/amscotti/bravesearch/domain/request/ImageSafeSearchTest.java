package io.amscotti.bravesearch.domain.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.UsageValidationError;
import org.junit.jupiter.api.Test;

/**
 * The images endpoint's own SafeSearch levels: exactly {@code off} and {@code strict},
 * mapped from the shared three-level enum, with {@code moderate} — which the images
 * endpoint does not document — rejected as a typed usage failure and the wire tokens
 * kept verbatim.
 */
final class ImageSafeSearchTest {

    @Test
    void theTwoDocumentedLevelsKeepTheirWireTokens() {
        assertEquals("off", ImageSafeSearch.OFF.wireName());
        assertEquals("strict", ImageSafeSearch.STRICT.wireName());
    }

    @Test
    void theSharedOffAndStrictLevelsMapOntoTheirImagesCounterparts() {
        assertSame(ImageSafeSearch.OFF, ImageSafeSearch.of(SafeSearch.OFF));
        assertSame(ImageSafeSearch.STRICT, ImageSafeSearch.of(SafeSearch.STRICT));
    }

    @Test
    void moderateIsNotADocumentedImagesLevelAndFailsAsUsage() {
        UsageValidationError rejected = assertThrows(UsageValidationError.class, () -> ImageSafeSearch.of(SafeSearch.MODERATE));

        assertEquals(FailureKind.USAGE, rejected.kind());
        assertEquals("safe-search must be off or strict for images", rejected.getMessage());
    }
}

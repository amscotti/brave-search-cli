package io.amscotti.bravesearch.domain.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.amscotti.bravesearch.domain.error.UsageValidationError;
import org.junit.jupiter.api.Test;

/**
 * Validation of the rich callback request's single member: the checked-in upstream
 * contract documents no format rules for a callback key — the endpoint's own reference
 * page does not exist — so the key is opaque. The only boundary is that it carries at
 * least one non-whitespace character; every spelling, including option-like and
 * reserved-character-laden ones, travels verbatim, because percent-encoding is the
 * endpoint assembly's concern, never the domain's.
 */
final class RichRequestTest {

    @Test
    void acceptsAnyNonblankOpaqueKeyVerbatim() {
        for (String key : new String[] {"cb-7f3a2b", "-opaque-key", "--count", "cb/3f9d2A==&q=1", "caf\u00e9 \u2603 key"}) {
            assertEquals(key, new RichRequest(key).callbackKey(), "the key must travel exactly as given: " + key);
        }
    }

    @Test
    void rejectsTheBlankKeyAsAUsageError() {
        for (String blank : new String[] {"", " ", "\t", " \n "}) {
            UsageValidationError rejected =
                    assertThrows(UsageValidationError.class, () -> new RichRequest(blank), "blank must be refused: " + blank);
            assertEquals("callback key must not be blank", rejected.getMessage());
        }
    }

    @Test
    void rejectsAMissingKey() {
        assertThrows(NullPointerException.class, () -> new RichRequest(null));
    }
}

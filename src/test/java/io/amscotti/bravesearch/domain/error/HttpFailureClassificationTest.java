package io.amscotti.bravesearch.domain.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Classification of an upstream HTTP failure into its failure kind: the status decides, except
 * that the one documented auth-bearing status is upgraded by a structured auth code.
 */
final class HttpFailureClassificationTest {

    record StatusCase(int status, FailureKind structured, FailureKind expected) {}

    @Test
    void statusAloneClassifiesUpstreamFailures() {
        List<StatusCase> cases =
                List.of(
                        new StatusCase(300, null, FailureKind.UPSTREAM),
                        new StatusCase(301, null, FailureKind.UPSTREAM),
                        new StatusCase(399, null, FailureKind.UPSTREAM),
                        new StatusCase(400, null, FailureKind.UPSTREAM),
                        new StatusCase(402, null, FailureKind.UPSTREAM),
                        new StatusCase(404, null, FailureKind.UPSTREAM),
                        new StatusCase(408, null, FailureKind.UPSTREAM),
                        new StatusCase(500, null, FailureKind.UPSTREAM),
                        new StatusCase(503, null, FailureKind.UPSTREAM),
                        new StatusCase(401, null, FailureKind.AUTHENTICATION),
                        new StatusCase(403, null, FailureKind.AUTHENTICATION),
                        new StatusCase(429, null, FailureKind.RATE_LIMITED));
        for (StatusCase statusCase : cases) {
            assertEquals(
                    statusCase.expected(),
                    HttpFailureClassification.classify(statusCase.status(), statusCase.structured()),
                    () -> "wrong kind for status " + statusCase.status());
        }
    }

    @Test
    void authBearingStructuredCodeUpgradesOnlyTheDocumentedStatus() {
        assertEquals(FailureKind.AUTHENTICATION, HttpFailureClassification.classify(422, FailureKind.AUTHENTICATION));
        assertEquals(FailureKind.UPSTREAM, HttpFailureClassification.classify(422, FailureKind.RATE_LIMITED));
        assertEquals(FailureKind.UPSTREAM, HttpFailureClassification.classify(422, FailureKind.UPSTREAM));
        assertEquals(FailureKind.UPSTREAM, HttpFailureClassification.classify(422, null));
    }

    @Test
    void statusClassificationWinsOverStructuredCodes() {
        assertEquals(FailureKind.UPSTREAM, HttpFailureClassification.classify(500, FailureKind.AUTHENTICATION));
        assertEquals(FailureKind.RATE_LIMITED, HttpFailureClassification.classify(429, FailureKind.AUTHENTICATION));
        assertEquals(FailureKind.AUTHENTICATION, HttpFailureClassification.classify(401, FailureKind.UPSTREAM));
        assertEquals(FailureKind.AUTHENTICATION, HttpFailureClassification.classify(403, FailureKind.RATE_LIMITED));
    }

    @Test
    void nonFailureStatusesAreRefused() {
        for (int status : List.of(200, 204, 299, 600, -1)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> HttpFailureClassification.classify(status, null),
                    () -> "classification must not apply to status " + status);
        }
    }
}

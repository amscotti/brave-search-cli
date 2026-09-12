package io.amscotti.bravesearch.adapter.bravehttp.live;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.amscotti.bravesearch.application.exchange.BraveHttpResponse;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.error.UpstreamError;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import org.junit.jupiter.api.Test;

/**
 * The hermetic twin of the live flat-probe observation: the reduction that turns one observed
 * flat-search-control exchange into the fixed record vocabulary — accepted,
 * rejected-unknown-or-invalid-parameter, rejected-other — and the observed status, pinned
 * here against constructed outcomes so the vocabulary and its evidence rules stay exact even
 * when no entitled key is available to run the live probe itself. No test here performs a
 * network exchange; the reduced evidence text never surfaces anywhere.
 */
final class LiveAnswersFlatProbeReductionTest {

    @Test
    void aCompletedExchangeReducesToAcceptedWithItsStatus() {
        Outcome<BraveHttpResponse> outcome =
                new Outcome.Success<>(responseOf(200, "{\"choices\":[]}"));

        assertEquals("accepted", LiveAnswersTest.classify(outcome), "a completed exchange is acceptance");
        assertEquals(200, LiveAnswersTest.observedStatus(outcome), "the observed status is the exchange's own");
    }

    @Test
    void evidenceMentioningAnUnknownSpellingReducesToTheParameterRejection() {
        assertEquals(
                "rejected-unknown-or-invalid-parameter",
                LiveAnswersTest.classify(failureOf(400, "bad_request", "{\"error\":{\"detail\":\"Unknown field\"}}")),
                "an unknown spelling alone is the parameter-rejection class");
        assertEquals(
                "rejected-unknown-or-invalid-parameter",
                LiveAnswersTest.classify(
                        failureOf(400, "invalid_parameter", "{\"error\":{\"detail\":\"nope\"}}")),
                "an invalid-parameter pair — code or body, either side — is the same class");
    }

    @Test
    void evidenceWithoutTheParameterPairStaysRejectedOther() {
        assertEquals(
                "rejected-other",
                LiveAnswersTest.classify(failureOf(400, "bad_request", "{\"error\":{\"detail\":\"invalid request\"}}")),
                "invalid without parameter never reads as a parameter rejection");
        assertEquals(
                "rejected-other",
                LiveAnswersTest.classify(failureOf(403, "forbidden", "{\"error\":{}}")),
                "an unrelated rejection keeps the generic class");
        assertEquals(
                "rejected-other",
                LiveAnswersTest.classify(new Outcome.Failure<>(
                        FailureKind.UPSTREAM, "upstream exchange failed with status 400", null, null, 400)),
                "a failure without upstream evidence cannot name a parameter and stays generic");
    }

    @Test
    void anExchangeThatNeverCompletedObservablyFailsLoudly() {
        Outcome<BraveHttpResponse> unobservable = new Outcome.Failure<>(
                FailureKind.TRANSPORT, "the exchange did not complete", null, null, 0);

        AssertionError loud = assertThrows(
                AssertionError.class, () -> LiveAnswersTest.classify(unobservable));

        assertEquals(
                "the flat probe exchange did not complete observably: kind=TRANSPORT", loud.getMessage());
    }

    @Test
    void theObservedStatusOfAFailureIsTheHttpStatusItCarries() {
        assertEquals(
                422,
                LiveAnswersTest.observedStatus(failureOf(422, "unprocessable", "{}")),
                "a failing exchange reports the status it observed, never a guess");
    }

    private static BraveHttpResponse responseOf(int status, String body) {
        return new BraveHttpResponse(status, new UpstreamPayload(body.getBytes(UTF_8)));
    }

    private static Outcome.Failure<BraveHttpResponse> failureOf(int status, String code, String body) {
        return new Outcome.Failure<>(
                FailureKind.UPSTREAM,
                "upstream exchange failed with status " + status,
                new UpstreamError(code, null, new UpstreamPayload(body.getBytes(UTF_8))),
                null,
                status);
    }
}

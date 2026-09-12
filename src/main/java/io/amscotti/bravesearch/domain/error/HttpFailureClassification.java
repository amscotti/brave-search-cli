package io.amscotti.bravesearch.domain.error;

/**
 * Classification of a failed upstream HTTP exchange into its failure kind.
 *
 * <p>The HTTP status decides, with one documented exception: a structured auth,
 * entitlement, or payment error code upgrades the auth-bearing status 422 to
 * {@link FailureKind#AUTHENTICATION}. A structured code never overrides any other status —
 * a structured authentication code arriving with status 500 classifies as
 * {@link FailureKind#UPSTREAM}, because the status is the observed fact and the body is an
 * interpretation.
 *
 * <p>A pure function over observed facts, so it lives with the failure vocabulary it produces:
 * every transport adapter that reads a failed exchange classifies here, and the CLI exit
 * mapping consumes the resulting kind.
 */
public final class HttpFailureClassification {

    private HttpFailureClassification() {}

    /**
     * The failure kind of an upstream response with {@code httpStatus} and, when a structured
     * error body was readable, its suggested {@code structuredKind} (otherwise {@code null}).
     *
     * @throws IllegalArgumentException for statuses outside the failed range 300-599, because
     *     classification applies only to failed exchanges
     */
    public static FailureKind classify(int httpStatus, FailureKind structuredKind) {
        if (httpStatus < 300 || httpStatus > 599) {
            throw new IllegalArgumentException(
                    "upstream classification applies only to failed statuses 300-599: " + httpStatus);
        }
        if (httpStatus == 401 || httpStatus == 403) {
            return FailureKind.AUTHENTICATION;
        }
        if (httpStatus == 429) {
            return FailureKind.RATE_LIMITED;
        }
        if (httpStatus == 422 && structuredKind == FailureKind.AUTHENTICATION) {
            return FailureKind.AUTHENTICATION;
        }
        return FailureKind.UPSTREAM;
    }
}

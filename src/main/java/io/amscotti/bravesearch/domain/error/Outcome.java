package io.amscotti.bravesearch.domain.error;

import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;

/** Sealed result of an exchange: either a value or an expected, redacted failure. */
public sealed interface Outcome<T> {

    /** Successful exchange carrying the returned value. */
    record Success<T>(T value) implements Outcome<T> {}

    /**
     * Expected failure carrying its category, a redacted diagnostic, and — when a bounded
     * upstream error body was available — the structured facts of that body, plus the
     * rate-limit snapshot observed on the failing exchange's response headers.
     *
     * <p>The diagnostic is the only human-facing text and is redacted by construction; the
     * upstream details ride along for the machine surfaces and are {@code null} whenever no
     * bounded error body existed. {@code rateLimits} is {@code null} whenever the failing
     * exchange offered no header observation — a transport-local break never has one — and an
     * empty snapshot (never {@code null}) whenever the headers carried no rate-limit
     * information, so a rate-limited 429 can explain itself with the windows it observed while
     * every earlier failure shape is unchanged. {@code httpStatus} is the observed upstream
     * status of the failing exchange and {@code 0} when none was observed, because the
     * machine failure documents report the status they actually saw, never a guess.
     */
    record Failure<T>(
            FailureKind kind,
            String diagnostic,
            UpstreamError upstream,
            RateLimitSnapshot rateLimits,
            int httpStatus)
            implements Outcome<T> {

        /** A failure with no upstream detail, identical in shape to every transport-local break. */
        public Failure(FailureKind kind, String diagnostic) {
            this(kind, diagnostic, null, null, 0);
        }

        /** A failure with upstream detail but no header observation. */
        public Failure(FailureKind kind, String diagnostic, UpstreamError upstream) {
            this(kind, diagnostic, upstream, null, 0);
        }

        /** A failure with upstream detail and a header observation but no observed status. */
        public Failure(FailureKind kind, String diagnostic, UpstreamError upstream, RateLimitSnapshot rateLimits) {
            this(kind, diagnostic, upstream, rateLimits, 0);
        }
    }
}

package io.amscotti.bravesearch.domain.error;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A failure is independent of the success type it is typed under: a layer boundary a failure
 * crosses reads the same instance under its own outcome type instead of copying the members.
 */
final class OutcomeFailureTest {

    @Test
    void aFailureWidensToAnotherSuccessTypeAsTheSameInstanceWithEveryMemberIntact() {
        UpstreamError upstream = new UpstreamError("TooManyRequests", null, new UpstreamPayload("{}".getBytes(UTF_8)));
        RateLimitSnapshot windows = new RateLimitSnapshot(List.of(), List.of(), Instant.EPOCH);
        Outcome.Failure<String> transportLevel =
                new Outcome.Failure<>(FailureKind.RATE_LIMITED, "rate limited", upstream, windows, 429);

        Outcome.Failure<Integer> endpointLevel = transportLevel.widen();

        assertSame(transportLevel, endpointLevel, "widening is a view, never a copy");
        assertEquals(FailureKind.RATE_LIMITED, endpointLevel.kind());
        assertEquals("rate limited", endpointLevel.diagnostic());
        assertSame(upstream, endpointLevel.upstream());
        assertSame(windows, endpointLevel.rateLimits());
        assertEquals(429, endpointLevel.httpStatus());
    }

    @Test
    void aWidenedFailureIsTheFailureOfItsNewOutcomeType() {
        Outcome<String> origin = new Outcome.Failure<>(FailureKind.TRANSPORT, "broke", null, null, 0);

        Outcome<Integer> crossed = switch (origin) {
            case Outcome.Success<String> ignored -> throw new AssertionError("nothing succeeded");
            case Outcome.Failure<String> failure -> failure.widen();
        };

        assertTrue(
                crossed instanceof Outcome.Failure<Integer> crossedFailure && crossedFailure.kind() == FailureKind.TRANSPORT,
                "the widened failure satisfies the sealed outcome of its new success type");
    }
}

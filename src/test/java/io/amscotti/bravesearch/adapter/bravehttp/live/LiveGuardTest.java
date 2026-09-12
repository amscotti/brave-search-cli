package io.amscotti.bravesearch.adapter.bravehttp.live;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.error.UpstreamError;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

/**
 * Hermetic behavior of the live suite's shared guard: the skip decision follows the canonical
 * credential precedence, a present-but-invalid credential fails loudly with redacted text,
 * observation lines can never carry credential material, the class-level exchange lock
 * really serializes holders, the entitlement skip matches exactly the documented
 * plan-absence failure shape shared by every entitlement-gated probe, and the unserved
 * version pin matches exactly the typed upstream not-found answer. No test here performs a
 * network exchange.
 */
final class LiveGuardTest {

    @Test
    void guardSkipsWithTheFixedMessageWhenNoSourceProvidesACredential() {
        LiveGuard guard = new LiveGuard(lookup -> null, () -> Optional.empty());
        assertNull(guard.credentialOrNull(), "every source missing resolves to no credential");
        TestAbortedException aborted =
                assertThrows(TestAbortedException.class, guard::requirePresent);
        assertTrue(
                aborted.getMessage().endsWith(LiveGuard.SKIP_MESSAGE),
                "the skip reason is the fixed message; Jupiter may only prefix its standard wording");
        assertEquals("live tests require BRAVE_API_KEY", LiveGuard.SKIP_MESSAGE);
    }

    @Test
    void guardResolvesTheCanonicalVariableBeforeTheAlias() {
        LiveGuard canonical = new LiveGuard(
                variable -> "BRAVE_API_KEY".equals(variable) ? "canonical-live-token" : null,
                () -> Optional.empty());
        assertArrayEquals(
                "canonical-live-token".getBytes(UTF_8),
                canonical.credentialOrNull().tokenBytes(),
                "the canonical environment variable wins the precedence");

        LiveGuard aliasOnly = new LiveGuard(
                variable -> "BRAVE_SEARCH_API_KEY".equals(variable) ? "alias-live-token" : null,
                () -> Optional.empty());
        assertArrayEquals(
                "alias-live-token".getBytes(UTF_8),
                aliasOnly.credentialOrNull().tokenBytes(),
                "the compatibility alias serves when the canonical variable is absent");
    }

    @Test
    void guardFailsLoudlyAndRedactedOnAnInvalidPresentCredential() {
        String invalid = "live token with a newline\n";
        LiveGuard guard = new LiveGuard(
                variable -> "BRAVE_API_KEY".equals(variable) ? invalid : null, () -> Optional.empty());
        IllegalStateException failed = assertThrows(IllegalStateException.class, guard::credentialOrNull);
        assertTrue(
                !failed.getMessage().contains(invalid),
                "the invalid-credential failure must never quote the offered value");
    }

    @Test
    void guardMemoizesTheResolvedCredentialAcrossExchanges() {
        LiveGuard guard = new LiveGuard(
                variable -> "BRAVE_API_KEY".equals(variable) ? "stable-live-token" : null,
                () -> Optional.empty());
        Credential first = guard.credentialOrNull();
        Credential second = guard.credentialOrNull();
        assertEquals(first, second, "one resolution serves every exchange of the run");
    }

    @Test
    void redactionRejectsAnyObservationCarryingCredentialMaterial() {
        LiveGuard guard = new LiveGuard(
                variable -> "BRAVE_API_KEY".equals(variable) ? "live-sentinel-token" : null,
                () -> Optional.empty());
        guard.credentialOrNull();
        AssertionError rejected = assertThrows(
                AssertionError.class, () -> guard.boundedObservation("observed live-sentinel-token on the wire"));
        assertEquals("a live observation line carried credential material", rejected.getMessage());
        assertEquals("clean observation", guard.boundedObservation("clean observation"));
    }

    @Test
    void redactionBoundsEveryObservationLine() {
        LiveGuard guard = new LiveGuard(lookup -> null, () -> Optional.empty());
        String bounded = guard.boundedObservation("x".repeat(500));
        assertTrue(bounded.length() <= 131, "an observation line never exceeds its bounded form");
        assertTrue(bounded.endsWith("..."), "a truncated observation line marks its cut");
    }

    @Test
    void exchangeLockHoldsAtMostOneHolderAtATime() throws InterruptedException {
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Runnable holder = () -> {
                synchronized (LiveGuard.EXCHANGE_LOCK) {
                    int now = inside.incrementAndGet();
                    peak.accumulateAndGet(now, Math::max);
                    inside.decrementAndGet();
                }
                done.countDown();
            };
            pool.execute(holder);
            pool.execute(holder);
            assertTrue(done.await(5, TimeUnit.SECONDS), "both lock holders must finish");
            assertEquals(1, peak.get(), "the class-level lock never admits two concurrent holders");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void planEntitlementSkipMatchesExactlyTheDocumentedOptionAbsence() {
        Outcome.Failure<Object> entitlementAbsent = failedUpstream(400, "OPTION_NOT_IN_PLAN");
        assertTrue(
                LiveGuard.planOptionAbsent(entitlementAbsent),
                "the documented 400 with upstream code OPTION_NOT_IN_PLAN is the entitlement absence");
        assertEquals(
                "place search is not included in this plan", LiveGuard.PLACE_SEARCH_NOT_IN_PLAN_MESSAGE);
        assertEquals(
                "llm context is not included in this plan", LiveGuard.LLM_CONTEXT_NOT_IN_PLAN_MESSAGE);
        assertEquals(
                "answers is not included in this plan — the nested web_search_options probe requires an"
                        + " entitled key",
                LiveGuard.ANSWERS_NOT_IN_PLAN_MESSAGE);
    }

    @Test
    void anyOtherPlanFailureStaysLoud() {
        assertFalse(
                LiveGuard.planOptionAbsent(failedUpstream(400, "VALIDATION_FAILED")),
                "a 400 with any other upstream code is a real failure, never an entitlement skip");
        assertFalse(
                LiveGuard.planOptionAbsent(
                        new Outcome.Failure<>(
                                FailureKind.UPSTREAM, "upstream exchange failed with status 400", null, null, 400)),
                "a 400 without structured upstream detail fails loudly");
        assertFalse(
                LiveGuard.planOptionAbsent(failedUpstream(403, "OPTION_NOT_IN_PLAN")),
                "the entitlement code on a different status is not the plan-absence shape; the status governs");
        assertFalse(
                LiveGuard.planOptionAbsent(failedUpstream(429, "OPTION_NOT_IN_PLAN")),
                "a rate-limited exchange is pacing territory, never an entitlement skip");
    }

    @Test
    void unservedVersionPinMatchesTheTypedUpstreamNotFoundAnswer() {
        assertTrue(
                LiveGuard.apiVersionNotFound(failedUpstream(404, "API_VERSION_NOT_FOUND")),
                "a 4xx answering upstream code API_VERSION_NOT_FOUND proves the pin reached the server"
                        + " and was evaluated");
        assertEquals("API_VERSION_NOT_FOUND", LiveGuard.API_VERSION_NOT_FOUND_CODE);
    }

    @Test
    void anyOtherVersionedExchangeFailureStaysLoud() {
        assertFalse(
                LiveGuard.apiVersionNotFound(failedUpstream(404, "OPTION_NOT_IN_PLAN")),
                "a 4xx naming a different upstream code says nothing about the version pin");
        assertFalse(
                LiveGuard.apiVersionNotFound(
                        new Outcome.Failure<>(
                                FailureKind.UPSTREAM, "upstream exchange failed with status 404", null, null, 404)),
                "a 4xx without structured upstream detail is no typed version verdict");
        assertFalse(
                LiveGuard.apiVersionNotFound(failedUpstream(500, "API_VERSION_NOT_FOUND")),
                "the typed code outside the 4xx range is a server-side break, not a pin evaluation");
    }

    private static Outcome.Failure<Object> failedUpstream(int status, String upstreamCode) {
        return new Outcome.Failure<>(
                FailureKind.UPSTREAM,
                "upstream exchange failed with status " + status,
                new UpstreamError(upstreamCode, null, new UpstreamPayload(new byte[0])),
                null,
                status);
    }
}

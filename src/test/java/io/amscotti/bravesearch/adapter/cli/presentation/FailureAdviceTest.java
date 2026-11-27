package io.amscotti.bravesearch.adapter.cli.presentation;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.error.UpstreamError;
import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import io.amscotti.bravesearch.domain.metadata.RateLimitWindow;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The human-facing sentence of a failed exchange: for the failures a person can act on it
 * says what happened in plain words and what to do next, naming the observed status and
 * upstream code; every other failure keeps the transport's fixed diagnostic. The machine
 * documents are untouched by this — their text is contract.
 */
final class FailureAdviceTest {

    private static final String REMEDY =
            "check the key with 'brave-search config show', or store a new one with 'brave-search config set-key'";

    @Test
    void aRejectedKeyIsNamedInPlainWordsWithTheRemedy() {
        Outcome.Failure<WebPlaceholder> rejected = failure(
                FailureKind.AUTHENTICATION,
                "upstream exchange failed with status 422",
                "SUBSCRIPTION_TOKEN_INVALID",
                422,
                null);

        assertEquals(
                "web: the Brave API rejected the API key (HTTP 422, code SUBSCRIPTION_TOKEN_INVALID); " + REMEDY,
                FailureAdvice.line("web", rejected));
    }

    @Test
    void anUnauthorizedStatusIsARejectedKeyEvenWithoutAStructuredCode() {
        Outcome.Failure<WebPlaceholder> unauthorized =
                failure(FailureKind.AUTHENTICATION, "upstream exchange failed with status 401", null, 401, null);

        assertEquals("web: the Brave API rejected the API key (HTTP 401); " + REMEDY, FailureAdvice.line("web", unauthorized));
    }

    @Test
    void aPlanRefusalNamesThePlanAndNeverBlamesTheKey() {
        Outcome.Failure<WebPlaceholder> forbidden =
                failure(FailureKind.AUTHENTICATION, "upstream exchange failed with status 403", null, 403, null);
        Outcome.Failure<WebPlaceholder> unpaid =
                failure(FailureKind.AUTHENTICATION, "upstream exchange failed with status 422", "Payment_Required", 422, null);

        assertEquals(
                "web: the API key's plan does not allow this request (HTTP 403); review the plan in the Brave Search API dashboard",
                FailureAdvice.line("web", forbidden));
        assertEquals(
                "web: the API key's plan does not allow this request (HTTP 422, code Payment_Required); review the plan in the Brave Search API dashboard",
                FailureAdvice.line("web", unpaid));
    }

    @Test
    void aRateLimitSaysWhenToRetry() {
        assertEquals(
                "web: the Brave API rate limit was reached (HTTP 429, code TooManyRequests); retry after 1s",
                FailureAdvice.line(
                        "web",
                        failure(
                                FailureKind.RATE_LIMITED,
                                "upstream exchange failed with status 429",
                                "TooManyRequests",
                                429,
                                windows(window("request", 1, 0, Duration.ofSeconds(1)), window("minute", 60, 14, Duration.ofSeconds(42))))));
        assertEquals(
                "web: the Brave API rate limit was reached (HTTP 429); retry after 42s",
                FailureAdvice.line(
                        "web",
                        failure(
                                FailureKind.RATE_LIMITED,
                                "upstream exchange failed with status 429",
                                null,
                                429,
                                windows(window("request", 1, 0, Duration.ofSeconds(1)), window("minute", 60, 0, Duration.ofSeconds(42))))),
                "the longest exhausted window decides: a retry before every window reset is refused again");
        assertEquals(
                "web: the Brave API rate limit was reached (HTTP 429); retry after 1500 ms",
                FailureAdvice.line(
                        "web",
                        failure(
                                FailureKind.RATE_LIMITED,
                                "upstream exchange failed with status 429",
                                null,
                                429,
                                windows(window("request", 1, 0, Duration.ofMillis(1500))))),
                "a finer reset keeps its precision");
        assertEquals(
                "web: the Brave API rate limit was reached (HTTP 429); retry after a short wait",
                FailureAdvice.line(
                        "web",
                        failure(FailureKind.RATE_LIMITED, "upstream exchange failed with status 429", null, 429, null)),
                "no observed window: the advice stays true without inventing a time");
    }

    @Test
    void anEndpointOutsideThePlanIsNamedAsSuch() {
        Outcome.Failure<WebPlaceholder> outsidePlan =
                failure(FailureKind.UPSTREAM, "upstream exchange failed with status 400", "OPTION_NOT_IN_PLAN", 400, null);

        assertEquals(
                "suggest: the API key's plan does not include this endpoint (HTTP 400, code OPTION_NOT_IN_PLAN)",
                FailureAdvice.line("suggest", outsidePlan));
    }

    @Test
    void everyOtherFailureKeepsItsFixedDiagnostic() {
        for (FailureKind kind : List.of(FailureKind.TRANSPORT, FailureKind.MALFORMED, FailureKind.INTERNAL, FailureKind.LOCAL_CONFIG, FailureKind.USAGE)) {
            Outcome.Failure<WebPlaceholder> other = failure(kind, "the fixed diagnostic", null, 0, null);

            assertEquals("web: the fixed diagnostic", FailureAdvice.line("web", other), kind.name());
        }
        assertEquals(
                "web: upstream exchange failed with status 404",
                FailureAdvice.line(
                        "web",
                        failure(FailureKind.UPSTREAM, "upstream exchange failed with status 404", "NotFound", 404, null)),
                "an upstream code with no advice keeps the diagnostic");
    }

    @Test
    void everySentenceIsPlainAscii() {
        List<Outcome.Failure<WebPlaceholder>> advised = List.of(
                failure(FailureKind.AUTHENTICATION, "d", "SUBSCRIPTION_TOKEN_INVALID", 422, null),
                failure(FailureKind.AUTHENTICATION, "d", null, 403, null),
                failure(FailureKind.RATE_LIMITED, "d", null, 429, windows(window("request", 1, 0, Duration.ofSeconds(1)))),
                failure(FailureKind.RATE_LIMITED, "d", null, 429, null),
                failure(FailureKind.UPSTREAM, "d", "OPTION_NOT_IN_PLAN", 400, null));

        for (Outcome.Failure<WebPlaceholder> failure : advised) {
            assertTrue(
                    FailureAdvice.sentence(failure).chars().allMatch(character -> character < 0x80),
                    () -> "the stderr channel is not promised more than ASCII: " + FailureAdvice.sentence(failure));
            assertTrue(FailureAdvice.sentence(failure).equals(FailureAdvice.sentence(failure).strip()), "no edge whitespace");
        }
    }

    @Test
    void anUpstreamCodeCarryingControlCharactersIsNeverEchoedToTheTerminal() {
        // the code is upstream text and stderr is a raw terminal stream: an escape sequence in
        // it must not reach the reader, so a code outside the plain identifier alphabet is dropped
        Outcome.Failure<WebPlaceholder> hostile =
                failure(FailureKind.AUTHENTICATION, "upstream exchange failed with status 422", "\u001b[31mevil\u0007", 422, null);

        String line = FailureAdvice.line("web", hostile);

        assertEquals("web: the Brave API rejected the API key (HTTP 422); " + REMEDY, line);
        assertTrue(line.chars().noneMatch(character -> character < 0x20), "no control character reaches stderr");
    }

    @Test
    void aStatuslessRateLimitPreservesTheLocalPacingDiagnosticVerbatim() {
        String refusal =
                "the rate-limit window resets in 2592000s, beyond this walk's maximum wait of 60s; retry after the reset";
        RateLimitSnapshot monthly = windows(window("month", 2000, 0, Duration.ofSeconds(2592000)));

        assertEquals(
                "web: " + refusal,
                FailureAdvice.line("web", failure(FailureKind.RATE_LIMITED, refusal, null, 0, monthly)),
                "a local pacing refusal carries its own timing diagnostic and must not collapse to generic 429 advice");
        assertEquals(
                "web: " + refusal,
                FailureAdvice.line("web", failure(FailureKind.RATE_LIMITED, refusal, null, 0, null)));
    }

    @Test
    void aSubMillisecondRateLimitWindowFallsBackToAShortWait() {
        Outcome.Failure<WebPlaceholder> subMillisecond = failure(
                FailureKind.RATE_LIMITED,
                "upstream exchange failed with status 429",
                null,
                429,
                windows(window("request", 1, 0, Duration.ofNanos(500_000))));

        assertEquals(
                "web: the Brave API rate limit was reached (HTTP 429); retry after a short wait",
                FailureAdvice.line("web", subMillisecond),
                "a sub-millisecond window must not claim 'retry after 0 ms'");
    }

    @Test
    void anUnlimitedWindowIsNeverReadAsTheTimeToWait() {
        // a limit of 0 means unlimited, so its "remaining 0" says nothing: only real windows decide
        Outcome.Failure<WebPlaceholder> mixed = failure(
                FailureKind.RATE_LIMITED,
                "d",
                null,
                429,
                windows(window("month", 0, 0, Duration.ofHours(1)), window("request", 1, 0, Duration.ofSeconds(1))));

        assertEquals("web: the Brave API rate limit was reached (HTTP 429); retry after 1s", FailureAdvice.line("web", mixed));
    }

    @Test
    void anUpstreamErrorObjectWithoutACodeReadsAsNoCode() {
        Outcome.Failure<WebPlaceholder> codeless = new Outcome.Failure<>(
                FailureKind.AUTHENTICATION,
                "upstream exchange failed with status 401",
                new UpstreamError(null, null, new UpstreamPayload("{}".getBytes(UTF_8))),
                null,
                401);

        assertEquals("web: the Brave API rejected the API key (HTTP 401); " + REMEDY, FailureAdvice.line("web", codeless));
    }

    @Test
    void aStatuslessFailureWithACodeNamesTheCodeAlone() {
        Outcome.Failure<WebPlaceholder> statusless =
                failure(FailureKind.UPSTREAM, "d", "OPTION_NOT_IN_PLAN", 0, null);

        assertEquals(
                "web: the API key's plan does not include this endpoint (code OPTION_NOT_IN_PLAN)",
                FailureAdvice.line("web", statusless));
    }

    private static RateLimitWindow window(String policy, long limit, long remaining, Duration reset) {
        return new RateLimitWindow(policy, limit, remaining, reset);
    }

    private static RateLimitSnapshot windows(RateLimitWindow... windows) {
        return new RateLimitSnapshot(List.of(windows), List.of(), Instant.EPOCH);
    }

    private static Outcome.Failure<WebPlaceholder> failure(
            FailureKind kind, String diagnostic, String code, int status, RateLimitSnapshot rateLimits) {
        return new Outcome.Failure<>(
                kind,
                diagnostic,
                code == null ? null : new UpstreamError(code, null, new UpstreamPayload("{}".getBytes(UTF_8))),
                rateLimits,
                status);
    }

    /** A stand-in result type: the advice reads the failure, never the success it is typed under. */
    private record WebPlaceholder(int httpStatus, UpstreamPayload body) {}
}

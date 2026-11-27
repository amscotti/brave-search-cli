package io.amscotti.bravesearch.adapter.cli.presentation;

import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import io.amscotti.bravesearch.domain.metadata.RateLimitWindow;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The human-facing sentence of a failed exchange. For the failures a person can act on it
 * says what happened in plain words and what to do next, naming the observed status and
 * upstream code: a rejected key, a plan that does not allow the request, a rate limit with
 * the time to wait, an endpoint outside the plan. Every other failure keeps the transport's
 * fixed diagnostic. Only the stderr line changes — the machine documents keep their fixed
 * text, because that text is contract, and the codes they already carry let a script decide
 * without reading a sentence.
 */
final class FailureAdvice {

    private static final String KEY_REMEDY =
            "check the key with 'brave-search config show', or store a new one with 'brave-search config set-key'";

    private static final String PLAN_REMEDY = "review the plan in the Brave Search API dashboard";

    /** The normalized upstream codes that mean the plan, not the key, refused the request. */
    private static final Set<String> PLAN_CODES = Set.of("paymentrequired", "subscriptionrequired", "entitlementrequired");

    private static final String OPTION_NOT_IN_PLAN = "optionnotinplan";

    /**
     * The only codes echoed to the terminal: upstream text reaches stderr raw, so anything
     * outside the plain identifier alphabet is dropped rather than displayed.
     */
    private static final Pattern ECHOABLE_CODE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    private FailureAdvice() {}

    /** The stderr line of one failed exchange: the command, then what happened and what to do. */
    static String line(String command, Outcome.Failure<?> failure) {
        return command + ": " + sentence(failure);
    }

    /** What happened and what to do, without the command prefix. */
    static String sentence(Outcome.Failure<?> failure) {
        Objects.requireNonNull(failure, "failure");
        return switch (failure.kind()) {
            case AUTHENTICATION -> authentication(failure);
            // a statusless rate limit is a local walk refusal (for example, a reset beyond
            // the walk's pacing cap), whose own diagnostic already names the wait and remedy
            case RATE_LIMITED -> failure.httpStatus() == 0
                    ? failure.diagnostic()
                    : withObservation("the Brave API rate limit was reached", failure) + "; " + retryAdvice(failure);
            case UPSTREAM -> OPTION_NOT_IN_PLAN.equals(normalizedCode(failure))
                    ? withObservation("the API key's plan does not include this endpoint", failure)
                    : failure.diagnostic();
            default -> failure.diagnostic();
        };
    }

    private static String authentication(Outcome.Failure<?> failure) {
        String code = normalizedCode(failure);
        boolean planRefusal = failure.httpStatus() == 403 || (code != null && PLAN_CODES.contains(code));
        String what = planRefusal ? "the API key's plan does not allow this request" : "the Brave API rejected the API key";
        String observed = observation(failure);
        return what + (observed.isEmpty() ? "" : " " + observed) + "; " + (planRefusal ? PLAN_REMEDY : KEY_REMEDY);
    }

    /** The sentence followed by the observed facts, when there are any. */
    private static String withObservation(String what, Outcome.Failure<?> failure) {
        String observed = observation(failure);
        return observed.isEmpty() ? what : what + " " + observed;
    }

    /** When to retry: after the longest reset among the exhausted windows, because a retry before every window has reset is refused again. */
    private static String retryAdvice(Outcome.Failure<?> failure) {
        RateLimitSnapshot snapshot = failure.rateLimits();
        Duration longest = Duration.ZERO;
        if (snapshot != null) {
            for (RateLimitWindow window : snapshot.windows()) {
                if (window.limit() != 0 && window.remaining() == 0 && window.reset().compareTo(longest) > 0) {
                    longest = window.reset();
                }
            }
        }
        long millis = longest.toMillis();
        if (millis == 0) {
            return "retry after a short wait";
        }
        return "retry after " + (millis % 1000 == 0 ? millis / 1000 + "s" : millis + " ms");
    }

    /** The observed facts a reader can act on: the status, and the upstream code when it is safe to show. */
    private static String observation(Outcome.Failure<?> failure) {
        int status = failure.httpStatus();
        String code = echoableCode(failure);
        if (status == 0 && code == null) {
            return "";
        }
        String facts = status == 0 ? "" : "HTTP " + status;
        if (code != null) {
            facts = facts.isEmpty() ? "code " + code : facts + ", code " + code;
        }
        return "(" + facts + ")";
    }

    private static String echoableCode(Outcome.Failure<?> failure) {
        if (failure.upstream() == null || failure.upstream().code() == null) {
            return null;
        }
        String code = failure.upstream().code();
        return ECHOABLE_CODE.matcher(code).matches() ? code : null;
    }

    /** The upstream code as the classification reads it: lower-cased, punctuation stripped. */
    private static String normalizedCode(Outcome.Failure<?> failure) {
        if (failure.upstream() == null || failure.upstream().code() == null) {
            return null;
        }
        return failure.upstream().code().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}

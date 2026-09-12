package io.amscotti.bravesearch.adapter.bravehttp.metadata;

import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import io.amscotti.bravesearch.domain.metadata.RateLimitWindow;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Positional parser of the four rate-limit headers.
 *
 * <p>Every header ({@code X-RateLimit-Limit}, {@code X-RateLimit-Policy}, {@code
 * X-RateLimit-Remaining}, {@code X-RateLimit-Reset}) carries one comma-separated token list,
 * and window i is built from the i-th token of each list. Physical header lines are flattened
 * case-insensitively in arrival order before splitting, so a value spread over repeated lines
 * behaves exactly like one comma list. Token positions are never shifted: a position where
 * the lists disagree in length, or where a numeric token is not a plain nonnegative integer,
 * yields a dropped window and a nonfatal note — never a shuffle of the windows that follow.
 * Policy tokens are free-form strings kept verbatim after surrounding-whitespace trimming;
 * an empty policy token is malformed. Because the split is positional, a policy token cannot
 * itself contain a comma — an upstream format constraint with no escape — so a policy value
 * carrying one splits into fragments that misalign the lists, which the misalignment note
 * reports while the surviving fragments stay verbatim. A reset token is a whole-second count
 * from the observation instant, and the observation instant comes from the injected {@link
 * Clock}, so a documented reset of {@code 0} is the valid duration zero, not an epoch. A
 * reset beyond the largest renderable whole-second horizon ({@code Long.MAX_VALUE / 1000}
 * seconds) is hostile input and drops its window with a note, because a duration no
 * millisecond rendering can hold must never crash a later success encoding.
 *
 * <p>Two bounds protect the pipeline from amplification: parsing caps the window count at
 * {@value #MAX_WINDOWS} with a truncation note, and the reset horizon above drops
 * unrenderable durations. Notes quote header names, positions, and list lengths only, never
 * observed token text, because header values are untrusted input and notes ride diagnostic
 * channels.
 */
public final class RateLimitHeaderParser {

    private static final String LIMIT = "x-ratelimit-limit";
    private static final String POLICY = "x-ratelimit-policy";
    private static final String REMAINING = "x-ratelimit-remaining";
    private static final String RESET = "x-ratelimit-reset";

    /**
     * The largest whole-second reset this pipeline can render: a duration at or below it converts
     * to milliseconds without long overflow and, added to any observation instant the clock can
     * produce, stays inside {@link java.time.Instant} range. A reset beyond it is hostile input
     * and drops its window with a note instead of crashing a later rendering.
     */
    private static final long MAX_RENDERABLE_RESET_SECONDS = Long.MAX_VALUE / 1000;

    /**
     * The most windows one exchange may contribute: a hostile header can carry an unbounded
     * comma list, so parsing truncates beyond this count with a note instead of amplifying
     * whatever the peer chose to send.
     */
    private static final int MAX_WINDOWS = 64;

    private RateLimitHeaderParser() {}

    /**
     * Parses the flattened, case-insensitive header multi-map of one exchange against the
     * injected observation clock. The map's iteration order decides the arrival order of
     * physical lines; case-variant spellings of one header name flatten into that header's
     * token list. Parsing never throws: every malformed or missing token degrades to a
     * dropped window plus a note.
     *
     * @throws NullPointerException when either argument is null
     */
    public static RateLimitSnapshot parse(Map<String, List<String>> headers, Clock clock) {
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(clock, "clock");
        Map<String, List<String>> flattened = flatten(headers);
        List<String> limit = tokens(flattened, LIMIT);
        List<String> policy = tokens(flattened, POLICY);
        List<String> remaining = tokens(flattened, REMAINING);
        List<String> reset = tokens(flattened, RESET);

        List<String> notes = new ArrayList<>();
        int aligned = Math.min(
                Math.min(limit.size(), policy.size()), Math.min(remaining.size(), reset.size()));
        if (aligned != Math.max(
                        Math.max(limit.size(), policy.size()),
                        Math.max(remaining.size(), reset.size()))) {
            notes.add("x-ratelimit header lists are misaligned: limit="
                    + limit.size() + ", policy=" + policy.size() + ", remaining="
                    + remaining.size() + ", reset=" + reset.size()
                    + "; unaligned trailing positions dropped");
        }

        List<RateLimitWindow> windows = new ArrayList<>();
        int parsed = Math.min(aligned, MAX_WINDOWS);
        if (aligned > MAX_WINDOWS) {
            notes.add("x-ratelimit window count capped at " + MAX_WINDOWS + "; "
                    + (aligned - MAX_WINDOWS) + " trailing positions truncated");
        }
        for (int position = 0; position < parsed; position++) {
            String policyToken = policy.get(position);
            if (policyToken.isEmpty()) {
                notes.add("x-ratelimit-policy token at position " + position + " is empty; window dropped");
                continue;
            }
            Long limitValue = nonnegativeInteger("limit", limit.get(position), position, notes);
            Long remainingValue = nonnegativeInteger("remaining", remaining.get(position), position, notes);
            Long resetValue = nonnegativeInteger("reset", reset.get(position), position, notes);
            if (limitValue == null || remainingValue == null || resetValue == null) {
                continue;
            }
            if (resetValue > MAX_RENDERABLE_RESET_SECONDS) {
                notes.add("x-ratelimit-reset token at position " + position
                        + " exceeds the representable reset horizon; window dropped");
                continue;
            }
            windows.add(new RateLimitWindow(
                    policyToken, limitValue, remainingValue, Duration.ofSeconds(resetValue)));
        }
        return new RateLimitSnapshot(windows, notes, clock.instant());
    }

    /** Collects every value of the four known headers, case-insensitively, in arrival order. */
    private static Map<String, List<String>> flatten(Map<String, List<String>> headers) {
        Map<String, List<String>> flattened = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> line : headers.entrySet()) {
            String canonical = line.getKey().toLowerCase(Locale.ROOT);
            if (canonical.equals(LIMIT)
                    || canonical.equals(POLICY)
                    || canonical.equals(REMAINING)
                    || canonical.equals(RESET)) {
                flattened.computeIfAbsent(canonical, ignored -> new ArrayList<>())
                        .addAll(line.getValue());
            }
        }
        return flattened;
    }

    /** Splits each physical value on commas, keeping empty tokens, and trims each token. */
    private static List<String> tokens(Map<String, List<String>> flattened, String name) {
        List<String> tokens = new ArrayList<>();
        for (String value : flattened.getOrDefault(name, List.of())) {
            for (String token : value.split(",", -1)) {
                tokens.add(token.strip());
            }
        }
        return tokens;
    }

    /**
     * A plain digit run that fits a long, or {@code null} after recording a note naming the
     * field and position; signs, decimals, and overflow are all malformed.
     */
    private static Long nonnegativeInteger(String field, String token, int position, List<String> notes) {
        if (!token.matches("\\d+")) {
            notes.add("x-ratelimit-" + field + " token at position " + position
                    + " is not a nonnegative integer; window dropped");
            return null;
        }
        try {
            return Long.parseLong(token);
        } catch (NumberFormatException oversized) {
            notes.add("x-ratelimit-" + field + " token at position " + position
                    + " does not fit a counter; window dropped");
            return null;
        }
    }
}

package io.amscotti.bravesearch.adapter.bravehttp.metadata;

import io.amscotti.bravesearch.domain.metadata.Usage;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Parser of the Answers usage headers of a blocking exchange: the documented {@code
 * X-Request-Requests}, {@code X-Request-Queries}, {@code X-Request-Tokens-In}, {@code
 * X-Request-Tokens-Out}, {@code X-Request-Requests-Cost}, {@code X-Request-Queries-Cost},
 * {@code X-Request-Tokens-In-Cost}, {@code X-Request-Tokens-Out-Cost}, and {@code
 * X-Request-Total-Cost} response headers.
 *
 * <p>Header names match case-insensitively; each known header contributes its first physical
 * value, trimmed, and a repeated header records a note rather than silently choosing. Counters
 * are plain nonnegative integers and costs are decimals parsed at their exact reported scale;
 * a negative or unparseable value drops that one field and records a nonfatal note, keeping
 * every surviving field. Unknown {@code X-Request-*} names are preserved verbatim with the
 * first-seen spelling and their values joined in arrival order. Parsing never throws and
 * never quotes observed values in notes, because header text is untrusted input.
 */
public final class AnswersUsageParser {

    private static final String PREFIX = "x-request-";

    private AnswersUsageParser() {}

    /**
     * Parses the flattened, case-insensitive header multi-map of one exchange; {@code null}
     * when the exchange carried no {@code X-Request-*} header at all, otherwise a usage value
     * holding whatever parsed.
     *
     * @throws NullPointerException when {@code headers} is null
     */
    public static Usage parse(Map<String, List<String>> headers) {
        Objects.requireNonNull(headers, "headers");
        Map<String, List<String>> flattened = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> line : headers.entrySet()) {
            String canonical = line.getKey().toLowerCase(java.util.Locale.ROOT);
            flattened.computeIfAbsent(canonical, ignored -> new ArrayList<>())
                    .addAll(line.getValue());
        }
        if (flattened.keySet().stream().noneMatch(name -> name.startsWith(PREFIX))) {
            return null;
        }

        List<String> notes = new ArrayList<>();
        Long requests = null;
        Long queries = null;
        Long tokensIn = null;
        Long tokensOut = null;
        BigDecimal requestsCost = null;
        BigDecimal queriesCost = null;
        BigDecimal tokensInCost = null;
        BigDecimal tokensOutCost = null;
        BigDecimal totalCost = null;
        Map<String, String> unknown = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> line : flattened.entrySet()) {
            String name = line.getKey();
            List<String> values = line.getValue();
            if (!name.startsWith(PREFIX)) {
                continue;
            }
            switch (name) {
                case "x-request-requests" -> requests = counter(name, repeated(name, values, notes), notes);
                case "x-request-queries" -> queries = counter(name, repeated(name, values, notes), notes);
                case "x-request-tokens-in" -> tokensIn = counter(name, repeated(name, values, notes), notes);
                case "x-request-tokens-out" -> tokensOut = counter(name, repeated(name, values, notes), notes);
                case "x-request-requests-cost" -> requestsCost = cost(name, repeated(name, values, notes), notes);
                case "x-request-queries-cost" -> queriesCost = cost(name, repeated(name, values, notes), notes);
                case "x-request-tokens-in-cost" -> tokensInCost = cost(name, repeated(name, values, notes), notes);
                case "x-request-tokens-out-cost" -> tokensOutCost = cost(name, repeated(name, values, notes), notes);
                case "x-request-total-cost" -> totalCost = cost(name, repeated(name, values, notes), notes);
                default -> unknown.put(observedName(headers, name), String.join(", ", values));
            }
        }
        return new Usage(
                requests,
                queries,
                tokensIn,
                tokensOut,
                requestsCost,
                queriesCost,
                tokensInCost,
                tokensOutCost,
                totalCost,
                unknown,
                notes);
    }

    /**
     * The trimmed first value of a known single-valued header, recording a note when physical
     * lines repeated it, or {@code null} when the header arrived with no value line at all —
     * an absence whose note is recorded here, so the field parsers treat {@code null} as
     * nothing left to parse.
     */
    private static String repeated(String name, List<String> values, List<String> notes) {
        if (values.isEmpty()) {
            notes.add(name + " is present with no value");
            return null;
        }
        if (values.size() > 1) {
            notes.add(name + " is repeated; the first value applies");
        }
        return values.getFirst().strip();
    }

    /** A plain digit run that fits a long, or {@code null} after a note naming the header. */
    private static Long counter(String name, String token, List<String> notes) {
        if (token == null) {
            return null;
        }
        if (!token.matches("\\d+")) {
            notes.add(name + " is not a nonnegative integer");
            return null;
        }
        try {
            return Long.parseLong(token);
        } catch (NumberFormatException oversized) {
            notes.add(name + " does not fit a counter");
            return null;
        }
    }

    /** A decimal at its exact reported scale, nonnegative, or {@code null} after a note. */
    private static BigDecimal cost(String name, String token, List<String> notes) {
        if (token == null) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(token);
            if (value.signum() < 0) {
                notes.add(name + " is not a nonnegative decimal");
                return null;
            }
            return value;
        } catch (NumberFormatException malformed) {
            notes.add(name + " is not a nonnegative decimal");
            return null;
        }
    }

    /**
     * The spelling under which an unknown header was first observed, because the preserved
     * name is upstream data and case-variant repeats fold onto their first spelling.
     */
    private static String observedName(Map<String, List<String>> headers, String canonical) {
        for (String observed : headers.keySet()) {
            if (observed.toLowerCase(java.util.Locale.ROOT).equals(canonical)) {
                return observed;
            }
        }
        return canonical;
    }
}

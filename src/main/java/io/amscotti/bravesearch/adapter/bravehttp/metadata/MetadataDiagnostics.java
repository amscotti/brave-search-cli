package io.amscotti.bravesearch.adapter.bravehttp.metadata;

import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import io.amscotti.bravesearch.domain.metadata.Usage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Human-readable diagnostic lines of one exchange's response metadata: the observed window
 * count, every nonfatal note verbatim, and — when usage is present — a summary naming only
 * the fields that were reported.
 *
 * <p>The lines are the rendering seam for a future verbose flag; they are not wired to any
 * output channel here. They quote counts, field names, and the already-redacted parser
 * notes, never preserved unknown header text, so they are safe on every diagnostic channel.
 */
public final class MetadataDiagnostics {

    private MetadataDiagnostics() {}

    /** The diagnostic lines of one exchange's rate-limit snapshot and optional usage. */
    public static List<String> renderNotes(RateLimitSnapshot rateLimits, Usage usage) {
        Objects.requireNonNull(rateLimits, "rateLimits");
        List<String> lines = new ArrayList<>();
        lines.add("rate-limit windows: " + rateLimits.windows().size());
        lines.addAll(rateLimits.notes());
        if (usage != null) {
            lines.add(usageLine(usage));
            lines.addAll(usage.notes());
        }
        return List.copyOf(lines);
    }

    private static String usageLine(Usage usage) {
        StringBuilder reported = new StringBuilder("answers usage: ");
        joinField(reported, "requests", usage.requests());
        joinField(reported, "queries", usage.queries());
        joinField(reported, "tokens_in", usage.tokensIn());
        joinField(reported, "tokens_out", usage.tokensOut());
        joinField(reported, "requests_cost", usage.requestsCost());
        joinField(reported, "queries_cost", usage.queriesCost());
        joinField(reported, "tokens_in_cost", usage.tokensInCost());
        joinField(reported, "tokens_out_cost", usage.tokensOutCost());
        joinField(reported, "total_cost", usage.totalCost());
        if (reported.length() == "answers usage: ".length()) {
            return "answers usage: no known counters reported";
        }
        return reported.toString();
    }

    private static void joinField(StringBuilder line, String name, Object value) {
        if (value == null) {
            return;
        }
        if (line.length() > "answers usage: ".length()) {
            line.append(", ");
        }
        line.append(name).append('=').append(value);
    }
}

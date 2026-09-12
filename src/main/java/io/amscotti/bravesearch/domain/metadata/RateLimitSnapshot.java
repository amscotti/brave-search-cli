package io.amscotti.bravesearch.domain.metadata;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One observation of the upstream rate-limit headers: the quota windows that fully parsed,
 * the nonfatal notes describing everything dropped, and the instant the headers were
 * observed.
 *
 * <p>The upstream reset value is a duration counted from the observation, never an epoch,
 * so the absolute reset instant is derived per window — {@link #resetAtOf(RateLimitWindow)}
 * adds the window's own reset duration to {@link #observedAt()}. Deriving per window keeps
 * the duration the single authoritative fact; a consumer that needs one overall instant
 * computes it from the windows it considers applicable, because which window governs a
 * decision is the consumer's question, not the snapshot's.
 *
 * <p>Notes quote header names, positions, and counts only, never observed header text, so
 * any note is safe on every diagnostic channel.
 */
public record RateLimitSnapshot(List<RateLimitWindow> windows, List<String> notes, Instant observedAt) {

    public RateLimitSnapshot {
        windows = windows == null ? List.of() : List.copyOf(windows);
        notes = notes == null ? List.of() : List.copyOf(notes);
        Objects.requireNonNull(observedAt, "observedAt");
    }

    /**
     * The snapshot of an exchange that observed no rate-limit headers at all: no windows, no
     * notes, and the epoch as the observation instant, because with no window there is no
     * reset to anchor.
     */
    public static RateLimitSnapshot empty() {
        return new RateLimitSnapshot(List.of(), List.of(), Instant.EPOCH);
    }

    /** The absolute instant at which the given observed window resets. */
    public Instant resetAtOf(RateLimitWindow window) {
        Objects.requireNonNull(window, "window");
        return observedAt.plus(window.reset());
    }
}

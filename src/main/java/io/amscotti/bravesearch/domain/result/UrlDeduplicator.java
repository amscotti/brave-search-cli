package io.amscotti.bravesearch.domain.result;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Exact-URL deduplication of the logical results across the pages of one pagination run,
 * written once over any entry type carrying the API-provided URL string.
 *
 * <p>The API-provided URL string is the whole identity of a result: no decoding,
 * lowercasing, case folding, fragment stripping, or query-parameter dropping ever happens,
 * because any transformation could merge two distinct resources. First-seen order is
 * preserved — a duplicate never moves its earlier occurrence — and an entry without a
 * usable URL string (a missing, null, or empty one) is retained on every page and never
 * participates in deduplication, so an upstream result that lost its URL cannot silently
 * delete a later page's result.
 *
 * <p>Pages are added in request order; the deduplicator is a single-run, single-threaded
 * object, exactly like the sequential pagination that feeds it.
 *
 * @param <E> the logical result entry type whose {@code url()} member is the identity
 */
public final class UrlDeduplicator<E extends UrlIdentifiedEntry> {

    /** One retained result with the user-facing page it was first seen on. */
    public record Kept<E extends UrlIdentifiedEntry>(E entry, int page) {

        public Kept {
            Objects.requireNonNull(entry, "entry");
        }
    }

    private final Set<String> seenUrls = new HashSet<>();
    private final List<Kept<E>> kept = new ArrayList<>();
    private final List<Integer> keptPerPage = new ArrayList<>();
    private int duplicatesRemoved;

    /** Adds one page's entries in presentation order and returns this page's retained ones. */
    public List<Kept<E>> addPage(int page, List<E> entries) {
        if (page < 1) {
            throw new IllegalArgumentException("page must not be smaller than one: " + page);
        }
        Objects.requireNonNull(entries, "entries");
        List<Kept<E>> keptHere = new ArrayList<>(entries.size());
        for (E entry : entries) {
            Objects.requireNonNull(entry, "entry");
            String url = entry.url();
            if (url == null || url.isEmpty() || seenUrls.add(url)) {
                Kept<E> retained = new Kept<>(entry, page);
                kept.add(retained);
                keptHere.add(retained);
            } else {
                duplicatesRemoved++;
            }
        }
        keptPerPage.add(keptHere.size());
        return List.copyOf(keptHere);
    }

    /** All retained entries across every added page, in first-seen order. */
    public List<Kept<E>> kept() {
        return List.copyOf(kept);
    }

    /** How many entries were dropped as exact-URL duplicates across every added page. */
    public int duplicatesRemoved() {
        return duplicatesRemoved;
    }

    /** The retained-entry count of each added page, in page order. */
    public List<Integer> keptPerPage() {
        return List.copyOf(keptPerPage);
    }
}

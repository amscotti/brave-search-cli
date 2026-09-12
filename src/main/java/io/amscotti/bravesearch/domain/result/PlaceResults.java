package io.amscotti.bravesearch.domain.result;

import java.util.List;
import java.util.Objects;

/**
 * The logical places of one place search exchange in presentation order: the entries
 * of every documented response bucket, enumerated in the buckets' documented order —
 * {@code results}, {@code cities}, {@code countries}, {@code regions}, {@code
 * neighborhoods}, {@code addresses}, {@code streets} — with each entry carrying the
 * wire word of the bucket it came from and its original zero-based position inside
 * that bucket's array, because one response mixes buckets and the output modes state
 * where every place came from.
 *
 * <p>Entry members are {@code null} when the upstream entry carried no usable textual
 * form of them; an absent member is omitted from every rendered form, never rendered
 * as an empty placeholder. The {@code id} is the opaque, ephemeral upstream place id —
 * carried verbatim, never validated for internal format — the rating members are the
 * textual forms the entry's {@code rating} block carried, and every other upstream
 * member is deliberately not carried, because the projection renders only the members
 * the listing can show. The {@code mixed} ordering hints are ignored: enumeration
 * follows the documented bucket order.
 */
public record PlaceResults(List<Entry> entries) {

    /**
     * The documented response buckets in enumeration order; any bucket may be null or
     * omitted upstream, and unknown top-level members — future buckets included — are
     * never enumerated.
     */
    public static final List<String> BUCKETS =
            List.of("results", "cities", "countries", "regions", "neighborhoods", "addresses", "streets");

    public PlaceResults {
        entries = entries == null ? List.of() : List.copyOf(entries);
        entries.forEach(Objects::requireNonNull);
    }

    /**
     * One logical place: its bucket's wire word, its original zero-based position in
     * that bucket's array, and the typed members that carried usable text.
     */
    public record Entry(
            String bucket,
            int position,
            String id,
            String title,
            String address,
            String ratingValue,
            String ratingCount,
            String distance,
            String phone,
            String website) {

        public Entry {
            if (position < 0) {
                throw new IllegalArgumentException("result position must not be negative: " + position);
            }
            Objects.requireNonNull(bucket, "bucket");
            if (bucket.isBlank()) {
                throw new IllegalArgumentException("bucket must name its response array");
            }
        }
    }
}

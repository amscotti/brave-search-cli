package io.amscotti.bravesearch.domain.result;

import java.util.List;
import java.util.Objects;

/**
 * The logical image results of one search exchange in presentation order: the entries of
 * the upstream top-level {@code results} array, each keeping its original zero-based
 * position so output modes can state where the result came from.
 *
 * <p>{@link #BUCKET} is the stable bucket name of every entry — the wire word that names
 * the logical result list these entries were enumerated from. Entry members are {@code
 * null} when the upstream result carried no usable textual form of them; an absent member
 * is omitted from every rendered form, never rendered as an empty placeholder. {@code
 * url} is the result page the image was found on, {@code image} the full-size image url,
 * and {@code thumbnail} the thumbnail url; every other upstream member — the numeric
 * dimension block and anything unknown — is deliberately not carried, because the
 * projection renders only textual members the listing can show.
 */
public record ImageResults(List<Entry> entries) {

    /** The bucket name of the images logical result list. */
    public static final String BUCKET = "images";

    public ImageResults {
        entries = entries == null ? List.of() : List.copyOf(entries);
        entries.forEach(Objects::requireNonNull);
    }

    /**
     * One logical image result: its original zero-based position in the upstream results
     * array and the typed members that carried usable text.
     */
    public record Entry(int position, String title, String url, String image, String thumbnail) {

        public Entry {
            if (position < 0) {
                throw new IllegalArgumentException("result position must not be negative: " + position);
            }
        }
    }
}

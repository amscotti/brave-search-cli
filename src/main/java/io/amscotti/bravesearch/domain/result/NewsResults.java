package io.amscotti.bravesearch.domain.result;

import java.util.List;
import java.util.Objects;

/**
 * The logical news results of one search exchange in presentation order: the entries of the
 * upstream {@code news.results} array, each keeping its original zero-based position so
 * output modes can state where the result came from.
 *
 * <p>{@link #BUCKET} is the stable bucket name of every entry — the wire word that names the
 * logical result list these entries were enumerated from. Entry members are {@code null} when
 * the upstream result carried no usable textual form of them; an absent member is omitted from
 * every rendered form, never rendered as an empty placeholder. {@code age} is the freshness
 * member the news endpoint attaches to a result — whatever textual form it carried travels
 * verbatim.
 */
public record NewsResults(List<Entry> entries) {

    /** The bucket name of the news logical result list. */
    public static final String BUCKET = "news";

    public NewsResults {
        entries = entries == null ? List.of() : List.copyOf(entries);
        entries.forEach(Objects::requireNonNull);
    }

    /**
     * One logical news result: its original zero-based position in the upstream results array
     * and the typed members that carried usable text.
     */
    public record Entry(int position, String title, String url, String description, String age)
            implements UrlIdentifiedEntry {

        public Entry {
            if (position < 0) {
                throw new IllegalArgumentException("result position must not be negative: " + position);
            }
        }
    }
}

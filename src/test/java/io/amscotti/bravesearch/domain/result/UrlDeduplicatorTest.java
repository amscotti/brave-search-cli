package io.amscotti.bravesearch.domain.result;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Exact-URL deduplication across the pages of one pagination run: the API-provided URL
 * string is the whole identity — no decoding, lowercasing, fragment stripping, or query
 * dropping — first-seen order survives, and entries without a usable URL string are
 * retained rather than merged or dropped.
 */
final class UrlDeduplicatorTest {

    @Test
    void overlappingPagesKeepFirstSeenOrderAndCountTheDuplicates() {
        UrlDeduplicator<WebResults.Entry> deduplicator = new UrlDeduplicator<>();
        List<UrlDeduplicator.Kept<WebResults.Entry>> firstPage = deduplicator.addPage(1, page("https://example.com/a", "https://example.com/b"));
        List<UrlDeduplicator.Kept<WebResults.Entry>> secondPage = deduplicator.addPage(2, page("https://example.com/b", "https://example.com/c"));

        assertEquals(2, firstPage.size(), "the first page keeps both of its entries");
        assertEquals(1, secondPage.size(), "the overlap of the second page is a duplicate");
        assertEquals("https://example.com/c", secondPage.getFirst().entry().url());
        assertEquals(2, secondPage.getFirst().page(), "the kept entry carries its own page number");
        assertEquals(List.of("https://example.com/a", "https://example.com/b", "https://example.com/c"), urls(deduplicator.kept()));
        assertEquals(1, deduplicator.duplicatesRemoved(), "exactly the overlapping URL was removed");
        assertEquals(List.of(2, 1), deduplicator.keptPerPage(), "per-page kept counts follow the page order");
    }

    @Test
    void duplicatesInsideOnePageAreRemovedLikeCrossPageDuplicates() {
        UrlDeduplicator<WebResults.Entry> deduplicator = new UrlDeduplicator<>();

        List<UrlDeduplicator.Kept<WebResults.Entry>> kept = deduplicator.addPage(1, page("https://example.com/a", "https://example.com/a"));

        assertEquals(1, kept.size());
        assertEquals(1, deduplicator.duplicatesRemoved());
        assertEquals(List.of(1), deduplicator.keptPerPage());
    }

    @Test
    void differentSpellingsOfSimilarUrlsAreNeverMerged() {
        UrlDeduplicator<WebResults.Entry> deduplicator = new UrlDeduplicator<>();

        deduplicator.addPage(
                1,
                page(
                        "https://example.com/a",
                        "HTTPS://example.com/a",
                        "https://example.com/a#fragment",
                        "https://example.com/a?tracking=1",
                        "https%3A%2F%2Fexample.com%2Fa",
                        "https://example.com/a/",
                        "https://example.com/a?b=2&a=1",
                        "https://example.com/a?a=1&b=2"));

        assertEquals(0, deduplicator.duplicatesRemoved(), "every distinct exact string stays a distinct result");
        assertEquals(8, deduplicator.kept().size());
    }

    @Test
    void entriesWithoutAUsableUrlStringAreRetainedAndNeverDeduplicated() {
        UrlDeduplicator<WebResults.Entry> deduplicator = new UrlDeduplicator<>();

        List<UrlDeduplicator.Kept<WebResults.Entry>> first = deduplicator.addPage(1, page((String) null, ""));
        List<UrlDeduplicator.Kept<WebResults.Entry>> second = deduplicator.addPage(2, page((String) null, ""));

        assertEquals(2, first.size(), "a missing URL never drops the entry");
        assertEquals(2, second.size(), "a missing URL is never treated as a duplicate of another missing URL");
        assertEquals(0, deduplicator.duplicatesRemoved());
        assertEquals(4, deduplicator.kept().size());
        assertEquals(List.of(2, 2), deduplicator.keptPerPage());
    }

    private static List<WebResults.Entry> page(String... urls) {
        java.util.ArrayList<WebResults.Entry> entries = new java.util.ArrayList<>();
        for (int position = 0; position < urls.length; position++) {
            entries.add(new WebResults.Entry(position, "Title " + position, urls[position], null));
        }
        return entries;
    }

    private static List<String> urls(List<UrlDeduplicator.Kept<WebResults.Entry>> kept) {
        return kept.stream().map(entry -> entry.entry().url()).toList();
    }
}

package io.amscotti.bravesearch.domain.request;

import io.amscotti.bravesearch.domain.error.UsageValidationError;
import java.util.Objects;

/**
 * The shared query text of every search endpoint: between one and {@link
 * #MAX_QUERY_CODE_POINTS} Unicode code points, one to {@link #MAX_QUERY_WORDS}
 * whitespace-delimited words, never all whitespace, and never normalized or trimmed — valid
 * input is transmitted byte-for-byte as given.
 *
 * <p>One value owns the rule so every endpoint's request validates the query identically;
 * requests keep their plain {@code String} member and delegate to {@link
 * #requireValid(String)} in their constructors.
 */
public record QueryText(String text) {

    /** The largest query, in Unicode code points. */
    public static final int MAX_QUERY_CODE_POINTS = 400;

    /** The largest number of whitespace-delimited words of a query. */
    public static final int MAX_QUERY_WORDS = 50;

    public QueryText {
        Objects.requireNonNull(text, "query");
        int codePoints = text.codePointCount(0, text.length());
        if (codePoints < 1 || codePoints > MAX_QUERY_CODE_POINTS) {
            throw new UsageValidationError("query must be between 1 and " + MAX_QUERY_CODE_POINTS + " code points");
        }
        int words = countWords(text);
        if (words < 1) {
            throw new UsageValidationError("query must carry at least one non-whitespace word");
        }
        if (words > MAX_QUERY_WORDS) {
            throw new UsageValidationError("query must carry at most " + MAX_QUERY_WORDS + " whitespace-delimited words");
        }
    }

    /**
     * Validates {@code query} against the shared rules.
     *
     * @throws UsageValidationError when the query breaks any shared boundary
     */
    public static void requireValid(String query) {
        new QueryText(query);
    }

    /** The number of whitespace-delimited words, shared with the validation itself. */
    int wordCount() {
        return countWords(text);
    }

    /** Nonempty runs between whitespace or space characters — the shared word definition. */
    private static int countWords(String text) {
        int words = 0;
        boolean insideWord = false;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                insideWord = false;
            } else if (!insideWord) {
                words++;
                insideWord = true;
            }
        }
        return words;
    }
}

package io.amscotti.bravesearch.domain.request;

import io.amscotti.bravesearch.domain.error.UsageValidationError;

/**
 * The country spellings the listing endpoints document: a two-letter code or the
 * region-wide {@code ALL}. The validation lived as one private copy per request record
 * until the spellings had to be shared; this is the single home now, so a future spelling
 * changes once instead of per vertical.
 */
public final class CountryCode {

    /** The region-wide spelling the news, videos, images, suggest, and spellcheck endpoints document. */
    public static final String ALL = "ALL";

    private CountryCode() {}

    /**
     * Rejects every {@code country} spelling that is not a two-letter code or the
     * region-wide {@code ALL}; null (the unsupplied case) passes untouched.
     *
     * @throws UsageValidationError when a supplied country is not a documented spelling
     */
    public static void requireDocumented(String country) {
        if (country == null) {
            return;
        }
        boolean twoLetters = country.length() == 2 && isAsciiLetter(country.charAt(0)) && isAsciiLetter(country.charAt(1));
        if (!twoLetters && !ALL.equals(country)) {
            throw new UsageValidationError("country must be a two-letter code or " + ALL);
        }
    }

    private static boolean isAsciiLetter(char letter) {
        return (letter >= 'A' && letter <= 'Z') || (letter >= 'a' && letter <= 'z');
    }
}

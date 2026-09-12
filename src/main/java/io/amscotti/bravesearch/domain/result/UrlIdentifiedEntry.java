package io.amscotti.bravesearch.domain.result;

/**
 * A logical result entry whose exact API-provided URL string is its cross-page identity.
 *
 * <p>The deduplication rule of a multi-request walk keys on the URL string exactly as the
 * upstream provided it — no decoding, lowercasing, or stripping — so the interface exposes
 * only that member: it is the whole identity, and an entry without a usable URL string
 * (null or empty) is retained and never deduplicated.
 */
public interface UrlIdentifiedEntry {

    /** The exact API-provided URL string, or null when the result carried none. */
    String url();
}

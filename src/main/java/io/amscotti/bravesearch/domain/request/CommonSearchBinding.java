package io.amscotti.bravesearch.domain.request;

/**
 * The endpoint-shared search setters of a request builder: country, search language,
 * interface language, SafeSearch, freshness, count, page, and the tri-state spellcheck
 * flag, exactly as the request records declare them.
 *
 * <p>A builder implements this interface with its own fluent methods — their covariant
 * returns satisfy the declarations — so the shared grammar of {@code CommonSearchOptions}
 * binds onto any request whose endpoint documents all of it through one mapper instead of
 * one mapping copy per command. Builders of endpoints that document only a subset stay
 * outside this interface and map their subset explicitly, because a setter a request
 * cannot carry must never exist merely for uniform binding.
 */
public interface CommonSearchBinding {

    /** Sets the two-letter (or endpoint-documented) result country code. */
    CommonSearchBinding country(String country);

    /** Sets the result language tag. */
    CommonSearchBinding searchLang(String searchLang);

    /** Sets the interface language tag. */
    CommonSearchBinding uiLang(String uiLang);

    /** Sets the SafeSearch level. */
    CommonSearchBinding safeSearch(SafeSearch safeSearch);

    /** Sets the freshness window. */
    CommonSearchBinding freshness(Freshness freshness);

    /** Sets the result count within the endpoint's documented range. */
    CommonSearchBinding count(int count);

    /** Sets the user-facing page, sent as its zero-based offset. */
    CommonSearchBinding page(int page);

    /** Sets the tri-state spellcheck flag. */
    CommonSearchBinding spellcheck(boolean spellcheck);
}

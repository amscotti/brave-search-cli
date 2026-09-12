package io.amscotti.bravesearch.adapter.bravehttp.endpoint;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.BraveApiRequest;
import io.amscotti.bravesearch.application.exchange.ExpectedUserAgent;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.request.ImageSearchRequest;
import io.amscotti.bravesearch.domain.request.SafeSearch;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Assembly of the images search wire form, pinned to the upstream contract's images
 * family: a full-parameter request renders one pinned percent-encoded URI over exactly
 * the documented field inventory — no interface language, no freshness, no operators, no
 * Goggles, and no offset ever appears — every unsupplied value is omitted rather than
 * coerced to a default, the region-wide country spelling travels unchanged, and the
 * method is GET always: the endpoint documents no POST form, so not even an encoded URI
 * beyond the shared 8,000-byte threshold switches methods.
 */
final class ImageSearchEndpointTest {

    private static final BraveApiOrigin PRODUCTION = BraveApiOrigin.production();

    /** The pinned full-parameter rendering: every documented images wire field supplied. */
    private static final String FULL_QUERY =
            "count=150&country=DE&q=hello%20world&safesearch=strict&search_lang=de&spellcheck=false";

    @Test
    void fullParameterRequestRendersTheExactEncodedUri() throws Exception {
        BraveApiRequest assembled = ImageSearchEndpoint.assemble(fullRequest(), PRODUCTION, token("opaque-test-token"));

        assertEquals("https://api.search.brave.com/res/v1/images/search?" + FULL_QUERY, assembled.uri().toString());
        assertEquals(BraveApiRequest.Method.GET, assembled.method());
        assertNull(assembled.bodyBytes());
        assertFalse(assembled.rawResponseMode());
        assertEquals(
                List.of(
                        new BraveApiRequest.HeaderLine("X-Subscription-Token", "opaque-test-token"),
                        new BraveApiRequest.HeaderLine("Accept", "application/json"),
                        new BraveApiRequest.HeaderLine("Accept-Encoding", "gzip"),
                        new BraveApiRequest.HeaderLine("User-Agent", ExpectedUserAgent.fromVersionResource())),
                assembled.headers(),
                "the images endpoint documents no location group, so the fixed assembly is the whole header set");
    }

    @Test
    void everyUnsuppliedValueIsOmittedFromTheWire() {
        BraveApiRequest assembled =
                ImageSearchEndpoint.assemble(ImageSearchRequest.builder("café ☕").build(), PRODUCTION, token("t"));

        assertEquals("q=caf%C3%A9%20%E2%98%95", assembled.uri().getRawQuery());
        assertEquals("/res/v1/images/search", assembled.uri().getPath());
        assertEquals(4, assembled.headers().size(), "only the fixed assembly travels: " + assembled.headers());
    }

    @Test
    void theRegionWideCountrySpellingTravelsUnchanged() {
        assertEquals("country=ALL&q=x", queryOf(ImageSearchRequest.builder("x").country("ALL").build()));
    }

    @Test
    void bothDocumentedSafeSearchLevelsTravelAndModerateCannotReachTheWire() {
        assertEquals("q=x&safesearch=off", queryOf(ImageSearchRequest.builder("x").safeSearch(SafeSearch.OFF).build()));
        assertEquals(
                "q=x&safesearch=strict", queryOf(ImageSearchRequest.builder("x").safeSearch(SafeSearch.STRICT).build()));
    }

    @Test
    void anApiVersionPinRidesTheFixedAssemblyExactlyWhenSupplied() {
        BraveApiRequest pinned = ImageSearchEndpoint.assemble(
                ImageSearchRequest.builder("x").build(), PRODUCTION, token("t"), "2024-06-01");

        assertEquals(
                new BraveApiRequest.HeaderLine("Api-Version", "2024-06-01"),
                pinned.headers().get(4),
                "the pin follows the User-Agent line of the fixed assembly");

        BraveApiRequest unpinned =
                ImageSearchEndpoint.assemble(ImageSearchRequest.builder("x").build(), PRODUCTION, token("t"), null);

        assertEquals(4, unpinned.headers().size(), "no pin line travels without a pin: " + unpinned.headers());
    }

    @Test
    void theMethodStaysGetEvenBeyondTheSharedPostTriggerLength() {
        ImageSearchRequest beyond = ImageSearchRequest.builder("x")
                .searchLang("a".repeat(7970))
                .build();

        BraveApiRequest assembled = ImageSearchEndpoint.assemble(beyond, PRODUCTION, token("t"));

        assertEquals(
                BraveApiRequest.Method.GET,
                assembled.method(),
                "the images endpoint documents no POST form, so the length rule never switches methods here");
        assertTrue(assembled.uri().getRawQuery().contains("search_lang="), "the long value rides the GET query");
        assertNull(assembled.bodyBytes());
        assertFalse(assembled.headers().contains(new BraveApiRequest.HeaderLine("Content-Type", "application/json")));
    }

    private static ImageSearchRequest fullRequest() {
        return ImageSearchRequest.builder("hello world")
                .country("DE")
                .searchLang("de")
                .safeSearch(SafeSearch.STRICT)
                .count(150)
                .spellcheck(false)
                .build();
    }

    private static String queryOf(ImageSearchRequest request) {
        return ImageSearchEndpoint.assemble(request, PRODUCTION, token("t")).uri().getRawQuery();
    }

    private static Credential token(String text) {
        return Credential.of(text.getBytes(UTF_8));
    }
}

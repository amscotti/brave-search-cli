package io.amscotti.bravesearch.adapter.bravehttp.endpoint;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.BraveApiRequest;
import io.amscotti.bravesearch.application.exchange.ExpectedUserAgent;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.request.SpellcheckRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Assembly of the spellcheck wire form, pinned to the live spellcheck reference: a
 * full-parameter request renders one percent-encoded GET URI over exactly the documented
 * field inventory — {@code q}, {@code lang}, and {@code country} and nothing else — where
 * the language option travels under the spellcheck endpoint's own {@code lang} wire name
 * and the string {@code search_lang} never appears anywhere in the URI. Every unsupplied
 * value is omitted rather than coerced to a default, and the method is GET always: the
 * endpoint documents no POST form.
 */
final class SpellcheckEndpointTest {

    private static final BraveApiOrigin PRODUCTION = BraveApiOrigin.production();

    /** The pinned full-parameter rendering: every documented spellcheck wire field supplied. */
    private static final String FULL_QUERY = "country=DE&lang=de&q=hello%20world";

    @Test
    void fullParameterRequestRendersTheExactEncodedUri() throws Exception {
        BraveApiRequest assembled = SpellcheckEndpoint.assemble(fullRequest(), PRODUCTION, token("opaque-test-token"));

        assertEquals("https://api.search.brave.com/res/v1/spellcheck/search?" + FULL_QUERY, assembled.uri().toString());
        assertEquals(BraveApiRequest.Method.GET, assembled.method());
        assertNull(assembled.bodyBytes());
        assertEquals(
                List.of(
                        new BraveApiRequest.HeaderLine("X-Subscription-Token", "opaque-test-token"),
                        new BraveApiRequest.HeaderLine("Accept", "application/json"),
                        new BraveApiRequest.HeaderLine("Accept-Encoding", "gzip"),
                        new BraveApiRequest.HeaderLine("User-Agent", ExpectedUserAgent.fromVersionResource())),
                assembled.headers(),
                "the spellcheck endpoint documents no location group, so the fixed assembly is the whole header set");
    }

    @Test
    void everyUnsuppliedValueIsOmittedFromTheWire() {
        BraveApiRequest assembled =
                SpellcheckEndpoint.assemble(SpellcheckRequest.builder("café ☕").build(), PRODUCTION, token("t"));

        assertEquals("q=caf%C3%A9%20%E2%98%95", assembled.uri().getRawQuery());
        assertEquals("/res/v1/spellcheck/search", assembled.uri().getPath());
        assertEquals(4, assembled.headers().size(), "only the fixed assembly travels: " + assembled.headers());
    }

    @Test
    void theLanguageHintRidesTheSpellcheckWireNameAndNeverTheSearchOne() {
        String query = queryOf(SpellcheckRequest.builder("x").lang("de").build());

        assertEquals("lang=de&q=x", query);
        assertFalse(query.contains("search_lang"), "the search wire name must never ride a spellcheck request");
    }

    @Test
    void theRegionWideCountrySpellingTravelsUnchanged() {
        assertEquals("country=ALL&q=x", queryOf(SpellcheckRequest.builder("x").country("ALL").build()));
    }

    @Test
    void noCountOrRichOrAnySearchVerticalFieldEverRidesTheUri() {
        String query = queryOf(SpellcheckRequest.builder("x").country("US").lang("en").build());

        assertEquals("country=US&lang=en&q=x", query, "the spellcheck inventory is exactly q, lang, and country");
        assertFalse(query.contains("count="));
        assertFalse(query.contains("rich="));
    }

    @Test
    void anApiVersionPinRidesTheFixedAssemblyExactlyWhenSupplied() {
        BraveApiRequest pinned =
                SpellcheckEndpoint.assemble(SpellcheckRequest.builder("x").build(), PRODUCTION, token("t"), "2024-06-01");

        assertEquals(
                new BraveApiRequest.HeaderLine("Api-Version", "2024-06-01"),
                pinned.headers().get(4),
                "the pin follows the User-Agent line of the fixed assembly");

        BraveApiRequest unpinned =
                SpellcheckEndpoint.assemble(SpellcheckRequest.builder("x").build(), PRODUCTION, token("t"), null);

        assertEquals(4, unpinned.headers().size(), "no pin line travels without a pin: " + unpinned.headers());
    }

    private static SpellcheckRequest fullRequest() {
        return SpellcheckRequest.builder("hello world").country("DE").lang("de").build();
    }

    private static String queryOf(SpellcheckRequest request) {
        return SpellcheckEndpoint.assemble(request, PRODUCTION, token("t")).uri().getRawQuery();
    }

    private static Credential token(String text) {
        return Credential.of(text.getBytes(UTF_8));
    }
}

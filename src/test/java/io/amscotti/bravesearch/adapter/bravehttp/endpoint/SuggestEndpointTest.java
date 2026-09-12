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
import io.amscotti.bravesearch.domain.request.SuggestRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Assembly of the suggest wire form, pinned to the live suggest reference: a
 * full-parameter request renders one percent-encoded GET URI over exactly the documented
 * field inventory — {@code q}, {@code lang}, {@code country}, {@code count}, {@code
 * rich} — where the language option travels under the suggest endpoint's own {@code lang}
 * wire name and the string {@code search_lang} never appears anywhere in the URI. Every
 * unsupplied value is omitted rather than coerced to a default, so Brave's count default
 * of 5 and rich default of false stay upstream decisions, and the method is GET always:
 * the endpoint documents no POST form.
 */
final class SuggestEndpointTest {

    private static final BraveApiOrigin PRODUCTION = BraveApiOrigin.production();

    /** The pinned full-parameter rendering: every documented suggest wire field supplied. */
    private static final String FULL_QUERY = "count=20&country=DE&lang=de&q=hello%20world&rich=true";

    @Test
    void fullParameterRequestRendersTheExactEncodedUri() throws Exception {
        BraveApiRequest assembled = SuggestEndpoint.assemble(fullRequest(), PRODUCTION, token("opaque-test-token"));

        assertEquals("https://api.search.brave.com/res/v1/suggest/search?" + FULL_QUERY, assembled.uri().toString());
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
                "the suggest endpoint documents no location group, so the fixed assembly is the whole header set");
    }

    @Test
    void everyUnsuppliedValueIsOmittedFromTheWire() {
        BraveApiRequest assembled =
                SuggestEndpoint.assemble(SuggestRequest.builder("café ☕").build(), PRODUCTION, token("t"));

        assertEquals("q=caf%C3%A9%20%E2%98%95", assembled.uri().getRawQuery());
        assertEquals("/res/v1/suggest/search", assembled.uri().getPath());
        assertEquals(4, assembled.headers().size(), "only the fixed assembly travels: " + assembled.headers());
    }

    @Test
    void theLanguageHintRidesTheSuggestWireNameAndNeverTheSearchOne() {
        String query = queryOf(SuggestRequest.builder("x").lang("de").build());

        assertEquals("lang=de&q=x", query);
        assertFalse(query.contains("search_lang"), "the search wire name must never ride a suggest request");
    }

    @Test
    void theRegionWideCountrySpellingTravelsUnchanged() {
        assertEquals("country=ALL&q=x", queryOf(SuggestRequest.builder("x").country("ALL").build()));
    }

    @Test
    void bothRichStatesTravelAndUnsuppliedStaysOmitted() {
        assertEquals("q=x&rich=true", queryOf(SuggestRequest.builder("x").rich(true).build()));
        assertEquals("q=x&rich=false", queryOf(SuggestRequest.builder("x").rich(false).build()));
        assertFalse(queryOf(SuggestRequest.builder("x").build()).contains("rich="));
    }

    @Test
    void anApiVersionPinRidesTheFixedAssemblyExactlyWhenSupplied() {
        BraveApiRequest pinned =
                SuggestEndpoint.assemble(SuggestRequest.builder("x").build(), PRODUCTION, token("t"), "2024-06-01");

        assertEquals(
                new BraveApiRequest.HeaderLine("Api-Version", "2024-06-01"),
                pinned.headers().get(4),
                "the pin follows the User-Agent line of the fixed assembly");

        BraveApiRequest unpinned =
                SuggestEndpoint.assemble(SuggestRequest.builder("x").build(), PRODUCTION, token("t"), null);

        assertEquals(4, unpinned.headers().size(), "no pin line travels without a pin: " + unpinned.headers());
    }

    @Test
    void theMethodStaysGetEvenBeyondTheSharedPostTriggerLength() {
        SuggestRequest beyond = SuggestRequest.builder("x").lang("a".repeat(7970)).build();

        BraveApiRequest assembled = SuggestEndpoint.assemble(beyond, PRODUCTION, token("t"));

        assertEquals(
                BraveApiRequest.Method.GET,
                assembled.method(),
                "the suggest endpoint documents no POST form, so the length rule never switches methods here");
        assertTrue(assembled.uri().getRawQuery().contains("lang="), "the long value rides the GET query");
        assertNull(assembled.bodyBytes());
        assertFalse(assembled.headers().contains(new BraveApiRequest.HeaderLine("Content-Type", "application/json")));
    }

    private static SuggestRequest fullRequest() {
        return SuggestRequest.builder("hello world").country("DE").lang("de").count(20).rich(true).build();
    }

    private static String queryOf(SuggestRequest request) {
        return SuggestEndpoint.assemble(request, PRODUCTION, token("t")).uri().getRawQuery();
    }

    private static Credential token(String text) {
        return Credential.of(text.getBytes(UTF_8));
    }
}

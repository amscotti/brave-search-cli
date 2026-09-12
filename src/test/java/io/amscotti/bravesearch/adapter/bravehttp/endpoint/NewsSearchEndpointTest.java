package io.amscotti.bravesearch.adapter.bravehttp.endpoint;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.BraveApiRequest;
import io.amscotti.bravesearch.application.exchange.ExpectedUserAgent;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.goggles.Goggle;
import io.amscotti.bravesearch.domain.request.Freshness;
import io.amscotti.bravesearch.domain.request.NewsSearchRequest;
import io.amscotti.bravesearch.domain.request.SafeSearch;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Assembly of the news search wire form, pinned to the upstream contract's news family: a
 * full-parameter request renders one pinned percent-encoded URI over exactly the documented
 * field inventory — no web-only field ever appears — every unsupplied value is omitted
 * rather than coerced to a default, the user-facing page travels as its zero-based offset,
 * the country carries its region-wide spelling unchanged, and the GET and POST forms
 * serialize every field equivalently.
 */
final class NewsSearchEndpointTest {

    private static final BraveApiOrigin PRODUCTION = BraveApiOrigin.production();

    /** The pinned full-parameter rendering: every documented news wire field supplied. */
    private static final String FULL_QUERY = "count=7&country=DE&extra_snippets=true"
            + "&freshness=2026-01-01to2026-02-28&include_fetch_metadata=true"
            + "&offset=2&operators=false&q=hello%20world"
            + "&safesearch=off&search_lang=de&spellcheck=false&ui_lang=de-DE";

    @Test
    void fullParameterRequestRendersTheExactEncodedUri() throws Exception {
        BraveApiRequest assembled = NewsSearchEndpoint.assemble(fullRequest(), PRODUCTION, token("opaque-test-token"));

        assertEquals("https://api.search.brave.com/res/v1/news/search?" + FULL_QUERY, assembled.uri().toString());
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
                "the news endpoint documents no location group, so the fixed assembly is the whole header set");
    }

    @Test
    void everyUnsuppliedValueIsOmittedFromTheWire() {
        BraveApiRequest assembled = NewsSearchEndpoint.assemble(
                NewsSearchRequest.builder("café ☕").build(), PRODUCTION, token("t"));

        assertEquals("q=caf%C3%A9%20%E2%98%95", assembled.uri().getRawQuery());
        assertEquals("/res/v1/news/search", assembled.uri().getPath());
        assertEquals(4, assembled.headers().size(), "only the fixed assembly travels: " + assembled.headers());
    }

    @Test
    void theUserFacingPageTravelsAsItsZeroBasedOffset() {
        assertEquals("offset=0&q=x", queryOf(NewsSearchRequest.builder("x").page(1).build()));
        assertEquals("offset=9&q=x", queryOf(NewsSearchRequest.builder("x").page(10).build()));
    }

    @Test
    void theRegionWideCountrySpellingTravelsUnchanged() {
        assertEquals("country=ALL&q=x", queryOf(NewsSearchRequest.builder("x").country("ALL").build()));
    }

    @Test
    void anApiVersionPinRidesTheFixedAssemblyExactlyWhenSupplied() {
        BraveApiRequest pinned = NewsSearchEndpoint.assemble(
                NewsSearchRequest.builder("x").build(), PRODUCTION, token("t"), "2024-06-01");

        assertEquals(
                new BraveApiRequest.HeaderLine("Api-Version", "2024-06-01"),
                pinned.headers().get(4),
                "the pin follows the User-Agent line of the fixed assembly");

        BraveApiRequest unpinned =
                NewsSearchEndpoint.assemble(NewsSearchRequest.builder("x").build(), PRODUCTION, token("t"), null);

        assertEquals(4, unpinned.headers().size(), "no pin line travels without a pin: " + unpinned.headers());
    }

    @Test
    void urlReferenceGogglesStayOnGetAsRepeatedParametersInGivenOrder() {
        BraveApiRequest assembled = NewsSearchEndpoint.assemble(
                NewsSearchRequest.builder("x")
                        .goggles(List.of(
                                new Goggle.UrlReference("https://example.com/first"),
                                new Goggle.UrlReference("http://example.org/second")))
                        .build(),
                PRODUCTION,
                token("t"));

        assertEquals(BraveApiRequest.Method.GET, assembled.method());
        assertNull(assembled.bodyBytes());
        assertEquals(
                "goggles=https%3A%2F%2Fexample.com%2Ffirst&goggles=http%3A%2F%2Fexample.org%2Fsecond&q=x",
                assembled.uri().getRawQuery());
    }

    @Test
    void anInlineGoggleForcesThePostFormWithTheGogglesArrayInTheBody() throws Exception {
        BraveApiRequest assembled = NewsSearchEndpoint.assemble(
                NewsSearchRequest.builder("hello world")
                        .goggles(List.of(
                                new Goggle.UrlReference("https://example.com/ref"),
                                new Goggle.Inline("!name: local\n+site:example.com")))
                        .build(),
                PRODUCTION,
                token("t"));

        assertEquals(BraveApiRequest.Method.POST, assembled.method());
        assertNull(assembled.uri().getRawQuery(), "the POST form carries no query parameters");
        JsonNode body = new ObjectMapper().readTree(assembled.bodyBytes());
        assertEquals("hello world", body.path("q").asText());
        assertEquals(2, body.path("goggles").size(), "every goggle rides the body array in its given order");
        assertEquals("https://example.com/ref", body.path("goggles").get(0).asText());
        assertEquals("!name: local\n+site:example.com", body.path("goggles").get(1).asText());
        assertTrue(
                assembled.headers().contains(new BraveApiRequest.HeaderLine("Content-Type", "application/json")),
                "the JSON body declares its type exactly");
    }

    @Test
    void aGetUriBeyondEightThousandBytesSwitchesToPostEvenWithoutGoggles() throws Exception {
        // the fixed URI characters plus one long freshness-style value land beyond the bound
        NewsSearchRequest beyond = NewsSearchRequest.builder("x")
                .searchLang("a".repeat(7970))
                .build();
        BraveApiRequest switched = NewsSearchEndpoint.assemble(beyond, PRODUCTION, token("t"));

        assertEquals(BraveApiRequest.Method.POST, switched.method());
        assertNull(switched.uri().getRawQuery());
        JsonNode body = new ObjectMapper().readTree(switched.bodyBytes());
        assertEquals("a".repeat(7970), body.path("search_lang").asText());
        assertEquals("x", body.path("q").asText());
        assertTrue(body.path("goggles").isMissingNode(), "no goggles member rides a goggle-free request");
    }

    @Test
    void getAndPostSerializeEveryFieldEquivalently() throws Exception {
        NewsSearchRequest shared = fullRequest();
        Map<String, String> getFields = decodedQueryOf(NewsSearchEndpoint.assemble(shared, PRODUCTION, token("t")));

        NewsSearchRequest withInlineGoggle = NewsSearchRequest.builder(shared.query())
                .country(shared.country())
                .searchLang(shared.searchLang())
                .uiLang(shared.uiLang())
                .safeSearch(shared.safeSearch())
                .freshness(shared.freshness())
                .count(shared.count())
                .page(shared.page())
                .spellcheck(shared.spellcheck())
                .extraSnippets(shared.extraSnippets())
                .includeFetchMetadata(shared.includeFetchMetadata())
                .operators(shared.operators())
                .goggles(List.of(new Goggle.Inline("+site:example.com")))
                .build();
        JsonNode body = new ObjectMapper()
                .readTree(NewsSearchEndpoint.assemble(withInlineGoggle, PRODUCTION, token("t")).bodyBytes());

        Map<String, String> postFields = new LinkedHashMap<>();
        body.properties()
                .forEach(
                        property -> {
                            if (!"goggles".equals(property.getKey())) {
                                postFields.put(property.getKey(), property.getValue().asText());
                            }
                        });
        assertEquals(
                getFields.keySet(),
                postFields.keySet(),
                "the POST body carries exactly the GET fields plus the goggles member");
        for (Map.Entry<String, String> field : getFields.entrySet()) {
            assertEquals(
                    field.getValue(),
                    postFields.get(field.getKey()),
                    () -> "field " + field.getKey() + " must serialize identically on both methods");
        }
        assertEquals(1, body.path("goggles").size());
        assertEquals("+site:example.com", body.path("goggles").get(0).asText());

        // the method-inherent encodings stay native JSON: numbers and booleans, not strings
        assertTrue(body.path("count").isNumber());
        assertTrue(body.path("offset").isNumber());
        assertTrue(body.path("spellcheck").isBoolean());
        assertTrue(body.path("extra_snippets").isBoolean());
    }

    private static Map<String, String> decodedQueryOf(BraveApiRequest assembled) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : assembled.uri().getRawQuery().split("&")) {
            int separator = pair.indexOf('=');
            String name = java.net.URLDecoder.decode(pair.substring(0, separator), UTF_8);
            String value = java.net.URLDecoder.decode(pair.substring(separator + 1), UTF_8);
            assertTrue(fields.putIfAbsent(name, value) == null, "one value per field name: " + name);
        }
        return fields;
    }

    private static NewsSearchRequest fullRequest() {
        return NewsSearchRequest.builder("hello world")
                .country("DE")
                .searchLang("de")
                .uiLang("de-DE")
                .safeSearch(SafeSearch.OFF)
                .freshness(new Freshness.DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28)))
                .count(7)
                .page(3)
                .spellcheck(false)
                .extraSnippets(true)
                .includeFetchMetadata(true)
                .operators(false)
                .build();
    }

    private static String queryOf(NewsSearchRequest request) {
        return NewsSearchEndpoint.assemble(request, PRODUCTION, token("t")).uri().getRawQuery();
    }

    private static Credential token(String text) {
        return Credential.of(text.getBytes(UTF_8));
    }
}

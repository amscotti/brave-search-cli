package io.amscotti.bravesearch.adapter.bravehttp.endpoint;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.BraveApiRequest;
import io.amscotti.bravesearch.application.exchange.ExpectedUserAgent;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.UsageValidationError;
import io.amscotti.bravesearch.domain.goggles.Goggle;
import io.amscotti.bravesearch.domain.request.Freshness;
import io.amscotti.bravesearch.domain.request.SafeSearch;
import io.amscotti.bravesearch.domain.request.Units;
import io.amscotti.bravesearch.domain.request.WebSearchRequest;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Assembly of the web search wire form: a full-parameter request renders one pinned
 * percent-encoded URI and one pinned header assembly (query parameters alphabetical by wire
 * name with the comma-joined filter value in its given order, the location group alphabetical
 * by header name), every unsupplied value is omitted rather than coerced to a default, the
 * user-facing page travels as its zero-based offset, and free-text location values that could
 * not ride an HTTP header line are typed usage rejections.
 */
final class WebSearchEndpointTest {

    private static final BraveApiOrigin PRODUCTION = BraveApiOrigin.production();

    /** The pinned full-parameter rendering: every §4.1 wire field and X-Loc header supplied. */
    private static final String FULL_QUERY = "count=7&country=DE&enable_rich_callback=true&extra_snippets=true"
            + "&freshness=2026-01-01to2026-02-28&include_fetch_metadata=true"
            + "&offset=2&operators=false&q=hello%20world&result_filter=discussions%2Cvideos"
            + "&safesearch=strict&search_lang=de&spellcheck=false&text_decorations=false"
            + "&ui_lang=de-DE&units=imperial";

    @Test
    void fullParameterRequestRendersTheExactEncodedUriAndHeaderAssembly() throws Exception {
        BraveApiRequest assembled = WebSearchEndpoint.assemble(fullRequest(), PRODUCTION, token("opaque-test-token"));

        assertEquals(
                "https://api.search.brave.com/res/v1/web/search?" + FULL_QUERY, assembled.uri().toString());
        assertEquals(BraveApiRequest.Method.GET, assembled.method());
        assertNull(assembled.bodyBytes());
        assertFalse(assembled.rawResponseMode());
        assertEquals(
                List.of(
                        new BraveApiRequest.HeaderLine("X-Subscription-Token", "opaque-test-token"),
                        new BraveApiRequest.HeaderLine("Accept", "application/json"),
                        new BraveApiRequest.HeaderLine("Accept-Encoding", "gzip"),
                        new BraveApiRequest.HeaderLine("User-Agent", ExpectedUserAgent.fromVersionResource()),
                        new BraveApiRequest.HeaderLine("X-Loc-City", "Seattle"),
                        new BraveApiRequest.HeaderLine("X-Loc-Country", "US"),
                        new BraveApiRequest.HeaderLine("X-Loc-Lat", "47.6062"),
                        new BraveApiRequest.HeaderLine("X-Loc-Long", "-122.3321"),
                        new BraveApiRequest.HeaderLine("X-Loc-Postal-Code", "98101"),
                        new BraveApiRequest.HeaderLine("X-Loc-State", "WA"),
                        new BraveApiRequest.HeaderLine("X-Loc-State-Name", "Washington"),
                        new BraveApiRequest.HeaderLine("X-Loc-Timezone", "America/Los_Angeles")),
                assembled.headers());
    }

    @Test
    void everyUnsuppliedValueIsOmittedFromTheWire() {
        BraveApiRequest assembled = WebSearchEndpoint.assemble(
                WebSearchRequest.builder("café ☕").build(), PRODUCTION, token("t"));

        assertEquals("q=caf%C3%A9%20%E2%98%95", assembled.uri().getRawQuery());
        assertEquals("/res/v1/web/search", assembled.uri().getPath());
        assertEquals(4, assembled.headers().size(), "only the fixed assembly travels: " + assembled.headers());
    }

    @Test
    void anApiVersionPinRidesTheFixedAssemblyExactlyWhenSupplied() {
        BraveApiRequest pinned = WebSearchEndpoint.assemble(
                WebSearchRequest.builder("x").build(), PRODUCTION, token("t"), "2024-06-01");

        assertEquals(
                new BraveApiRequest.HeaderLine("Api-Version", "2024-06-01"),
                pinned.headers().get(4),
                "the pin follows the User-Agent line of the fixed assembly");

        BraveApiRequest unpinned = WebSearchEndpoint.assemble(WebSearchRequest.builder("x")
                .build(), PRODUCTION, token("t"), null);

        assertEquals(4, unpinned.headers().size(), "no pin line travels without a pin: " + unpinned.headers());
    }

    @Test
    void theUserFacingPageTravelsAsItsZeroBasedOffset() {
        assertEquals("offset=0&q=x", queryOf(WebSearchRequest.builder("x").page(1).build()));
        assertEquals("offset=9&q=x", queryOf(WebSearchRequest.builder("x").page(10).build()));
    }

    @Test
    void resultFilterValuesJoinIntoOneCommaSeparatedParameterInGivenOrder() {
        assertEquals(
                "q=x&result_filter=videos%2Cdiscussions",
                queryOf(WebSearchRequest.builder("x").resultFilters(List.of("videos", "discussions")).build()));
        assertEquals(
                "q=x",
                queryOf(WebSearchRequest.builder("x").resultFilters(List.of()).build()),
                "an empty filter list is omitted entirely");
    }

    @Test
    void headerUnsafeLocationValuesAreUsageRejections() {
        for (String unsafe : new String[] {
            "injected\r\nX-Evil: 1", "linefeed\n", "nul\u0000", "vertical\u000Btab", " leading", "trailing ", "tab\tboth"
        }) {
            assertThrows(
                    UsageValidationError.class,
                    () -> WebSearchEndpoint.assemble(
                            WebSearchRequest.builder("x")
                                    .location(new WebSearchRequest.Location(
                                            null, null, unsafe, null, null, null, null, null))
                                    .build(),
                            PRODUCTION,
                            token("t")),
                    "city value must be rejected: " + unsafe);
        }
        assertThrows(
                UsageValidationError.class,
                () -> WebSearchEndpoint.assemble(
                        WebSearchRequest.builder("x")
                                .location(new WebSearchRequest.Location(
                                        null, null, null, null, "state\rname", null, null, null))
                                .build(),
                        PRODUCTION,
                        token("t")));
        assertThrows(
                UsageValidationError.class,
                () -> WebSearchEndpoint.assemble(
                        WebSearchRequest.builder("x")
                                .location(new WebSearchRequest.Location(
                                        null, null, null, null, null, null, "981\u0001", null))
                                .build(),
                        PRODUCTION,
                        token("t")));
    }

    @Test
    void urlReferenceGogglesStayOnGetAsRepeatedParametersInGivenOrder() {
        BraveApiRequest assembled = WebSearchEndpoint.assemble(
                WebSearchRequest.builder("x")
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
        BraveApiRequest assembled = WebSearchEndpoint.assemble(
                WebSearchRequest.builder("hello world")
                        .goggles(List.of(
                                new Goggle.UrlReference("https://example.com/ref"),
                                new Goggle.Inline("!name: local\n+site:example.com\trule")))
                        .build(),
                PRODUCTION,
                token("t"));

        assertEquals(BraveApiRequest.Method.POST, assembled.method());
        assertNull(assembled.uri().getRawQuery(), "the POST form carries no query parameters");
        JsonNode body = new ObjectMapper().readTree(assembled.bodyBytes());
        assertEquals("hello world", body.path("q").asText());
        assertEquals(2, body.path("goggles").size(), "every goggle rides the body array in its given order");
        assertEquals("https://example.com/ref", body.path("goggles").get(0).asText());
        assertEquals("!name: local\n+site:example.com\trule", body.path("goggles").get(1).asText());
        assertTrue(
                assembled.headers().contains(new BraveApiRequest.HeaderLine("Content-Type", "application/json")),
                "the JSON body declares its type exactly");
        assertTrue(
                assembled.headers().contains(new BraveApiRequest.HeaderLine("Accept", "application/json")),
                "the accept line stays unchanged on POST");
    }

    @Test
    void aGetUriBeyondEightThousandBytesSwitchesToPostEvenWithoutGoggles() {
        // 65 fixed characters of URI plus two joined filters of 3966 characters plus the
        // encoded comma (%2C) land on exactly 8000 bytes: still GET
        WebSearchRequest atTheBound = WebSearchRequest.builder("x")
                .resultFilters(List.of("a".repeat(3966), "b".repeat(3966)))
                .build();
        assertEquals(
                BraveApiRequest.Method.GET,
                WebSearchEndpoint.assemble(atTheBound, PRODUCTION, token("t")).method());

        WebSearchRequest beyond = WebSearchRequest.builder("x")
                .resultFilters(List.of("a".repeat(3967), "b".repeat(3967)))
                .build();
        BraveApiRequest switched = WebSearchEndpoint.assemble(beyond, PRODUCTION, token("t"));

        assertEquals(BraveApiRequest.Method.POST, switched.method());
        assertNull(switched.uri().getRawQuery());
        try {
            JsonNode body = new ObjectMapper().readTree(switched.bodyBytes());
            assertEquals("a".repeat(3967) + "," + "b".repeat(3967), body.path("result_filter").asText());
            assertEquals("x", body.path("q").asText());
            assertTrue(body.path("goggles").isMissingNode(), "no goggles member rides a goggle-free request");
        } catch (Exception unreadable) {
            throw new IllegalStateException("the POST body must be one JSON document", unreadable);
        }
    }

    @Test
    void getAndPostSerializeEveryFieldEquivalently() throws Exception {
        WebSearchRequest shared = fullRequest();
        Map<String, String> getFields = decodedQueryOf(WebSearchEndpoint.assemble(shared, PRODUCTION, token("t")));

        WebSearchRequest withInlineGoggle = WebSearchRequest.builder(shared.query())
                .country(shared.country())
                .searchLang(shared.searchLang())
                .uiLang(shared.uiLang())
                .safeSearch(shared.safeSearch())
                .freshness(shared.freshness())
                .count(shared.count())
                .page(shared.page())
                .spellcheck(shared.spellcheck())
                .textDecorations(shared.textDecorations())
                .resultFilters(shared.resultFilters())
                .units(shared.units())
                .extraSnippets(shared.extraSnippets())
                .includeFetchMetadata(shared.includeFetchMetadata())
                .operators(shared.operators())
                .enableRichCallback(shared.enableRichCallback())
                .goggles(List.of(new Goggle.Inline("+site:example.com")))
                .location(shared.location())
                .build();
        JsonNode body = new ObjectMapper()
                .readTree(WebSearchEndpoint.assemble(withInlineGoggle, PRODUCTION, token("t")).bodyBytes());

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
    }

    private static Map<String, String> decodedQueryOf(BraveApiRequest assembled) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : assembled.uri().getRawQuery().split("&")) {
            int separator = pair.indexOf('=');
            String name = java.net.URLDecoder.decode(pair.substring(0, separator), UTF_8);
            String value = java.net.URLDecoder.decode(pair.substring(separator + 1), UTF_8);
            assertTrue(fields.putIfAbsent(name, value) == null, "one value per field name: " + name);
        }
        return fields;
    }

    private static WebSearchRequest fullRequest() {
        return WebSearchRequest.builder("hello world")
                .country("DE")
                .searchLang("de")
                .uiLang("de-DE")
                .safeSearch(SafeSearch.STRICT)
                .freshness(new Freshness.DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28)))
                .count(7)
                .page(3)
                .spellcheck(false)
                .textDecorations(false)
                .resultFilters(List.of("discussions", "videos"))
                .units(Units.IMPERIAL)
                .extraSnippets(true)
                .includeFetchMetadata(true)
                .operators(false)
                .enableRichCallback(true)
                .location(new WebSearchRequest.Location(
                        47.6062,
                        -122.3321,
                        "Seattle",
                        "WA",
                        "Washington",
                        "US",
                        "98101",
                        "America/Los_Angeles"))
                .build();
    }

    private static String queryOf(WebSearchRequest request) {
        return WebSearchEndpoint.assemble(request, PRODUCTION, token("t")).uri().getRawQuery();
    }

    private static Credential token(String text) {
        return Credential.of(text.getBytes(UTF_8));
    }
}

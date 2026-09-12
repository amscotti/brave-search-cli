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
import io.amscotti.bravesearch.domain.request.Freshness;
import io.amscotti.bravesearch.domain.request.SafeSearch;
import io.amscotti.bravesearch.domain.request.VideoSearchRequest;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Assembly of the videos search wire form, pinned to the upstream contract's videos
 * family: a full-parameter request renders one pinned percent-encoded URI over exactly
 * the documented field inventory — no goggles, no extra snippets, and no web-only field
 * ever appears — every unsupplied value is omitted rather than coerced to a default, the
 * user-facing page travels as its zero-based offset, the country carries its region-wide
 * spelling unchanged, and the GET and POST forms serialize every field equivalently. The
 * endpoint documents no inline-Goggle trigger, so the URI-length rule alone owns the POST
 * switch.
 */
final class VideoSearchEndpointTest {

    private static final BraveApiOrigin PRODUCTION = BraveApiOrigin.production();

    /** The pinned full-parameter rendering: every documented videos wire field supplied. */
    private static final String FULL_QUERY = "count=7&country=DE&freshness=2026-01-01to2026-02-28"
            + "&include_fetch_metadata=true&offset=2&operators=false&q=hello%20world"
            + "&safesearch=off&search_lang=de&spellcheck=false&ui_lang=de-DE";

    @Test
    void fullParameterRequestRendersTheExactEncodedUri() throws Exception {
        BraveApiRequest assembled = VideoSearchEndpoint.assemble(fullRequest(), PRODUCTION, token("opaque-test-token"));

        assertEquals("https://api.search.brave.com/res/v1/videos/search?" + FULL_QUERY, assembled.uri().toString());
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
                "the videos endpoint documents no location group, so the fixed assembly is the whole header set");
    }

    @Test
    void everyUnsuppliedValueIsOmittedFromTheWire() {
        BraveApiRequest assembled = VideoSearchEndpoint.assemble(
                VideoSearchRequest.builder("café ☕").build(), PRODUCTION, token("t"));

        assertEquals("q=caf%C3%A9%20%E2%98%95", assembled.uri().getRawQuery());
        assertEquals("/res/v1/videos/search", assembled.uri().getPath());
        assertEquals(4, assembled.headers().size(), "only the fixed assembly travels: " + assembled.headers());
    }

    @Test
    void theUserFacingPageTravelsAsItsZeroBasedOffset() {
        assertEquals("offset=0&q=x", queryOf(VideoSearchRequest.builder("x").page(1).build()));
        assertEquals("offset=9&q=x", queryOf(VideoSearchRequest.builder("x").page(10).build()));
    }

    @Test
    void theRegionWideCountrySpellingTravelsUnchanged() {
        assertEquals("country=ALL&q=x", queryOf(VideoSearchRequest.builder("x").country("ALL").build()));
    }

    @Test
    void anApiVersionPinRidesTheFixedAssemblyExactlyWhenSupplied() {
        BraveApiRequest pinned = VideoSearchEndpoint.assemble(
                VideoSearchRequest.builder("x").build(), PRODUCTION, token("t"), "2024-06-01");

        assertEquals(
                new BraveApiRequest.HeaderLine("Api-Version", "2024-06-01"),
                pinned.headers().get(4),
                "the pin follows the User-Agent line of the fixed assembly");

        BraveApiRequest unpinned =
                VideoSearchEndpoint.assemble(VideoSearchRequest.builder("x").build(), PRODUCTION, token("t"), null);

        assertEquals(4, unpinned.headers().size(), "no pin line travels without a pin: " + unpinned.headers());
    }

    @Test
    void aGetUriBeyondEightThousandBytesSwitchesToPost() throws Exception {
        // the fixed URI characters plus one long freshness-style value land beyond the bound
        VideoSearchRequest beyond = VideoSearchRequest.builder("x")
                .searchLang("a".repeat(7970))
                .build();
        BraveApiRequest switched = VideoSearchEndpoint.assemble(beyond, PRODUCTION, token("t"));

        assertEquals(BraveApiRequest.Method.POST, switched.method());
        assertNull(switched.uri().getRawQuery(), "the POST form carries no query parameters");
        JsonNode body = new ObjectMapper().readTree(switched.bodyBytes());
        assertEquals("a".repeat(7970), body.path("search_lang").asText());
        assertEquals("x", body.path("q").asText());
        assertTrue(switched.headers().contains(new BraveApiRequest.HeaderLine("Content-Type", "application/json")));
    }

    @Test
    void getAndPostSerializeEveryFieldEquivalently() throws Exception {
        VideoSearchRequest shared = fullRequest();
        Map<String, String> getFields = decodedQueryOf(VideoSearchEndpoint.assemble(shared, PRODUCTION, token("t")));

        // the POST body is reached through the length rule: the shared request with one long
        // language value keeps every other member identical and crosses the 8,000-byte bound
        VideoSearchRequest lengthened = VideoSearchRequest.builder(shared.query())
                .country(shared.country())
                .searchLang("a".repeat(7970))
                .uiLang(shared.uiLang())
                .safeSearch(shared.safeSearch())
                .freshness(shared.freshness())
                .count(shared.count())
                .page(shared.page())
                .spellcheck(shared.spellcheck())
                .includeFetchMetadata(shared.includeFetchMetadata())
                .operators(shared.operators())
                .build();
        BraveApiRequest switched = VideoSearchEndpoint.assemble(lengthened, PRODUCTION, token("t"));
        assertEquals(BraveApiRequest.Method.POST, switched.method(), "the lengthened request rides the POST form");
        JsonNode body = new ObjectMapper().readTree(switched.bodyBytes());

        Map<String, String> postFields = new LinkedHashMap<>();
        body.properties().forEach(property -> postFields.put(property.getKey(), property.getValue().asText()));
        postFields.remove("search_lang");
        Map<String, String> expectedFields = new LinkedHashMap<>(getFields);
        expectedFields.remove("search_lang");
        assertEquals(
                expectedFields.keySet(),
                postFields.keySet(),
                "the POST body carries exactly the GET fields, no goggles member anywhere");
        for (Map.Entry<String, String> field : expectedFields.entrySet()) {
            assertEquals(
                    field.getValue(),
                    postFields.get(field.getKey()),
                    () -> "field " + field.getKey() + " must serialize identically on both methods");
        }

        // the method-inherent encodings stay native JSON: numbers and booleans, not strings
        assertTrue(body.path("count").isNumber());
        assertTrue(body.path("offset").isNumber());
        assertTrue(body.path("spellcheck").isBoolean());
        assertTrue(body.path("include_fetch_metadata").isBoolean());
        assertTrue(body.path("operators").isBoolean());
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

    private static VideoSearchRequest fullRequest() {
        return VideoSearchRequest.builder("hello world")
                .country("DE")
                .searchLang("de")
                .uiLang("de-DE")
                .safeSearch(SafeSearch.OFF)
                .freshness(new Freshness.DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28)))
                .count(7)
                .page(3)
                .spellcheck(false)
                .includeFetchMetadata(true)
                .operators(false)
                .build();
    }

    private static String queryOf(VideoSearchRequest request) {
        return VideoSearchEndpoint.assemble(request, PRODUCTION, token("t")).uri().getRawQuery();
    }

    private static Credential token(String text) {
        return Credential.of(text.getBytes(UTF_8));
    }
}

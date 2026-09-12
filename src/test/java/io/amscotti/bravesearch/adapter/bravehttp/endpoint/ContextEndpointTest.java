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
import io.amscotti.bravesearch.domain.request.ContextRequest;
import io.amscotti.bravesearch.domain.request.ContextThreshold;
import io.amscotti.bravesearch.domain.request.Freshness;
import io.amscotti.bravesearch.domain.request.SafeSearch;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Assembly of the LLM context wire form: a full-parameter request renders one pinned
 * percent-encoded URI and one pinned header assembly — query parameters alphabetical by
 * wire name under the endpoint's documented spellings, the seven documented X-Loc headers
 * alphabetical with no timezone member — every unsupplied value is omitted rather than
 * coerced to a Brave default, the encoded-URI-over-8000-bytes rule switches to the POST
 * body with the same field inventory, and free-text location values that could not ride an
 * HTTP header line are typed usage rejections.
 */
final class ContextEndpointTest {

    private static final BraveApiOrigin PRODUCTION = BraveApiOrigin.production();

    /** The pinned full-parameter rendering: every documented wire field and X-Loc header supplied. */
    private static final String FULL_QUERY = "context_threshold_mode=balanced&count=7&country=DE&enable_local=false"
            + "&enable_source_metadata=true&freshness=2026-01-01to2026-02-28"
            + "&maximum_number_of_snippets=100&maximum_number_of_snippets_per_url=50"
            + "&maximum_number_of_tokens=4096&maximum_number_of_tokens_per_url=2048"
            + "&maximum_number_of_urls=10&q=hello%20world&safesearch=strict&search_lang=de";

    @Test
    void fullParameterRequestRendersTheExactEncodedUriAndHeaderAssembly() throws Exception {
        BraveApiRequest assembled =
                ContextEndpoint.assemble(fullRequest(), PRODUCTION, token("opaque-test-token"));

        assertEquals("https://api.search.brave.com/res/v1/llm/context?" + FULL_QUERY, assembled.uri().toString());
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
                        new BraveApiRequest.HeaderLine("X-Loc-State-Name", "Washington")),
                assembled.headers());
        assertFalse(
                assembled.headers().stream().anyMatch(line -> line.name().contains("Timezone")),
                "the context location group carries no timezone header");
    }

    @Test
    void everyUnsuppliedValueIsOmittedFromTheWire() {
        BraveApiRequest assembled =
                ContextEndpoint.assemble(ContextRequest.builder("café ☕").build(), PRODUCTION, token("t"));

        assertEquals("q=caf%C3%A9%20%E2%98%95", assembled.uri().getRawQuery());
        assertEquals("/res/v1/llm/context", assembled.uri().getPath());
        assertEquals(4, assembled.headers().size(), "only the fixed assembly travels: " + assembled.headers());
    }

    @Test
    void anApiVersionPinRidesTheFixedAssemblyExactlyWhenSupplied() {
        BraveApiRequest pinned = ContextEndpoint.assemble(
                ContextRequest.builder("x").build(), PRODUCTION, token("t"), "2024-06-01");

        assertEquals(
                new BraveApiRequest.HeaderLine("Api-Version", "2024-06-01"),
                pinned.headers().get(4),
                "the pin follows the User-Agent line of the fixed assembly");

        BraveApiRequest unpinned =
                ContextEndpoint.assemble(ContextRequest.builder("x").build(), PRODUCTION, token("t"), null);

        assertEquals(4, unpinned.headers().size(), "no pin line travels without a pin: " + unpinned.headers());
    }

    @Test
    void headerUnsafeLocationValuesAreUsageRejections() {
        for (String unsafe : new String[] {
            "injected\r\nX-Evil: 1", "linefeed\n", "nul\u0000", "vertical\u000Btab", " leading", "trailing ", "tab\tboth"
        }) {
            assertThrows(
                    UsageValidationError.class,
                    () -> ContextEndpoint.assemble(
                            ContextRequest.builder("x")
                                    .location(new ContextRequest.Location(
                                            null, null, unsafe, null, null, null, null))
                                    .build(),
                            PRODUCTION,
                            token("t")),
                    "city value must be rejected: " + unsafe);
        }
        assertThrows(
                UsageValidationError.class,
                () -> ContextEndpoint.assemble(
                        ContextRequest.builder("x")
                                .location(new ContextRequest.Location(
                                        null, null, null, null, "state\rname", null, null))
                                .build(),
                        PRODUCTION,
                        token("t")));
        assertThrows(
                UsageValidationError.class,
                () -> ContextEndpoint.assemble(
                        ContextRequest.builder("x")
                                .location(new ContextRequest.Location(
                                        null, null, null, null, null, null, "981\u0001"))
                                .build(),
                        PRODUCTION,
                        token("t")));
    }

    @Test
    void aGetUriBeyondEightThousandBytesSwitchesToPostWithTheSameFields() throws Exception {
        // 47 fixed URI characters plus "q=x&search_lang=" (17) plus N plain letters: N = 7936
        // lands on exactly 8000 bytes and stays GET; one more byte switches to POST
        ContextRequest atTheBound = ContextRequest.builder("x")
                .searchLang("a".repeat(7936))
                .build();
        assertEquals(BraveApiRequest.Method.GET, ContextEndpoint.assemble(atTheBound, PRODUCTION, token("t")).method());

        ContextRequest beyond = ContextRequest.builder("x")
                .searchLang("a".repeat(7937))
                .build();
        BraveApiRequest switched = ContextEndpoint.assemble(beyond, PRODUCTION, token("t"));

        assertEquals(BraveApiRequest.Method.POST, switched.method());
        assertNull(switched.uri().getRawQuery(), "the POST form carries no query parameters");
        JsonNode body = new ObjectMapper().readTree(switched.bodyBytes());
        assertEquals("x", body.path("q").asText());
        assertEquals("a".repeat(7937), body.path("search_lang").asText());
        assertTrue(
                switched.headers().contains(new BraveApiRequest.HeaderLine("Content-Type", "application/json")),
                "the JSON body declares its type exactly");
        assertTrue(
                switched.headers().contains(new BraveApiRequest.HeaderLine("Accept", "application/json")),
                "the accept line stays unchanged on POST");
    }

    @Test
    void getAndPostSerializeEveryFieldEquivalently() throws Exception {
        ContextRequest shared = fullRequest();
        Map<String, String> getFields = decodedQueryOf(ContextEndpoint.assemble(shared, PRODUCTION, token("t")));

        ContextRequest forcedPost = fullRequestWithSearchLang("a".repeat(7937));
        JsonNode body = new ObjectMapper()
                .readTree(ContextEndpoint.assemble(forcedPost, PRODUCTION, token("t")).bodyBytes());

        Map<String, String> postFields = new LinkedHashMap<>();
        body.properties()
                .forEach(property -> postFields.put(property.getKey(), property.getValue().asText()));

        assertEquals(getFields.keySet(), postFields.keySet(), "the two methods carry exactly the same fields");
        for (Map.Entry<String, String> field : getFields.entrySet()) {
            if (!"search_lang".equals(field.getKey())) {
                assertEquals(
                        field.getValue(),
                        postFields.get(field.getKey()),
                        () -> "field " + field.getKey() + " must serialize identically on both methods");
            }
        }

        // the method-inherent encodings stay native JSON: numbers and booleans, not strings
        assertTrue(body.path("count").isNumber());
        assertTrue(body.path("maximum_number_of_tokens").isNumber());
        assertTrue(body.path("enable_local").isBoolean());
        assertTrue(body.path("enable_source_metadata").isBoolean());
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

    private static ContextRequest fullRequest() {
        return fullRequestWithSearchLang("de");
    }

    private static ContextRequest fullRequestWithSearchLang(String searchLang) {
        return ContextRequest.builder("hello world")
                .country("DE")
                .searchLang(searchLang)
                .safeSearch(SafeSearch.STRICT)
                .freshness(new Freshness.DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28)))
                .count(7)
                .maxUrls(10)
                .maxTokens(4096)
                .maxSnippets(100)
                .maxTokensPerUrl(2048)
                .maxSnippetsPerUrl(50)
                .threshold(ContextThreshold.BALANCED)
                .sourceMetadata(true)
                .enableLocal(false)
                .location(new ContextRequest.Location(
                        47.6062, -122.3321, "Seattle", "WA", "Washington", "US", "98101"))
                .build();
    }

    private static Credential token(String text) {
        return Credential.of(text.getBytes(UTF_8));
    }
}

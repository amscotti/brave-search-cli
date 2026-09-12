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
import io.amscotti.bravesearch.domain.request.RichRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Assembly of the rich callback wire form: one GET over {@code /web/rich} whose single
 * query parameter is {@code callback_key} — never the search verticals' {@code q}, the
 * obsolete spelling this CLI must never inherit. The key is opaque, so it rides the
 * query percent-encoded independently under strict RFC 3986 rules, and no other
 * parameter, default, or coercion ever joins it, because the endpoint documents none.
 */
final class RichEndpointTest {

    private static final BraveApiOrigin PRODUCTION = BraveApiOrigin.production();

    @Test
    void theCallbackKeyRidesTheCallbackKeyWireNameAndNeverQueryQ() {
        String query = queryOf(new RichRequest("cb-7f3a2b"));

        assertEquals("callback_key=cb-7f3a2b", query);
        assertFalse(query.startsWith("q="), "the rich lookup is not a query search");
        assertFalse(query.contains("&q="), "no search query parameter may ride a rich request");
    }

    @Test
    void anOpaqueKeyIsPercentEncodedIndependentlyAndStaysOneParameter() {
        String query = queryOf(new RichRequest("cb/3f9d2A==&q=1 caf\u00e9"));

        assertEquals("callback_key=cb%2F3f9d2A%3D%3D%26q%3D1%20caf%C3%A9", query);
        assertFalse(query.substring("callback_key=".length()).contains("&"), "reserved bytes must never split the key");
    }

    @Test
    void theRequestIsOneGetOverTheWebRichPath() throws Exception {
        BraveApiRequest assembled = RichEndpoint.assemble(new RichRequest("cb-7f3a2b"), PRODUCTION, token("t"));

        assertEquals("https://api.search.brave.com/res/v1/web/rich?callback_key=cb-7f3a2b", assembled.uri().toString());
        assertEquals(BraveApiRequest.Method.GET, assembled.method());
        assertNull(assembled.bodyBytes());
        assertFalse(assembled.rawResponseMode());
        assertEquals(
                List.of(
                        new BraveApiRequest.HeaderLine("X-Subscription-Token", "t"),
                        new BraveApiRequest.HeaderLine("Accept", "application/json"),
                        new BraveApiRequest.HeaderLine("Accept-Encoding", "gzip"),
                        new BraveApiRequest.HeaderLine("User-Agent", ExpectedUserAgent.fromVersionResource())),
                assembled.headers(),
                "the rich endpoint documents no location group, so the fixed assembly is the whole header set");
    }

    @Test
    void theMethodStaysGetEvenBeyondTheSharedPostTriggerLength() {
        RichRequest beyond = new RichRequest("k".repeat(9000));

        BraveApiRequest assembled = RichEndpoint.assemble(beyond, PRODUCTION, token("t"));

        assertEquals(
                BraveApiRequest.Method.GET,
                assembled.method(),
                "the rich endpoint documents GET only, so the length rule never switches methods here");
        assertTrue(assembled.uri().getRawQuery().contains("callback_key="), "the long key rides the GET query");
        assertNull(assembled.bodyBytes());
    }

    @Test
    void anApiVersionPinRidesTheFixedAssemblyExactlyWhenSupplied() {
        BraveApiRequest pinned =
                RichEndpoint.assemble(new RichRequest("cb-7f3a2b"), PRODUCTION, token("t"), "2024-06-01");

        assertEquals(
                new BraveApiRequest.HeaderLine("Api-Version", "2024-06-01"),
                pinned.headers().get(4),
                "the pin follows the User-Agent line of the fixed assembly");

        BraveApiRequest unpinned = RichEndpoint.assemble(new RichRequest("cb-7f3a2b"), PRODUCTION, token("t"), null);

        assertEquals(4, unpinned.headers().size(), "no pin line travels without a pin: " + unpinned.headers());
    }

    private static String queryOf(RichRequest request) {
        return RichEndpoint.assemble(request, PRODUCTION, token("t")).uri().getRawQuery();
    }

    private static Credential token(String text) {
        return Credential.of(text.getBytes(UTF_8));
    }
}

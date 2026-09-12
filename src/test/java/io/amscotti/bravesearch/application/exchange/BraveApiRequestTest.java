package io.amscotti.bravesearch.application.exchange;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.FailureKind;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Header assembly of a composed request: one deterministic order, the token rendered from its
 * exact bytes, defaults for accept and encoding per response mode, the product User-Agent from
 * the generated version resource, and Content-Type existing exactly on a JSON POST body.
 */
final class BraveApiRequestTest {

    private static final URI ANY_URI = URI.create("https://api.search.brave.com/res/v1/web/search");

    @Test
    void assemblesTheHeaderLinesInOneDeterministicOrder() throws IOException {
        String token = "sentinel-" + UUID.randomUUID();

        BraveApiRequest request =
                BraveApiRequest.get(ANY_URI).token(Credential.of(token.getBytes(UTF_8))).build();

        assertEquals(
                List.of(
                        new BraveApiRequest.HeaderLine("X-Subscription-Token", token),
                        new BraveApiRequest.HeaderLine("Accept", "application/json"),
                        new BraveApiRequest.HeaderLine("Accept-Encoding", "gzip"),
                        new BraveApiRequest.HeaderLine("User-Agent", ExpectedUserAgent.fromVersionResource())),
                request.headers());
    }

    @Test
    void theTokenHeaderCarriesTheExactTokenBytesIncludingMultibyte() {
        String asciiToken = "sentinel-" + UUID.randomUUID();
        String multibyteToken = "séntinèl-café-" + UUID.randomUUID();

        assertEquals(
                asciiToken,
                tokenHeaderValue(BraveApiRequest.get(ANY_URI)
                        .token(Credential.of(asciiToken.getBytes(UTF_8)))
                        .build()));
        assertEquals(
                multibyteToken,
                tokenHeaderValue(BraveApiRequest.get(ANY_URI)
                        .token(Credential.of(multibyteToken.getBytes(UTF_8)))
                        .build()));
    }

    @Test
    void aTokenOutsideTheHeaderValueAlphabetIsRejectedWithoutEchoingIt() {
        String unwirable = "ключ-\uD83E\uDDEA-" + UUID.randomUUID();

        InvalidTokenException rejected =
                assertThrows(
                        InvalidTokenException.class,
                        () -> BraveApiRequest.get(ANY_URI)
                                .token(Credential.of(unwirable.getBytes(UTF_8)))
                                .build());

        assertNotNull(rejected.getMessage(), "the rejection must name the violated rule");
        assertFalse(rejected.getMessage().contains(unwirable), "the rejection never echoes token material");
        assertEquals(FailureKind.LOCAL_CONFIG, rejected.kind());
    }

    @Test
    void jsonContentTypeTravelsExactlyOnPostsWithBodies() {
        String token = "sentinel-" + UUID.randomUUID();
        Credential credential = Credential.of(token.getBytes(UTF_8));

        BraveApiRequest post =
                BraveApiRequest.post(ANY_URI, "{\"q\":\"café\"}".getBytes(UTF_8))
                        .token(credential)
                        .build();
        BraveApiRequest get = BraveApiRequest.get(ANY_URI).token(credential).build();

        assertEquals(
                "application/json",
                headerValue(post, "Content-Type").orElseThrow(() -> new AssertionError("Content-Type missing on POST")));
        assertTrue(
                headerValue(get, "Content-Type").isEmpty(),
                "a bodyless GET must not carry a Content-Type header");
        assertNull(get.bodyBytes());
        assertEquals(BraveApiRequest.Method.POST, post.method());
        assertEquals(BraveApiRequest.Method.GET, get.method());
    }

    @Test
    void theApiVersionHeaderTravelsOnlyWhenPinned() {
        Credential credential = Credential.of(("sentinel-" + UUID.randomUUID()).getBytes(UTF_8));

        BraveApiRequest pinned =
                BraveApiRequest.get(ANY_URI).token(credential).apiVersion("2025-06-30").build();
        BraveApiRequest unpinned = BraveApiRequest.get(ANY_URI).token(credential).build();

        assertEquals("2025-06-30", headerValue(pinned, "Api-Version").orElseThrow());
        assertTrue(headerValue(unpinned, "Api-Version").isEmpty(), "no Api-Version header without a pin");
    }

    @Test
    void rawResponseModeSelectsIdentityEncodingAndParsedModeGzip() {
        Credential credential = Credential.of(("sentinel-" + UUID.randomUUID()).getBytes(UTF_8));

        assertEquals(
                "identity",
                headerValue(BraveApiRequest.get(ANY_URI).token(credential).rawMode(true).build(), "Accept-Encoding")
                        .orElseThrow());
        assertEquals(
                "gzip",
                headerValue(BraveApiRequest.get(ANY_URI).token(credential).rawMode(false).build(), "Accept-Encoding")
                        .orElseThrow());
        assertEquals(
                "gzip",
                headerValue(BraveApiRequest.get(ANY_URI).token(credential).build(), "Accept-Encoding")
                        .orElseThrow());
    }

    @Test
    void acceptAndUserAgentAreConfigurableInsideTheAdapter() {
        Credential credential = Credential.of(("sentinel-" + UUID.randomUUID()).getBytes(UTF_8));

        BraveApiRequest request = BraveApiRequest.get(ANY_URI)
                .token(credential)
                .accept("text/event-stream")
                .userAgent("Mozilla/5.0 (product-specific agent)")
                .build();

        assertEquals("text/event-stream", headerValue(request, "Accept").orElseThrow());
        assertEquals("Mozilla/5.0 (product-specific agent)", headerValue(request, "User-Agent").orElseThrow());
    }

    @Test
    void bodyBytesAreDefensivelyCopiedInBothDirections() {
        byte[] mutable = "{\"q\":\"x\"}".getBytes(UTF_8);
        BraveApiRequest request =
                BraveApiRequest.post(ANY_URI, mutable)
                        .token(Credential.of(("sentinel-" + UUID.randomUUID()).getBytes(UTF_8)))
                        .build();

        mutable[1] = 'X';
        assertEquals("{\"q\":\"x\"}", new String(request.bodyBytes(), UTF_8));

        byte[] leaked = request.bodyBytes();
        leaked[0] = 'X';
        assertEquals("{\"q\":\"x\"}", new String(request.bodyBytes(), UTF_8));
    }

    @Test
    void missingMandatoryPiecesAreRejected() {
        String token = "sentinel-" + UUID.randomUUID();
        Credential credential = Credential.of(token.getBytes(UTF_8));

        assertThrows(NullPointerException.class, () -> BraveApiRequest.get(ANY_URI).build());
        assertThrows(NullPointerException.class, () -> BraveApiRequest.get(null).token(credential).build());
        assertThrows(NullPointerException.class, () -> BraveApiRequest.post(null, new byte[] {1}).build());
        assertThrows(NullPointerException.class, () -> BraveApiRequest.post(ANY_URI, null).build());
        assertThrows(NullPointerException.class, () -> BraveApiRequest.get(ANY_URI).token(null).build());
        assertThrows(
                NullPointerException.class,
                () -> BraveApiRequest.get(ANY_URI).token(credential).apiVersion(null).build());
        assertThrows(
                NullPointerException.class,
                () -> BraveApiRequest.get(ANY_URI).token(credential).userAgent(null).build());
        assertThrows(
                NullPointerException.class,
                () -> BraveApiRequest.get(ANY_URI).token(credential).accept(null).build());
        assertThrows(NullPointerException.class, () -> BraveApiRequest.requireWireableToken(null));
    }

    private static String tokenHeaderValue(BraveApiRequest request) {
        return headerValue(request, "X-Subscription-Token").orElseThrow();
    }

    private static Optional<String> headerValue(BraveApiRequest request, String name) {
        return request.headers().stream()
                .filter(header -> header.name().equals(name))
                .map(BraveApiRequest.HeaderLine::value)
                .findFirst();
    }
}

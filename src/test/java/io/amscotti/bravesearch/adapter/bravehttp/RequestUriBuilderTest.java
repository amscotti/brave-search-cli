package io.amscotti.bravesearch.adapter.bravehttp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.QueryParameter;
import io.amscotti.bravesearch.application.exchange.RequestUri;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * URI composition under an origin: the endpoint path joins the origin base, every query name
 * and value is percent-encoded independently and strictly, insertion order (including
 * duplicates) survives into the composed URI, and the redacted rendering keeps structure and
 * names but never a single value's text.
 */
final class RequestUriBuilderTest {

    private static final BraveApiOrigin LOCALHOST_RESOLVER_NEVER_INVOKED =
            BraveApiOrigin.fromOverride(
                    "http://127.0.0.1:9", host -> { throw new AssertionError("literals are never resolved"); });

    @Test
    void joinsTheEndpointPathUnderTheProductionOrigin() {
        RequestUri built = new RequestUriBuilder(BraveApiOrigin.production())
                .build("web/search", List.of());

        assertEquals(URI.create("https://api.search.brave.com/res/v1/web/search"), built.uri());
        assertEquals("https://api.search.brave.com/res/v1/web/search", built.redacted());
    }

    @Test
    void joinsTheEndpointPathDirectlyUnderALoopbackOrigin() {
        RequestUri built = new RequestUriBuilder(LOCALHOST_RESOLVER_NEVER_INVOKED)
                .build("web/search", List.of());

        assertEquals(URI.create("http://127.0.0.1:9/web/search"), built.uri());
    }

    @Test
    void encodesEveryKeyAndValueIndependentlyAndStrictly() {
        RequestUri built = new RequestUriBuilder(BraveApiOrigin.production())
                .build(
                        "web/search",
                        List.of(
                                new QueryParameter("q", "café search"),
                                new QueryParameter("a&b=c?", "x y+.z~_-")));

        assertEquals(
                "q=caf%C3%A9%20search&a%26b%3Dc%3F=x%20y%2B.z~_-",
                built.uri().getRawQuery(),
                "space must be %20, plus must be %2B, reserved characters encoded, unreserved kept");
        assertEquals("/res/v1/web/search", built.uri().getPath());
        assertNotNull(built.uri().getScheme());
    }

    @Test
    void preservesInsertionOrderIncludingDuplicatesDeterministically() {
        List<QueryParameter> ordered =
                List.of(
                        new QueryParameter("z", "1"),
                        new QueryParameter("a", "2"),
                        new QueryParameter("z", "3"));
        RequestUriBuilder builder = new RequestUriBuilder(BraveApiOrigin.production());

        RequestUri first = builder.build("web/search", ordered);
        RequestUri second = builder.build("web/search", ordered);

        assertEquals(first.uri(), second.uri(), "identical input must compose a byte-identical URI");
        assertEquals("z=1&a=2&z=3", first.uri().getRawQuery());
        assertNotEquals(
                first.uri().getRawQuery(),
                builder
                        .build(
                                "web/search",
                                ordered.reversed())
                        .uri()
                        .getRawQuery(),
                "parameter order is significant and must never be reordered");
    }

    @Test
    void anEmptyValueRendersAsABareSeparator() {
        RequestUri built = new RequestUriBuilder(BraveApiOrigin.production())
                .build("web/search", List.of(new QueryParameter("empty", "")));

        assertEquals("empty=", built.uri().getRawQuery());
    }

    @Test
    void redactedRenderingMasksEveryQueryValue() {
        String queryText = "marker-" + UUID.randomUUID();
        RequestUri built = new RequestUriBuilder(BraveApiOrigin.production())
                .build(
                        "web/search",
                        List.of(
                                new QueryParameter("q", queryText),
                                new QueryParameter("country", queryText)));

        assertEquals(
                "https://api.search.brave.com/res/v1/web/search?q=<redacted>&country=<redacted>",
                built.redacted(),
                "the redacted rendering shows structure and names with fixed value markers");
        assertFalse(built.redacted().contains(queryText), "no query value text may survive redaction");
        assertTrue(built.uri().getRawQuery().contains(queryText), "the real URI still carries the value");
    }

    @Test
    void redactedRenderingOmitsTheQuerySeparatorWithoutParameters() {
        RequestUri built = new RequestUriBuilder(BraveApiOrigin.production())
                .build("spellcheck/search", List.of());

        assertEquals("https://api.search.brave.com/res/v1/spellcheck/search", built.redacted());
        assertFalse(built.redacted().contains("?"));
    }

    @Test
    void rejectsNullKeysValuesAndArguments() {
        RequestUriBuilder builder = new RequestUriBuilder(BraveApiOrigin.production());

        assertThrows(NullPointerException.class, () -> new QueryParameter(null, "v"));
        assertThrows(NullPointerException.class, () -> new QueryParameter("k", null));
        assertThrows(NullPointerException.class, () -> builder.build(null, List.of()));
        assertThrows(NullPointerException.class, () -> builder.build("web/search", null));
        assertThrows(
                NullPointerException.class,
                () -> builder.build(
                        "web/search",
                        Arrays.asList(new QueryParameter("k", "v"), null)));
        assertThrows(NullPointerException.class, () -> new RequestUriBuilder(null));
    }

    @Test
    void rejectsEmptyInnerPathSegments() {
        RequestUriBuilder builder = new RequestUriBuilder(BraveApiOrigin.production());

        for (String hollow : List.of("a//b", "web//search", "web///search")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> builder.build(hollow, List.of()),
                    () -> "must reject endpoint path [" + hollow + "] with an empty inner segment");
        }
    }

    @Test
    void rejectsMalformedEndpointPaths() {
        RequestUriBuilder builder = new RequestUriBuilder(BraveApiOrigin.production());

        for (String malformed :
                List.of("", "   ", "/web/search", "web/search/", "./web", "web/../search", ".", "..")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> builder.build(malformed, List.of()),
                    () -> "must reject endpoint path [" + malformed + "]");
        }
    }
}

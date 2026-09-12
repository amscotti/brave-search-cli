package io.amscotti.bravesearch.adapter.bravehttp.endpoint;

import io.amscotti.bravesearch.adapter.bravehttp.RequestUriBuilder;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.BraveApiRequest;
import io.amscotti.bravesearch.application.exchange.QueryParameter;
import io.amscotti.bravesearch.application.exchange.QueryParameters;
import io.amscotti.bravesearch.application.exchange.RequestUri;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.goggles.Goggle;
import io.amscotti.bravesearch.domain.request.NewsSearchRequest;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import tools.jackson.core.JsonEncoding;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.json.JsonFactory;

/**
 * Assembles the news search exchange: a validated domain request becomes the wire form of
 * {@code /news/search} — GET with percent-encoded query parameters under the origin, or,
 * exactly when an inline Goggle is present or the fully encoded GET URI would exceed
 * {@link #MAX_GET_URI_BYTES} bytes, POST with the same fields as one JSON body. The news
 * endpoint documents no location header group, so none is ever assembled.
 *
 * <p>Both methods emit one shared field inventory, each in alphabetical wire-name order, so
 * their serialization is equivalent field for field — pinned by the contract tests — with
 * only the method-inherent differences: the GET form percent-encodes every value, while the
 * POST body writes numbers and booleans as native JSON members and carries every goggle,
 * URL references and inline definitions alike, as one {@code goggles} array in the given
 * order. Only URL-reference goggles can ride a GET, because an inline definition forces the
 * POST form; a POST target carries no query at all.
 *
 * <p>Every boolean and optional value the caller left unset is omitted rather than coerced
 * to an upstream default, and the user-facing page travels as its zero-based {@code offset}.
 */
public final class NewsSearchEndpoint {

    /** The news search endpoint path under the origin base. */
    public static final String ENDPOINT_PATH = "news/search";

    /** The largest fully encoded GET URI, in bytes; one byte more switches to POST. */
    public static final int MAX_GET_URI_BYTES = 8000;

    private NewsSearchEndpoint() {}

    /**
     * Assembles the GET request for the given search under the given origin without a
     * version pin; identical to {@link #assemble(NewsSearchRequest, BraveApiOrigin, Credential, String)}
     * with an absent pin.
     */
    public static BraveApiRequest assemble(NewsSearchRequest request, BraveApiOrigin origin, Credential token) {
        return assemble(request, origin, token, null);
    }

    /**
     * Assembles the request for the given search under the given origin: the GET form
     * unless an inline Goggle or the URI-length rule forces the POST form, pinning
     * {@code Api-Version} to the given exact spelling when one is supplied.
     *
     * @param pinnedApiVersion the exact {@code Api-Version} header value, or null for no pin
     */
    public static BraveApiRequest assemble(
            NewsSearchRequest request, BraveApiOrigin origin, Credential token, String pinnedApiVersion) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(token, "token");
        RequestUri target = RequestUriBuilder.compose(origin, ENDPOINT_PATH, queryParametersOf(request));
        if (carriesInlineGoggle(request) || encodedByteLength(target.uri()) > MAX_GET_URI_BYTES) {
            RequestUri bodyless = RequestUriBuilder.compose(origin, ENDPOINT_PATH, List.of());
            BraveApiRequest.Builder assembly = BraveApiRequest.post(bodyless.uri(), postBodyOf(request)).token(token);
            if (pinnedApiVersion != null) {
                assembly.apiVersion(pinnedApiVersion);
            }
            return assembly.build();
        }
        BraveApiRequest.Builder assembly = BraveApiRequest.get(target.uri()).token(token);
        if (pinnedApiVersion != null) {
            assembly.apiVersion(pinnedApiVersion);
        }
        return assembly.build();
    }

    private static boolean carriesInlineGoggle(NewsSearchRequest request) {
        return request.goggles().stream().anyMatch(Goggle.Inline.class::isInstance);
    }

    private static int encodedByteLength(java.net.URI uri) {
        return uri.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    /** The wire value of one goggle: its URL, or its inline definition text. */
    private static String wireTextOf(Goggle goggle) {
        return goggle instanceof Goggle.UrlReference url ? url.url() : ((Goggle.Inline) goggle).definition();
    }

    /**
     * The GET parameters, alphabetical by wire name; identical requests always render the
     * same order. Only URL-reference goggles ride the URI — an inline definition forces the
     * POST form.
     */
    private static List<QueryParameter> queryParametersOf(NewsSearchRequest request) {
        List<QueryParameter> parameters = new ArrayList<>();
        QueryParameters.addNumber(parameters, "count", request.count());
        QueryParameters.addString(parameters, "country", request.country());
        QueryParameters.addFlag(parameters, "extra_snippets", request.extraSnippets());
        QueryParameters.addString(parameters, "freshness", request.freshness() == null ? null : request.freshness().wireValue());
        for (Goggle goggle : request.goggles()) {
            if (goggle instanceof Goggle.UrlReference url) {
                parameters.add(new QueryParameter("goggles", url.url()));
            }
        }
        QueryParameters.addFlag(parameters, "include_fetch_metadata", request.includeFetchMetadata());
        QueryParameters.addNumber(parameters, "offset", request.upstreamOffset());
        QueryParameters.addFlag(parameters, "operators", request.operators());
        parameters.add(new QueryParameter("q", request.query()));
        QueryParameters.addString(parameters, "safesearch", request.safeSearch() == null ? null : request.safeSearch().wireName());
        QueryParameters.addString(parameters, "search_lang", request.searchLang());
        QueryParameters.addFlag(parameters, "spellcheck", request.spellcheck());
        QueryParameters.addString(parameters, "ui_lang", request.uiLang());
        parameters.sort(Comparator.comparing(QueryParameter::name));
        return parameters;
    }

    /**
     * The POST body: the same field inventory as the GET form, in the same alphabetical
     * order — the two emitters are pinned field-for-field by the contract tests — with
     * numbers and booleans as native JSON members and every goggle as one array.
     */
    private static byte[] postBodyOf(NewsSearchRequest request) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (JsonGenerator generator = new JsonFactory().createGenerator(buffer, JsonEncoding.UTF8)) {
            generator.writeStartObject();
            writeNumberWhenPresent(generator, "count", request.count());
            writeStringWhenPresent(generator, "country", request.country());
            writeBooleanWhenSupplied(generator, "extra_snippets", request.extraSnippets());
            writeStringWhenPresent(
                    generator, "freshness", request.freshness() == null ? null : request.freshness().wireValue());
            if (!request.goggles().isEmpty()) {
                generator.writeName("goggles");
                generator.writeStartArray();
                for (Goggle goggle : request.goggles()) {
                    generator.writeString(wireTextOf(goggle));
                }
                generator.writeEndArray();
            }
            writeBooleanWhenSupplied(generator, "include_fetch_metadata", request.includeFetchMetadata());
            writeNumberWhenPresent(generator, "offset", request.upstreamOffset());
            writeBooleanWhenSupplied(generator, "operators", request.operators());
            generator.writeName("q");
            generator.writeString(request.query());
            writeStringWhenPresent(
                    generator, "safesearch", request.safeSearch() == null ? null : request.safeSearch().wireName());
            writeStringWhenPresent(generator, "search_lang", request.searchLang());
            writeBooleanWhenSupplied(generator, "spellcheck", request.spellcheck());
            writeStringWhenPresent(generator, "ui_lang", request.uiLang());
            generator.writeEndObject();
        }
        return buffer.toByteArray();
    }

    private static void writeStringWhenPresent(JsonGenerator generator, String name, String value) {
        if (value != null) {
            generator.writeName(name);
            generator.writeString(value);
        }
    }

    private static void writeNumberWhenPresent(JsonGenerator generator, String name, Integer value) {
        if (value != null) {
            generator.writeName(name);
            generator.writeNumber(value);
        }
    }

    private static void writeBooleanWhenSupplied(JsonGenerator generator, String name, Boolean supplied) {
        if (supplied != null) {
            generator.writeName(name);
            generator.writeBoolean(supplied);
        }
    }
}

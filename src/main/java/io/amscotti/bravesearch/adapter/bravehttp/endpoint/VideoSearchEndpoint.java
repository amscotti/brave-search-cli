package io.amscotti.bravesearch.adapter.bravehttp.endpoint;

import io.amscotti.bravesearch.adapter.bravehttp.RequestUriBuilder;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.BraveApiRequest;
import io.amscotti.bravesearch.application.exchange.QueryParameter;
import io.amscotti.bravesearch.application.exchange.QueryParameters;
import io.amscotti.bravesearch.application.exchange.RequestUri;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.request.VideoSearchRequest;
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
 * Assembles the videos search exchange: a validated domain request becomes the wire form
 * of {@code /videos/search} — GET with percent-encoded query parameters under the origin,
 * or, exactly when the fully encoded GET URI would exceed {@link #MAX_GET_URI_BYTES}
 * bytes, POST with the same fields as one JSON body. The endpoint documents no Goggles,
 * so no inline definition can force the POST form and the URI-length rule is its sole
 * trigger; the endpoint also documents no location header group, so none is ever
 * assembled.
 *
 * <p>Both methods emit one shared field inventory, each in alphabetical wire-name order,
 * so their serialization is equivalent field for field — pinned by the contract tests —
 * with only the method-inherent differences: the GET form percent-encodes every value,
 * while the POST body writes numbers and booleans as native JSON members.
 *
 * <p>Every boolean and optional value the caller left unset is omitted rather than coerced
 * to an upstream default, and the user-facing page travels as its zero-based {@code offset}.
 */
public final class VideoSearchEndpoint {

    /** The videos search endpoint path under the origin base. */
    public static final String ENDPOINT_PATH = "videos/search";

    /** The largest fully encoded GET URI, in bytes; one byte more switches to POST. */
    public static final int MAX_GET_URI_BYTES = 8000;

    private VideoSearchEndpoint() {}

    /**
     * Assembles the GET request for the given search under the given origin without a
     * version pin; identical to
     * {@link #assemble(VideoSearchRequest, BraveApiOrigin, Credential, String)} with an
     * absent pin.
     */
    public static BraveApiRequest assemble(VideoSearchRequest request, BraveApiOrigin origin, Credential token) {
        return assemble(request, origin, token, null);
    }

    /**
     * Assembles the request for the given search under the given origin: the GET form
     * unless the URI-length rule forces the POST form, pinning {@code Api-Version} to the
     * given exact spelling when one is supplied.
     *
     * @param pinnedApiVersion the exact {@code Api-Version} header value, or null for no pin
     */
    public static BraveApiRequest assemble(
            VideoSearchRequest request, BraveApiOrigin origin, Credential token, String pinnedApiVersion) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(token, "token");
        RequestUri target = RequestUriBuilder.compose(origin, ENDPOINT_PATH, queryParametersOf(request));
        if (encodedByteLength(target.uri()) > MAX_GET_URI_BYTES) {
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

    private static int encodedByteLength(java.net.URI uri) {
        return uri.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * The GET parameters, alphabetical by wire name; identical requests always render the
     * same order.
     */
    private static List<QueryParameter> queryParametersOf(VideoSearchRequest request) {
        List<QueryParameter> parameters = new ArrayList<>();
        QueryParameters.addNumber(parameters, "count", request.count());
        QueryParameters.addString(parameters, "country", request.country());
        QueryParameters.addString(parameters, "freshness", request.freshness() == null ? null : request.freshness().wireValue());
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
     * numbers and booleans as native JSON members.
     */
    private static byte[] postBodyOf(VideoSearchRequest request) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (JsonGenerator generator = new JsonFactory().createGenerator(buffer, JsonEncoding.UTF8)) {
            generator.writeStartObject();
            writeNumberWhenPresent(generator, "count", request.count());
            writeStringWhenPresent(generator, "country", request.country());
            writeStringWhenPresent(
                    generator, "freshness", request.freshness() == null ? null : request.freshness().wireValue());
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

package io.amscotti.bravesearch.adapter.bravehttp.endpoint;

import io.amscotti.bravesearch.adapter.bravehttp.RequestUriBuilder;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.BraveApiRequest;
import io.amscotti.bravesearch.application.exchange.QueryParameter;
import io.amscotti.bravesearch.application.exchange.QueryParameters;
import io.amscotti.bravesearch.application.exchange.RequestUri;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.UsageValidationError;
import io.amscotti.bravesearch.domain.request.ContextRequest;
import io.amscotti.bravesearch.domain.request.LocationRules;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import tools.jackson.core.JsonEncoding;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.json.JsonFactory;

/**
 * Assembles the LLM context exchange: a validated domain request becomes the wire form of
 * {@code /llm/context} — GET with percent-encoded query parameters under the origin and
 * the seven documented location members as X-Loc headers, or, exactly when the fully
 * encoded GET URI would exceed {@link #MAX_GET_URI_BYTES} bytes, POST with the same fields
 * as one JSON body. The context endpoint documents no inline Goggles on this CLI, so the
 * URI length is the one POST trigger.
 *
 * <p>Both methods emit one shared field inventory, each in alphabetical wire-name order, so
 * their serialization is equivalent field for field — pinned by the contract tests — with
 * only the method-inherent differences: the GET form percent-encodes every value, while the
 * POST body writes numbers and booleans as native JSON members.
 *
 * <p>Every optional value the caller left unset is omitted rather than coerced to a Brave
 * default: the count, the per-request and per-url token and snippet budgets, the threshold
 * mode, the tri-state source-metadata and local-recall flags, and the whole location group
 * travel only when they were supplied. Free-text location values become HTTP header values
 * here, so header safety is enforced at this boundary — and the timezone header this
 * endpoint does not document can never appear, because the request carries no timezone
 * member at all.
 */
public final class ContextEndpoint {

    /** The LLM context endpoint path under the origin base. */
    public static final String ENDPOINT_PATH = "llm/context";

    /** The largest fully encoded GET URI, in bytes; one byte more switches to POST. */
    public static final int MAX_GET_URI_BYTES = 8000;

    private ContextEndpoint() {}

    /**
     * Assembles the GET request for the given context retrieval under the given origin
     * without a version pin; identical to {@link #assemble(ContextRequest, BraveApiOrigin,
     * Credential, String)} with an absent pin.
     *
     * @throws UsageValidationError when a location header value is not header-safe
     */
    public static BraveApiRequest assemble(ContextRequest request, BraveApiOrigin origin, Credential token) {
        return assemble(request, origin, token, null);
    }

    /**
     * Assembles the request for the given context retrieval under the given origin: the GET
     * form unless the URI-length rule forces the POST form, pinning {@code Api-Version} to
     * the given exact spelling when one is supplied.
     *
     * @param pinnedApiVersion the exact {@code Api-Version} header value, or null for no pin
     * @throws UsageValidationError when a location header value is not header-safe
     */
    public static BraveApiRequest assemble(
            ContextRequest request, BraveApiOrigin origin, Credential token, String pinnedApiVersion) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(token, "token");
        RequestUri target = RequestUriBuilder.compose(origin, ENDPOINT_PATH, queryParametersOf(request));
        if (encodedByteLength(target.uri()) > MAX_GET_URI_BYTES) {
            RequestUri bodyless = RequestUriBuilder.compose(origin, ENDPOINT_PATH, List.of());
            return withLocationHeaders(
                    request, BraveApiRequest.post(bodyless.uri(), postBodyOf(request)).token(token), pinnedApiVersion);
        }
        return withLocationHeaders(request, BraveApiRequest.get(target.uri()).token(token), pinnedApiVersion);
    }

    private static int encodedByteLength(java.net.URI uri) {
        return uri.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    private static BraveApiRequest withLocationHeaders(
            ContextRequest request, BraveApiRequest.Builder assembly, String pinnedApiVersion) {
        if (pinnedApiVersion != null) {
            assembly.apiVersion(pinnedApiVersion);
        }
        locationHeadersOf(request.location())
                .forEach((name, value) -> {
                    LocationRules.requireHeaderSafe(name, value);
                    assembly.additionalHeader(name, value);
                });
        return assembly.build();
    }

    /**
     * The GET parameters, alphabetical by wire name; identical requests always render the
     * same order. An unsupplied optional never appears — the documented Brave defaults are
     * upstream decisions, never values this CLI invents.
     */
    private static List<QueryParameter> queryParametersOf(ContextRequest request) {
        List<QueryParameter> parameters = new ArrayList<>();
        QueryParameters.addString(
                parameters,
                "context_threshold_mode",
                request.threshold() == null ? null : request.threshold().wireName());
        QueryParameters.addNumber(parameters, "count", request.count());
        QueryParameters.addString(parameters, "country", request.country());
        QueryParameters.addFlag(parameters, "enable_local", request.enableLocal());
        QueryParameters.addFlag(parameters, "enable_source_metadata", request.sourceMetadata());
        QueryParameters.addString(parameters, "freshness", request.freshness() == null ? null : request.freshness().wireValue());
        QueryParameters.addNumber(parameters, "maximum_number_of_snippets", request.maxSnippets());
        QueryParameters.addNumber(parameters, "maximum_number_of_snippets_per_url", request.maxSnippetsPerUrl());
        QueryParameters.addNumber(parameters, "maximum_number_of_tokens", request.maxTokens());
        QueryParameters.addNumber(parameters, "maximum_number_of_tokens_per_url", request.maxTokensPerUrl());
        QueryParameters.addNumber(parameters, "maximum_number_of_urls", request.maxUrls());
        parameters.add(new QueryParameter("q", request.query()));
        QueryParameters.addString(parameters, "safesearch", request.safeSearch() == null ? null : request.safeSearch().wireName());
        QueryParameters.addString(parameters, "search_lang", request.searchLang());
        parameters.sort(Comparator.comparing(QueryParameter::name));
        return parameters;
    }

    /**
     * The POST body: the same field inventory as the GET form, in the same alphabetical
     * order — the two emitters are pinned field-for-field by the contract tests — with
     * numbers and booleans as native JSON members.
     */
    private static byte[] postBodyOf(ContextRequest request) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (JsonGenerator generator = new JsonFactory().createGenerator(buffer, JsonEncoding.UTF8)) {
            generator.writeStartObject();
            writeStringWhenPresent(
                    generator,
                    "context_threshold_mode",
                    request.threshold() == null ? null : request.threshold().wireName());
            writeNumberWhenPresent(generator, "count", request.count());
            writeStringWhenPresent(generator, "country", request.country());
            writeBooleanWhenSupplied(generator, "enable_local", request.enableLocal());
            writeBooleanWhenSupplied(generator, "enable_source_metadata", request.sourceMetadata());
            writeStringWhenPresent(
                    generator, "freshness", request.freshness() == null ? null : request.freshness().wireValue());
            writeNumberWhenPresent(generator, "maximum_number_of_snippets", request.maxSnippets());
            writeNumberWhenPresent(generator, "maximum_number_of_snippets_per_url", request.maxSnippetsPerUrl());
            writeNumberWhenPresent(generator, "maximum_number_of_tokens", request.maxTokens());
            writeNumberWhenPresent(generator, "maximum_number_of_tokens_per_url", request.maxTokensPerUrl());
            writeNumberWhenPresent(generator, "maximum_number_of_urls", request.maxUrls());
            generator.writeName("q");
            generator.writeString(request.query());
            writeStringWhenPresent(
                    generator, "safesearch", request.safeSearch() == null ? null : request.safeSearch().wireName());
            writeStringWhenPresent(generator, "search_lang", request.searchLang());
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

    /**
     * The location group as its seven documented X-Loc headers, alphabetical by header
     * name, absent values dropped — and no timezone member, because the request type
     * carries none.
     */
    private static Map<String, String> locationHeadersOf(ContextRequest.Location location) {
        Map<String, String> headers = new TreeMap<>();
        if (location == null) {
            return headers;
        }
        if (location.latitude() != null) {
            headers.put("X-Loc-Lat", Double.toString(location.latitude()));
        }
        if (location.longitude() != null) {
            headers.put("X-Loc-Long", Double.toString(location.longitude()));
        }
        putWhenPresent(headers, "X-Loc-City", location.city());
        putWhenPresent(headers, "X-Loc-State", location.state());
        putWhenPresent(headers, "X-Loc-State-Name", location.stateName());
        putWhenPresent(headers, "X-Loc-Country", location.country());
        putWhenPresent(headers, "X-Loc-Postal-Code", location.postalCode());
        return headers;
    }

    private static void putWhenPresent(Map<String, String> headers, String name, String value) {
        if (value != null) {
            headers.put(name, value);
        }
    }
}

package io.amscotti.bravesearch.adapter.bravehttp.endpoint;

import io.amscotti.bravesearch.adapter.bravehttp.RequestUriBuilder;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.BraveApiRequest;
import io.amscotti.bravesearch.application.exchange.RequestUri;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.request.AnswersRequest;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.Objects;
import tools.jackson.core.JsonEncoding;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.json.JsonFactory;

/**
 * Assembles the Answers exchange: a validated domain request becomes the wire form of
 * {@code /chat/completions} — always POST with one JSON body, because the endpoint
 * documents POST only and its body is the chat-completions form rather than encoded
 * parameters.
 *
 * <p>The body's top level carries exactly the chat members — {@code messages}, {@code
 * model}, {@code stream}, {@code max_completion_tokens}, {@code seed}, {@code metadata} —
 * while every search-control member ({@code country}, {@code language}, {@code
 * safesearch}, the three {@code enable_*} flags, and the whole {@code research_*}
 * family) sits inside the nested {@code web_search_options} object and never rides the
 * top level. Both levels render in alphabetical wire-name order, so identical requests
 * always render the same bytes; every member the caller left unset is omitted rather
 * than coerced to an upstream default, and an options container with no supplied member
 * is omitted whole. The one message is exactly one {@code user} message carrying the
 * question verbatim.
 */
public final class AnswersEndpoint {

    /** The Answers chat-completions endpoint path under the origin base. */
    public static final String ENDPOINT_PATH = "chat/completions";

    private AnswersEndpoint() {}

    /**
     * Assembles the POST request for the given answers lookup under the given origin
     * without a version pin; identical to
     * {@link #assemble(AnswersRequest, BraveApiOrigin, Credential, String)} with an
     * absent pin.
     */
    public static BraveApiRequest assemble(AnswersRequest request, BraveApiOrigin origin, Credential token) {
        return assemble(request, origin, token, null);
    }

    /**
     * Assembles the POST request for the given answers lookup under the given origin,
     * pinning {@code Api-Version} to the given exact spelling when one is supplied.
     *
     * @param pinnedApiVersion the exact {@code Api-Version} header value, or null for no pin
     */
    public static BraveApiRequest assemble(
            AnswersRequest request, BraveApiOrigin origin, Credential token, String pinnedApiVersion) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(token, "token");
        RequestUri target = RequestUriBuilder.compose(origin, ENDPOINT_PATH, java.util.List.of());
        BraveApiRequest.Builder assembly = BraveApiRequest.post(target.uri(), postBodyOf(request)).token(token);
        if (pinnedApiVersion != null) {
            assembly.apiVersion(pinnedApiVersion);
        }
        return assembly.build();
    }

    /**
     * Assembles the POST request of one streaming answers exchange: the same chat-completions
     * body with the stream flag true, accepting the event-stream media type and negotiating
     * {@code Accept-Encoding: identity}. One exchange serves both representations of the body —
     * decoded-exact event-stream bytes for the raw mode and the semantic event stream decoded
     * from those same bytes — so identity is the only coding a stream ever asks for: a gzipped
     * body would be inflated before either representation exists, which is exactly what the
     * identity negotiation avoids having to define twice.
     *
     * @throws IllegalArgumentException when {@code request} does not carry {@code stream=true},
     *     because a blocking request cannot open a streaming exchange
     */
    public static BraveApiRequest assembleStream(
            AnswersRequest request, BraveApiOrigin origin, Credential token, String pinnedApiVersion) {
        Objects.requireNonNull(request, "request");
        if (!request.stream()) {
            throw new IllegalArgumentException("a streaming exchange requires a request with stream=true");
        }
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(token, "token");
        RequestUri target = RequestUriBuilder.compose(origin, ENDPOINT_PATH, java.util.List.of());
        BraveApiRequest.Builder assembly = BraveApiRequest.post(target.uri(), postBodyOf(request))
                .token(token)
                .accept("text/event-stream")
                .rawMode(true);
        if (pinnedApiVersion != null) {
            assembly.apiVersion(pinnedApiVersion);
        }
        return assembly.build();
    }

    /**
     * The POST body: the chat members and the nested {@code web_search_options} container,
     * each level alphabetical by wire name, every unsupplied member omitted.
     */
    private static byte[] postBodyOf(AnswersRequest request) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (JsonGenerator generator = new JsonFactory().createGenerator(buffer, JsonEncoding.UTF8)) {
            generator.writeStartObject();
            writeNumberWhenPresent(generator, "max_completion_tokens", request.maxCompletionTokens());
            generator.writeName("messages");
            generator.writeStartArray();
            generator.writeStartObject();
            generator.writeName("content");
            generator.writeString(request.question());
            generator.writeName("role");
            generator.writeString("user");
            generator.writeEndObject();
            generator.writeEndArray();
            if (request.metadata() != null) {
                generator.writeName("metadata");
                generator.writeStartObject();
                for (Map.Entry<String, String> member : request.metadata().entrySet()) {
                    generator.writeName(member.getKey());
                    generator.writeString(member.getValue());
                }
                generator.writeEndObject();
            }
            writeStringWhenPresent(generator, "model", request.model());
            writeNumberWhenPresent(generator, "seed", request.seed());
            generator.writeName("stream");
            generator.writeBoolean(request.stream());
            writeSearchOptions(generator, request);
            generator.writeEndObject();
        }
        return buffer.toByteArray();
    }

    /**
     * The nested {@code web_search_options} container — the one home of every
     * search-control member — omitted whole when the request supplied none of them.
     */
    private static void writeSearchOptions(JsonGenerator generator, AnswersRequest request) {
        boolean empty = request.country() == null
                && request.language() == null
                && request.safeSearch() == null
                && request.citations() == null
                && request.entities() == null
                && request.research() == null
                && request.researchAllowThinking() == null
                && request.researchMaximumTokensPerQuery() == null
                && request.researchMaximumQueries() == null
                && request.researchMaximumIterations() == null
                && request.researchMaximumSeconds() == null
                && request.researchMaximumResultsPerQuery() == null;
        if (empty) {
            return;
        }
        generator.writeName("web_search_options");
        generator.writeStartObject();
        writeStringWhenPresent(generator, "country", request.country());
        writeBooleanWhenSupplied(generator, "enable_citations", request.citations());
        writeBooleanWhenSupplied(generator, "enable_entities", request.entities());
        writeBooleanWhenSupplied(generator, "enable_research", request.research());
        writeStringWhenPresent(generator, "language", request.language());
        writeBooleanWhenSupplied(generator, "research_allow_thinking", request.researchAllowThinking());
        writeNumberWhenPresent(generator, "research_maximum_iterations", request.researchMaximumIterations());
        writeNumberWhenPresent(generator, "research_maximum_queries", request.researchMaximumQueries());
        writeNumberWhenPresent(
                generator, "research_maximum_results_per_query", request.researchMaximumResultsPerQuery());
        writeNumberWhenPresent(generator, "research_maximum_seconds", request.researchMaximumSeconds());
        writeNumberWhenPresent(generator, "research_maximum_tokens_per_query", request.researchMaximumTokensPerQuery());
        writeStringWhenPresent(
                generator, "safesearch", request.safeSearch() == null ? null : request.safeSearch().wireName());
        generator.writeEndObject();
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

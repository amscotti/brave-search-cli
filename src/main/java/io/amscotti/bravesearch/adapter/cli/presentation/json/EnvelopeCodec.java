package io.amscotti.bravesearch.adapter.cli.presentation.json;

import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.UnreadableBodyException;
import io.amscotti.bravesearch.domain.metadata.RateLimitWindow;
import io.amscotti.bravesearch.domain.metadata.RequestMeta;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.metadata.Usage;
import io.amscotti.bravesearch.domain.result.PagedExchange;
import io.amscotti.bravesearch.domain.result.PagedSearch;
import io.amscotti.bravesearch.domain.result.Projection;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Encodes the schema-versioned success and failure envelopes.
 *
 * <p>Success field order is exactly {@code schema_version, ok, command,
 * data{projection, upstream}, meta{request_id, http_status, api_version, rate_limits[], usage,
 * warnings}}; failure field order is {@code schema_version, ok, command, error{code, message,
 * retryable, upstream_code, details}, meta{http_status, rate_limits}}. Every document is compact
 * and terminated by exactly one LF. Warnings ride in {@code meta.warnings} because JSON mode owns
 * them on machine output; the failure envelope keeps only the metadata the error contract
 * defines. {@code meta.usage} renders the Answers counters, the exact-scale cost decimals, and
 * the preserved unknown {@code X-Request-*} fields; its parse notes stay on the diagnostic
 * channel instead of the envelope. Upstream bytes become the lossless {@code data.upstream}
 * tree — decimals round-trip exactly as {@code BigDecimal}, see {@link
 * JsonMappers#upstreamReader()} — and must be exactly one JSON document, otherwise encoding
 * fails loudly with an {@link UnreadableBodyException} — the malformed-response verdict the
 * presenters classify — instead of guessing.
 *
 * <p>A multi-request invocation encodes {@code data.upstream} as the ordered array of its
 * completed pages, one {@code request_index}/{@code meta}/{@code body} entry per request —
 * the array form itself is the discriminator of a multi-request command; a single-request
 * command always emits the bare body tree.
 */
public final class EnvelopeCodec {

    /** The machine-output schema version of every document this codec emits. */
    public static final String SCHEMA_VERSION = "1";

    private final JsonMappers mappers;

    public EnvelopeCodec(JsonMappers mappers) {
        this.mappers = mappers;
    }

    public byte[] encodeSuccess(
            String command, Projection projection, UpstreamPayload upstream, RequestMeta meta, List<String> warnings) {
        return encodeSuccess(command, projection, upstream, meta, warnings, false);
    }

    /**
     * The success envelope over a prebuilt upstream tree — the streaming family's form, whose
     * exchange publishes one event stream instead of one JSON body and therefore assembles
     * its {@code data.upstream} itself.
     */
    public byte[] encodeSuccess(
            String command,
            Projection projection,
            JsonNode upstream,
            RequestMeta meta,
            List<String> warnings,
            boolean pretty) {
        ObjectNode root = successRoot(command, projection, upstream, meta, warnings);
        return terminated(mappers.outputMapper(), root, pretty);
    }

    /** The success envelope, in the compact form or the stable pretty form of {@code pretty}. */
    public byte[] encodeSuccess(
            String command,
            Projection projection,
            UpstreamPayload upstream,
            RequestMeta meta,
            List<String> warnings,
            boolean pretty) {
        ObjectNode root = successRoot(command, projection, upstreamTree(upstream), meta, warnings);
        return terminated(mappers.outputMapper(), root, pretty);
    }

    /**
     * The multi-request success envelope: {@code data.upstream} is the ordered array of the
     * invocation's completed pages — one {@code request_index}/{@code meta}/{@code body}
     * entry per request, in request order.
     */
    public <R extends PagedExchange> byte[] encodeSuccess(
            String command,
            Projection projection,
            List<PagedSearch.Page<R>> upstream,
            RequestMeta meta,
            List<String> warnings,
            boolean pretty) {
        ArrayNode entries = JsonDocuments.NODES.arrayNode();
        for (PagedSearch.Page<R> page : upstream) {
            ObjectNode element = entries.addObject();
            element.put("request_index", page.requestIndex());
            PagedExchange result = page.result();
            appendMeta(
                    element.putObject("meta"),
                    new RequestMeta(
                            result.requestId(),
                            result.httpStatus(),
                            result.apiVersion(),
                            result.rateLimits().windows(),
                            result.usage()),
                    page.waited() == null ? null : page.waited().toMillis());
            element.set("body", upstreamTree(result.body()));
        }
        ObjectNode root = successRoot(command, projection, entries, meta, warnings);
        return terminated(mappers.outputMapper(), root, pretty);
    }

    public byte[] encodeFailure(
            String command, FailureKind kind, String message, boolean retryable, String upstreamCode, RequestMeta meta) {
        return encodeFailure(command, kind, message, retryable, upstreamCode, meta, false);
    }

    /** The failure envelope, in the compact form or the stable pretty form of {@code pretty}. */
    public byte[] encodeFailure(
            String command,
            FailureKind kind,
            String message,
            boolean retryable,
            String upstreamCode,
            RequestMeta meta,
            boolean pretty) {
        return encodeFailure(command, kind, message, retryable, upstreamCode, meta, null, pretty);
    }

    /**
     * The failure envelope with structured {@code error.details}: the multi-request
     * pagination counts ride as the ordered fields of {@code details} (a {@code null} list
     * keeps the detail-free document).
     */
    public byte[] encodeFailure(
            String command,
            FailureKind kind,
            String message,
            boolean retryable,
            String upstreamCode,
            RequestMeta meta,
            List<Projection.Field> details,
            boolean pretty) {
        return encodeFailure(command, codeFor(kind), message, retryable, upstreamCode, meta, details, pretty);
    }

    /**
     * The failure envelope with an explicit wire code — the streaming family's form, whose
     * user-interruption ending carries no failure category and therefore supplies its own
     * code instead of deriving one from a kind.
     */
    public byte[] encodeFailure(
            String command,
            String code,
            String message,
            boolean retryable,
            String upstreamCode,
            RequestMeta meta,
            List<Projection.Field> details,
            boolean pretty) {
        ObjectNode root = JsonDocuments.NODES.objectNode();
        root.put("schema_version", SCHEMA_VERSION);
        root.put("ok", false);
        root.put("command", command);
        ObjectNode error = root.putObject("error");
        error.put("code", code);
        error.put("message", message);
        error.put("retryable", retryable);
        JsonDocuments.putNullableString(error, "upstream_code", upstreamCode);
        if (details == null) {
            error.putNull("details");
        } else {
            error.set("details", ProjectionNodes.toObject(new Projection(details).fields(), JsonDocuments.NODES));
        }
        ObjectNode metaObject = root.putObject("meta");
        metaObject.put("http_status", meta.httpStatus());
        appendWindows(metaObject.putArray("rate_limits"), meta.rateLimits());
        return terminated(mappers.outputMapper(), root, pretty);
    }

    private static String codeFor(FailureKind kind) {
        return ErrorCodes.codeFor(kind);
    }

    private ObjectNode successRoot(
            String command, Projection projection, JsonNode upstream, RequestMeta meta, List<String> warnings) {
        ObjectNode root = JsonDocuments.NODES.objectNode();
        root.put("schema_version", SCHEMA_VERSION);
        root.put("ok", true);
        root.put("command", command);
        ObjectNode data = root.putObject("data");
        data.set("projection", ProjectionNodes.toObject(projection.fields(), JsonDocuments.NODES));
        data.set("upstream", upstream);
        ObjectNode metaObject = root.putObject("meta");
        appendMeta(metaObject, meta, null);
        ArrayNode warningArray = metaObject.putArray("warnings");
        warnings.forEach(warningArray::add);
        return root;
    }

    private static void appendMeta(ObjectNode metaObject, RequestMeta meta, Long waitedMs) {
        JsonDocuments.putNullableString(metaObject, "request_id", meta.requestId());
        metaObject.put("http_status", meta.httpStatus());
        JsonDocuments.putNullableString(metaObject, "api_version", meta.apiVersion());
        appendWindows(metaObject.putArray("rate_limits"), meta.rateLimits());
        appendUsage(metaObject, meta.usage());
        if (waitedMs != null) {
            metaObject.put("waited_ms", waitedMs);
        }
    }

    private static byte[] terminated(JsonMapper mapper, ObjectNode root, boolean pretty) {
        return pretty
                ? JsonDocuments.prettyLfTerminated(mapper, root)
                : JsonDocuments.lfTerminated(mapper, root);
    }

    private JsonNode upstreamTree(UpstreamPayload upstream) {
        try {
            JsonNode tree = mappers.upstreamReader().readTree(upstream.toByteArray());
            if (tree == null || tree.isMissingNode()) {
                throw new UnreadableBodyException("upstream body is not a JSON document: the body is empty");
            }
            return tree;
        } catch (JacksonException e) {
            throw new UnreadableBodyException(
                    "upstream body is not exactly one JSON document: " + e.getClass().getSimpleName());
        }
    }

    private static void appendWindows(ArrayNode rateLimits, List<RateLimitWindow> windows) {
        rateLimits.addAll(JsonDocuments.windowsArray(windows));
    }

    private static void appendUsage(ObjectNode metaObject, Usage usage) {
        if (usage == null) {
            metaObject.putNull("usage");
            return;
        }
        ObjectNode usageObject = metaObject.putObject("usage");
        JsonDocuments.putNullableLong(usageObject, "requests", usage.requests());
        JsonDocuments.putNullableLong(usageObject, "queries", usage.queries());
        JsonDocuments.putNullableLong(usageObject, "tokens_in", usage.tokensIn());
        JsonDocuments.putNullableLong(usageObject, "tokens_out", usage.tokensOut());
        putNullableDecimal(usageObject, "requests_cost", usage.requestsCost());
        putNullableDecimal(usageObject, "queries_cost", usage.queriesCost());
        putNullableDecimal(usageObject, "tokens_in_cost", usage.tokensInCost());
        putNullableDecimal(usageObject, "tokens_out_cost", usage.tokensOutCost());
        putNullableDecimal(usageObject, "total_cost", usage.totalCost());
        if (usage.unknownFields().isEmpty()) {
            usageObject.putNull("unknown");
        } else {
            ObjectNode unknownObject = usageObject.putObject("unknown");
            usage.unknownFields().forEach(unknownObject::put);
        }
    }

    private static void putNullableDecimal(ObjectNode object, String key, java.math.BigDecimal value) {
        if (value == null) {
            object.putNull(key);
        } else {
            object.put(key, value);
        }
    }
}

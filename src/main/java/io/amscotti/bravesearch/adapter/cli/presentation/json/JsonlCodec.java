package io.amscotti.bravesearch.adapter.cli.presentation.json;

import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.metadata.RateLimitWindow;
import io.amscotti.bravesearch.domain.metadata.Usage;
import io.amscotti.bravesearch.domain.result.Projection;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Encodes JSON Lines records.
 *
 * <p>Every line is one independent, compact JSON object whose leading members are exactly
 * {@code schema_version, type, command} followed by the record payload, terminated by one LF.
 * Lines never contain headings, ANSI escapes, or any other human decoration.
 */
public final class JsonlCodec {

    private final JsonMappers mappers;

    public JsonlCodec(JsonMappers mappers) {
        this.mappers = mappers;
    }

    public byte[] encodeResult(String command, List<Projection.Field> fields) {
        return encodeFields(JsonlRecordTypes.RESULT, command, fields);
    }

    /** One streamed answer-text increment, sequenced from zero in emission order. */
    public byte[] encodeAnswerDelta(String command, long sequence, String text) {
        ObjectNode line = base(JsonlRecordTypes.ANSWER_DELTA, command);
        line.put("sequence", sequence);
        line.put("text", text);
        return JsonDocuments.lfTerminated(mappers.outputMapper(), line);
    }

    /** One streamed citation with its projected members. */
    public byte[] encodeStreamCitation(String command, List<Projection.Field> citation) {
        return encodeFields(JsonlRecordTypes.CITATION, command, citation);
    }

    /** One streamed entity with its projected members. */
    public byte[] encodeStreamEntity(String command, List<Projection.Field> entity) {
        return encodeFields(JsonlRecordTypes.ENTITY, command, entity);
    }

    /** One research progress event: the tag that carried it beside its parsed payload tree. */
    public byte[] encodeResearchProgress(String command, String tag, JsonNode payload) {
        ObjectNode line = base(JsonlRecordTypes.RESEARCH_PROGRESS, command);
        line.put("tag", tag);
        line.set("payload", payload);
        return JsonDocuments.lfTerminated(mappers.outputMapper(), line);
    }

    /** One preserved upstream event: the tag name and its raw payload text, verbatim. */
    public byte[] encodeUpstreamEvent(String command, String tag, String rawText) {
        return encodeUpstreamEvent(command, tag, rawText, null, null, null);
    }

    /**
     * One preserved upstream event with the server-sent-event envelope its block carried: the
     * tag name and raw payload text verbatim, plus {@code sse_id}, {@code sse_retry_ms}, and
     * {@code event_name} — each member present exactly when the block offered it and omitted
     * otherwise.
     */
    public byte[] encodeUpstreamEvent(
            String command, String tag, String rawText, String sseName, String sseId, Long sseRetryMillis) {
        ObjectNode line = base(JsonlRecordTypes.UPSTREAM_EVENT, command);
        line.put("tag", tag);
        line.put("raw", rawText);
        if (sseId != null) {
            line.put("sse_id", sseId);
        }
        if (sseRetryMillis != null) {
            line.put("sse_retry_ms", sseRetryMillis);
        }
        if (sseName != null) {
            line.put("event_name", sseName);
        }
        return JsonDocuments.lfTerminated(mappers.outputMapper(), line);
    }

    /**
     * The terminal summary of a successful stream: the run's counts and, when the stream
     * carried a final usage event, the observed usage — a {@code null} usage omits the
     * member entirely and the cost-unknown marker states why.
     */
    public byte[] encodeStreamSummary(
            String command, List<Projection.Field> counts, JsonNode usage, boolean costUnknown) {
        ObjectNode line = encodeFieldsNode(JsonlRecordTypes.SUMMARY, command, counts);
        if (usage != null) {
            line.set("usage", usage);
        }
        line.put("cost_unknown", costUnknown);
        return JsonDocuments.lfTerminated(mappers.outputMapper(), line);
    }

    /**
     * The terminal error of a failed stream: the failure code — the shared table's codes or
     * the interruption code of a user's SIGINT — beside the partial progress counts and the
     * cost-unknown marker that keeps an unreported cost from reading as free. The failing
     * exchange observed no rate-limit snapshot, so the record carries none.
     */
    public byte[] encodeStreamError(
            String command, String code, String message, boolean retryable, long deltasEmitted, long citationsSeen, boolean costUnknown) {
        return encodeStreamError(command, code, message, retryable, deltasEmitted, citationsSeen, costUnknown, null);
    }

    /**
     * The terminal error of a failed stream with the open exchange's observed quota windows
     * — the same per-window rendering as the envelope's {@code meta.rate_limits} — present
     * exactly when a snapshot exists, so a rate-limited stream explains itself in-channel.
     */
    public byte[] encodeStreamError(
            String command,
            String code,
            String message,
            boolean retryable,
            long deltasEmitted,
            long citationsSeen,
            boolean costUnknown,
            List<RateLimitWindow> rateLimits) {
        ObjectNode line = base(JsonlRecordTypes.ERROR, command);
        line.put("code", code);
        line.put("message", message);
        line.put("retryable", retryable);
        line.put("deltas_emitted", deltasEmitted);
        line.put("citations_seen", citationsSeen);
        line.put("cost_unknown", costUnknown);
        if (rateLimits != null) {
            line.set("rate_limits", JsonDocuments.windowsArray(rateLimits));
        }
        return JsonDocuments.lfTerminated(mappers.outputMapper(), line);
    }

    /**
     * The usage members of a machine record — the same member set and order the envelope's
     * {@code meta.usage} carries, with {@code null} for every unobserved field — built as a
     * node so record payloads can carry it beside their own typed members.
     */
    public ObjectNode usageNode(Usage usage) {
        Objects.requireNonNull(usage, "usage");
        ObjectNode node = JsonDocuments.NODES.objectNode();
        JsonDocuments.putNullableLong(node, "requests", usage.requests());
        JsonDocuments.putNullableLong(node, "queries", usage.queries());
        JsonDocuments.putNullableLong(node, "tokens_in", usage.tokensIn());
        JsonDocuments.putNullableLong(node, "tokens_out", usage.tokensOut());
        putNullableDecimal(node, "requests_cost", usage.requestsCost());
        putNullableDecimal(node, "queries_cost", usage.queriesCost());
        putNullableDecimal(node, "tokens_in_cost", usage.tokensInCost());
        putNullableDecimal(node, "tokens_out_cost", usage.tokensOutCost());
        putNullableDecimal(node, "total_cost", usage.totalCost());
        if (usage.unknownFields().isEmpty()) {
            node.putNull("unknown");
        } else {
            ObjectNode unknown = node.putObject("unknown");
            usage.unknownFields().forEach(unknown::put);
        }
        return node;
    }

    public byte[] encodeSummary(String command, List<Projection.Field> fields) {
        return encodeFields(JsonlRecordTypes.SUMMARY, command, fields);
    }

    public byte[] encodeWarning(String command, String message) {
        ObjectNode line = base(JsonlRecordTypes.WARNING, command);
        line.put("message", message);
        return JsonDocuments.lfTerminated(mappers.outputMapper(), line);
    }

    public byte[] encodeError(String command, FailureKind kind, String message, boolean retryable) {
        return encodeError(command, kind, message, retryable, null);
    }

    /**
     * The error record with trailing count fields: the multi-request pagination run's
     * requested and received pages ride after {@code retryable} ({@code null} counts keep
     * the detail-free record).
     */
    public byte[] encodeError(
            String command, FailureKind kind, String message, boolean retryable, List<Projection.Field> counts) {
        return encodeError(command, kind, message, retryable, counts, null);
    }

    /**
     * The error record with the trailing count fields and the failing exchange's observed
     * quota windows: the counts ride after {@code retryable}, then the windows — the same
     * per-window rendering as the envelope's {@code meta.rate_limits} — close the record
     * exactly when a rate-limit snapshot exists, so a rate-limited exchange explains itself
     * in-channel ({@code null} keeps either trailing member absent).
     */
    public byte[] encodeError(
            String command,
            FailureKind kind,
            String message,
            boolean retryable,
            List<Projection.Field> counts,
            List<RateLimitWindow> rateLimits) {
        ObjectNode line = base(JsonlRecordTypes.ERROR, command);
        line.put("code", ErrorCodes.codeFor(kind));
        line.put("message", message);
        line.put("retryable", retryable);
        if (counts != null) {
            for (Projection.Field field : new Projection(counts).fields()) {
                line.set(field.key(), ProjectionNodes.toNode(field.value(), JsonDocuments.NODES));
            }
        }
        if (rateLimits != null) {
            line.set("rate_limits", JsonDocuments.windowsArray(rateLimits));
        }
        return JsonDocuments.lfTerminated(mappers.outputMapper(), line);
    }

    private byte[] encodeFields(String type, String command, List<Projection.Field> fields) {
        return JsonDocuments.lfTerminated(
                mappers.outputMapper(), encodeFieldsNode(type, command, fields));
    }

    private ObjectNode encodeFieldsNode(String type, String command, List<Projection.Field> fields) {
        ObjectNode line = base(type, command);
        for (Projection.Field field : fields) {
            if (Projection.RESERVED_FRAMING_KEYS.contains(field.key())) {
                throw new IllegalArgumentException(
                        "record field '" + field.key() + "' is reserved for the record framing");
            }
            line.set(field.key(), ProjectionNodes.toNode(field.value(), JsonDocuments.NODES));
        }
        return line;
    }

    private static void putNullableDecimal(ObjectNode object, String key, java.math.BigDecimal value) {
        if (value == null) {
            object.putNull(key);
        } else {
            object.put(key, value);
        }
    }

    private static ObjectNode base(String type, String command) {
        ObjectNode line = JsonDocuments.NODES.objectNode();
        line.put("schema_version", EnvelopeCodec.SCHEMA_VERSION);
        line.put("type", type);
        line.put("command", command);
        return line;
    }
}

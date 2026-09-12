package io.amscotti.bravesearch.adapter.bravehttp.json;

import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

/**
 * The tolerant upstream codec of the library path: bounded upstream body bytes parsed into
 * exactly one lossless {@link JsonNode} tree.
 *
 * <p>The reader mirrors the machine documents' upstream reader: decimals parse as {@code
 * BigDecimal} so upstream numbers round-trip value-exactly — trailing zeros and long
 * significands survive — and a body must be exactly one JSON document, because a snapshot
 * that silently kept only the first of two documents would lie about what the server sent.
 * Unknown members, deep structure, and any JSON value ride along unchanged; tolerance means
 * accepting every shape the server may evolve to, never inventing one it did not send. The
 * mapper is built once and never reconfigured, and this codec is the one the public library
 * boundary reads trees through, so the public API never owns a Jackson codec of its own; the
 * bravehttp stream pieces — SSE chunks, tag payloads, continuation probes, error bodies —
 * parse through this same codec instead of building private mappers.
 */
public final class UpstreamJsonCodec {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final tools.jackson.databind.ObjectReader UPSTREAM_READER = MAPPER.reader(
            DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private UpstreamJsonCodec() {}

    /**
     * Parses {@code body} into its one lossless upstream tree.
     *
     * @throws tools.jackson.core.JacksonException when the body is not exactly one JSON
     *     document; the caller at the public boundary maps this to its malformed-response
     *     failure
     * @throws NullPointerException when {@code body} is null
     */
    public static JsonNode readTree(UpstreamPayload body) {
        Objects.requireNonNull(body, "body");
        return UPSTREAM_READER.readTree(body.toByteArray());
    }

    /**
     * Parses one documented-tag payload into its lossless tree, parse-or-empty: a payload
     * that is not exactly one JSON document yields Jackson's missing node instead of an
     * exception, because a tag frame always flows with its exact text and never a broken
     * tree. This is the reader the public frame projection parses documented-tag payloads
     * through, so the public API never owns a Jackson codec of its own.
     *
     * @throws NullPointerException when {@code payload} is null
     */
    public static JsonNode readTagPayload(String payload) {
        Objects.requireNonNull(payload, "payload");
        try {
            return UPSTREAM_READER.readTree(payload);
        } catch (JacksonException unparseable) {
            return MissingNode.getInstance();
        }
    }

    /**
     * Parses the first JSON document of one loose upstream fragment — an SSE chunk payload or
     * a bounded error body — under the tolerant base mapper, throwing exactly when no document
     * parses at all. Stream-piece readers that only probe a tree's shape use this seam so the
     * code base constructs mappers only in the role codecs.
     *
     * @throws tools.jackson.core.JacksonException when the fragment is not parseable JSON
     * @throws NullPointerException when {@code json} is null
     */
    public static JsonNode readFirstDocument(byte[] json) {
        Objects.requireNonNull(json, "json");
        return MAPPER.readTree(json);
    }

    /**
     * The string form of {@link #readFirstDocument(byte[])}: the first JSON document of one
     * loose upstream fragment under the tolerant base mapper.
     *
     * @throws tools.jackson.core.JacksonException when the fragment is not parseable JSON
     * @throws NullPointerException when {@code payload} is null
     */
    public static JsonNode readFirstDocument(String payload) {
        Objects.requireNonNull(payload, "payload");
        return MAPPER.readTree(payload);
    }

    /**
     * The strict exactly-one-document judgment documented-tag payloads must satisfy: the same
     * reader settings the upstream body reader applies, so a payload that continues after its
     * first JSON document — trailing tokens, a second concatenated document — is malformed
     * everywhere this CLI parses upstream JSON, never silently truncated in one place and
     * rejected in another.
     *
     * @throws NullPointerException when {@code payload} is null
     */
    public static boolean isExactlyOneJsonDocument(String payload) {
        Objects.requireNonNull(payload, "payload");
        try {
            UPSTREAM_READER.readTree(payload);
            return true;
        } catch (JacksonException notExactlyOne) {
            return false;
        }
    }
}

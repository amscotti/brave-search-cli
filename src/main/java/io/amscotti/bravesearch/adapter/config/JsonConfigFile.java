package io.amscotti.bravesearch.adapter.config;

import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.config.LocalConfigException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The v1 credential-file format codec: {@code {"schema_version":"1","api_key":"<token>"}}.
 *
 * <p>Strict on the way in: at most {@value #MAX_BYTES} bytes, valid UTF-8, exactly one JSON
 * object, no duplicate keys, and no trailing tokens; the schema version must be exactly the
 * string {@code "1"}, and every key other than {@code schema_version}/{@code api_key} is
 * ignored as explicitly forward-compatible. Failures never echo document content — any part of
 * a hostile file may carry secret-shaped bytes — so parse problems surface as bounded
 * {@link LocalConfigException}s that describe the violated invariant, not the input.
 *
 * <p>Jackson policy: this codec owns a distinct, private, immutable mapper inside {@code
 * adapter.config}, separate from the tolerant upstream decoder of {@code adapter.bravehttp.json}
 * and the stable machine-output encoder of {@code adapter.cli.presentation.json}, so its strict
 * parser settings can never affect another role's documents. The codec policy in {@code
 * docs/architecture.md} records this third mapper for the eventual mapper-containment rule.
 *
 * <p>Token bytes: the api key travels as a JSON string. Every valid {@link Credential} is
 * strictly round-tripping UTF-8 — the factory rejects any byte sequence that does not decode
 * and re-encode unchanged — so encoding is byte-exact: the token's own bytes come back from the
 * stored document (pinned by a multibyte test). The output bound always holds because a token
 * is at most 4096 bytes and JSON escaping expands each byte at most sixfold, well under
 * {@value #MAX_BYTES}.
 */
public final class JsonConfigFile {

    /** The largest config document, in bytes, that decoding accepts. */
    public static final int MAX_BYTES = 64 * 1024;

    /** The only schema version this codec reads and the one it always writes. */
    public static final String SCHEMA_VERSION = "1";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final JsonMapper mapper;

    private final ObjectReader reader;

    public JsonConfigFile() {
        this.mapper = JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        this.reader = mapper.reader(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /**
     * Decodes and validates the whole file as a v1 document, yielding the stored api key.
     *
     * @return the api key, empty when the document stores none
     * @throws LocalConfigException when the content is oversized, not one strict JSON object, or
     *     declares an unsupported schema version; the failure never carries document content
     */
    public Optional<String> decodeApiKey(byte[] content) {
        Objects.requireNonNull(content, "content");
        if (content.length > MAX_BYTES) {
            throw LocalConfigException.malformedFile("the config file exceeds the " + MAX_BYTES + "-byte bound");
        }
        JsonNode root = parse(content);
        if (!root.isObject()) {
            throw LocalConfigException.malformedFile("the config file is not a JSON object");
        }
        JsonNode version = root.get("schema_version");
        if (version == null) {
            throw LocalConfigException.malformedFile("the config file lacks schema_version");
        }
        if (!version.isTextual() || !SCHEMA_VERSION.equals(version.asString())) {
            throw LocalConfigException.unsupportedSchemaVersion(SCHEMA_VERSION);
        }
        JsonNode apiKey = root.get("api_key");
        if (apiKey == null) {
            return Optional.empty();
        }
        if (!apiKey.isTextual()) {
            throw LocalConfigException.malformedFile("api_key must be a JSON string");
        }
        return Optional.of(apiKey.asString());
    }

    /** Serializes the credential as the v1 document: compact, stable field order, one terminating LF. */
    public byte[] encode(Credential credential) {
        Objects.requireNonNull(credential, "credential");
        ObjectNode root = NODES.objectNode();
        root.put("schema_version", SCHEMA_VERSION);
        root.put("api_key", new String(credential.tokenBytes(), StandardCharsets.UTF_8));
        return lfTerminated(root);
    }

    /** Serializes the schema-only document of a cleared config, with the same termination. */
    public byte[] encodeCleared() {
        ObjectNode root = NODES.objectNode();
        root.put("schema_version", SCHEMA_VERSION);
        return lfTerminated(root);
    }

    private JsonNode parse(byte[] content) {
        try {
            JsonNode tree = reader.readTree(content);
            if (tree == null || tree.isMissingNode()) {
                throw LocalConfigException.malformedFile("the config file is empty");
            }
            return tree;
        } catch (JacksonException malformed) {
            throw LocalConfigException.malformedFile(
                    "the config file is not one strict JSON document (valid UTF-8, no duplicate keys, no trailing tokens)");
        }
    }

    private byte[] lfTerminated(ObjectNode root) {
        byte[] body = mapper.writeValueAsBytes(root);
        byte[] line = Arrays.copyOf(body, body.length + 1);
        line[body.length] = '\n';
        return line;
    }
}

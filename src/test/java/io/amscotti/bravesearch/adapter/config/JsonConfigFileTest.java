package io.amscotti.bravesearch.adapter.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.config.LocalConfigException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The v1 config document format: stable golden encodes, strict decodes (one object, valid
 * UTF-8, no duplicates, no trailing tokens, the 64 KiB bound, exactly schema version "1"),
 * forward-compatible unknown-field tolerance, byte-exact multibyte tokens, and failures that
 * never echo document content.
 */
final class JsonConfigFileTest {

    private final JsonConfigFile codec = new JsonConfigFile();

    @Test
    void encodesTheV1DocumentWithStableFieldOrderAndOneTerminatingLf() {
        byte[] document = codec.encode(credential("token-one"));
        assertArrayEquals(
                "{\"schema_version\":\"1\",\"api_key\":\"token-one\"}\n".getBytes(StandardCharsets.UTF_8), document);
    }

    @Test
    void encodesTheClearedDocumentWithTheSchemaVersionOnly() {
        assertArrayEquals(
                "{\"schema_version\":\"1\"}\n".getBytes(StandardCharsets.UTF_8), codec.encodeCleared());
    }

    @Test
    void decodesTheStoredApiKey() {
        Optional<String> apiKey = codec.decodeApiKey(configBytes("token-one"));
        assertEquals(Optional.of("token-one"), apiKey);
    }

    @Test
    void decodesAnAbsentApiKeyAsEmpty() {
        assertEquals(Optional.empty(), codec.decodeApiKey(configBytes(null)));
    }

    @Test
    void ignoresUnknownForwardCompatibleFields() {
        byte[] document = "{\"schema_version\":\"1\",\"api_key\":\"k\",\"future\":{\"a\":[1,2]},\"note\":\"x\"}"
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(Optional.of("k"), codec.decodeApiKey(document));
    }

    @Test
    void acceptsSurroundingWhitespaceAroundTheSingleObject() {
        byte[] document = " \n\t{\"schema_version\":\"1\",\"api_key\":\"k\"}\n ".getBytes(StandardCharsets.UTF_8);
        assertEquals(Optional.of("k"), codec.decodeApiKey(document));
    }

    @Test
    void rejectsDuplicateKeys() {
        byte[] document =
                "{\"schema_version\":\"1\",\"api_key\":\"a\",\"api_key\":\"b\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals(
                LocalConfigException.Reason.MALFORMED_FILE,
                assertThrows(LocalConfigException.class, () -> codec.decodeApiKey(document)).reason());
    }

    @Test
    void rejectsTrailingTokens() {
        byte[] document = "{\"schema_version\":\"1\",\"api_key\":\"a\"} {\"second\":1}".getBytes(StandardCharsets.UTF_8);
        assertEquals(
                LocalConfigException.Reason.MALFORMED_FILE,
                assertThrows(LocalConfigException.class, () -> codec.decodeApiKey(document)).reason());
    }

    @Test
    void rejectsAnEmptyDocument() {
        assertEquals(
                LocalConfigException.Reason.MALFORMED_FILE,
                assertThrows(LocalConfigException.class, () -> codec.decodeApiKey(new byte[0])).reason());
    }

    @Test
    void rejectsANonObjectDocument() {
        assertEquals(
                LocalConfigException.Reason.MALFORMED_FILE,
                assertThrows(
                        LocalConfigException.class,
                        () -> codec.decodeApiKey("[\"schema_version\"]".getBytes(StandardCharsets.UTF_8)))
                        .reason());
    }

    @Test
    void rejectsAMissingSchemaVersion() {
        byte[] document = "{\"api_key\":\"k\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals(
                LocalConfigException.Reason.MALFORMED_FILE,
                assertThrows(LocalConfigException.class, () -> codec.decodeApiKey(document)).reason());
    }

    @Test
    void rejectsANonStringSchemaVersion() {
        byte[] document = "{\"schema_version\":1,\"api_key\":\"k\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals(
                LocalConfigException.Reason.UNSUPPORTED_SCHEMA,
                assertThrows(LocalConfigException.class, () -> codec.decodeApiKey(document)).reason());
    }

    @Test
    void rejectsAnUnsupportedSchemaVersionWithoutEchoingIt() {
        String hostileVersion = sentinel();
        byte[] document = ("{\"schema_version\":\"" + hostileVersion + "\",\"api_key\":\"k\"}")
                .getBytes(StandardCharsets.UTF_8);
        LocalConfigException failure = assertThrows(LocalConfigException.class, () -> codec.decodeApiKey(document));
        assertEquals(LocalConfigException.Reason.UNSUPPORTED_SCHEMA, failure.reason());
        assertFalse(failure.getMessage().contains(hostileVersion), "the failure must not echo document content");
    }

    @Test
    void rejectsANonStringApiKey() {
        byte[] document = "{\"schema_version\":\"1\",\"api_key\":42}".getBytes(StandardCharsets.UTF_8);
        assertEquals(
                LocalConfigException.Reason.MALFORMED_FILE,
                assertThrows(LocalConfigException.class, () -> codec.decodeApiKey(document)).reason());
    }

    @Test
    void rejectsContentBeyondTheSizeBound() {
        byte[] padding = "A".repeat(JsonConfigFile.MAX_BYTES).getBytes(StandardCharsets.UTF_8);
        byte[] document = ("{\"schema_version\":\"1\",\"pad\":\"" + new String(padding, StandardCharsets.UTF_8)
                        + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        LocalConfigException failure = assertThrows(LocalConfigException.class, () -> codec.decodeApiKey(document));
        assertEquals(LocalConfigException.Reason.MALFORMED_FILE, failure.reason());
        assertTrue(failure.getMessage().contains(String.valueOf(JsonConfigFile.MAX_BYTES)), () -> failure.getMessage());
    }

    @Test
    void rejectsInvalidUtf8() {
        byte[] document = "{\"schema_version\":\"1\",\"api_key\":\"\uFFFD\"".getBytes(StandardCharsets.UTF_8);
        document[document.length - 3] = (byte) 0xFF;
        assertEquals(
                LocalConfigException.Reason.MALFORMED_FILE,
                assertThrows(LocalConfigException.class, () -> codec.decodeApiKey(document)).reason());
    }

    @Test
    void malformedContentNeverEchoesTheDocument() {
        String hostile = sentinel();
        byte[] document = ("{\"schema_version\":\"1\",\"api_key\":\"" + hostile + "\",}")
                .getBytes(StandardCharsets.UTF_8);
        LocalConfigException failure = assertThrows(LocalConfigException.class, () -> codec.decodeApiKey(document));
        assertFalse(failure.getMessage().contains(hostile), "the failure must not echo document content");
    }

    @Test
    void multibyteApiKeySurvivesEncodeAndDecodeByteForByte() {
        String token = "ключ-キー-\uD83E\uDDEA-key";
        byte[] encoded = codec.encode(credential(token));
        assertEquals(Optional.of(token), codec.decodeApiKey(encoded));
        assertArrayEquals(token.getBytes(StandardCharsets.UTF_8), codec.decodeApiKey(encoded).orElseThrow()
                .getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] configBytes(String apiKey) {
        String document = apiKey == null
                ? "{\"schema_version\":\"1\"}"
                : "{\"schema_version\":\"1\",\"api_key\":\"" + apiKey + "\"}";
        return document.getBytes(StandardCharsets.UTF_8);
    }

    private static Credential credential(String token) {
        return Credential.of(token.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A unique random-looking sentinel per invocation: it is embedded in document material under
     * test, so any appearance in a failure message is a leak of file content.
     */
    private static String sentinel() {
        return "BSK" + Long.toUnsignedString(UUID.randomUUID().getMostSignificantBits(), 36)
                + Long.toUnsignedString(UUID.randomUUID().getLeastSignificantBits(), 36);
    }
}

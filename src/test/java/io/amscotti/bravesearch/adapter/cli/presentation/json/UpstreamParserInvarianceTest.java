package io.amscotti.bravesearch.adapter.cli.presentation.json;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import io.amscotti.bravesearch.domain.metadata.RequestMeta;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.result.Projection;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The stable envelope is invariant under upstream parser configuration: the same upstream
 * bytes, parsed and re-encoded by differently-configured Jackson readers, always encode to
 * byte-identical envelopes, because the envelope's own value model — not the calling code's
 * reader settings — decides the wire form.
 */
final class UpstreamParserInvarianceTest {

    private static final JsonMapper PLAIN = JsonMapper.builder().build();

    private static final JsonMapper GRAMMAR_LENIENT = JsonMapper.builder()
            .enable(
                    JsonReadFeature.ALLOW_JAVA_COMMENTS,
                    JsonReadFeature.ALLOW_SINGLE_QUOTES,
                    JsonReadFeature.ALLOW_UNQUOTED_PROPERTY_NAMES)
            .build();

    private static final JsonMapper DECIMAL_EXACT = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    @Test
    void grammarToleranceOfTheParsingReaderCannotChangeTheEnvelope() {
        String upstream =
                "{\"trailing\":1.10,\"exponent\":1e2,\"long\":0.1000000000000000000001,\"text\":\"café\"}";

        byte[] fromPlain = envelopeOf(PLAIN, upstream);
        byte[] fromLenient = envelopeOf(GRAMMAR_LENIENT, upstream);

        assertArrayEquals(
                fromPlain,
                fromLenient,
                () -> "lenient reader settings changed the envelope:\n"
                        + new String(fromPlain, StandardCharsets.UTF_8) + "\n"
                        + new String(fromLenient, StandardCharsets.UTF_8));
    }

    @Test
    void numericModelOfTheParsingReaderCannotChangeTheEnvelopeForDecimalFreeDocuments() {
        String upstream = "{\"count\":42,\"flags\":[true,false],\"name\":\"cats\"}";

        byte[] fromDoubles = envelopeOf(PLAIN, upstream);
        byte[] fromBigDecimals = envelopeOf(DECIMAL_EXACT, upstream);

        assertArrayEquals(
                fromDoubles,
                fromBigDecimals,
                () -> "numeric model changed the envelope:\n"
                        + new String(fromDoubles, StandardCharsets.UTF_8) + "\n"
                        + new String(fromBigDecimals, StandardCharsets.UTF_8));
    }

    /** Parses {@code upstream} with {@code reader}, re-encodes the tree, and wraps it in a success envelope. */
    private static byte[] envelopeOf(JsonMapper reader, String upstream) {
        JsonNode tree = reader.readTree(upstream.getBytes(StandardCharsets.UTF_8));
        byte[] reencoded = reader.writeValueAsBytes(tree);
        return CodecSamples
                .envelopeCodec()
                .encodeSuccess(
                        "web",
                        Projection.empty(),
                        new UpstreamPayload(reencoded),
                        new RequestMeta(null, 200, null, List.of(), null),
                        List.of());
    }
}

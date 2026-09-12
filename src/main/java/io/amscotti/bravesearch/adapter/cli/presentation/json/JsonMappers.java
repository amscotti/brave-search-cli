package io.amscotti.bravesearch.adapter.cli.presentation.json;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/**
 * Owns the stable mappers of the machine documents.
 *
 * <p>Each mapper is built once and never reconfigured: every document is assembled as a tree
 * whose insertion order is the wire order, so alphabetical property sorting stays explicitly
 * disabled and field order can never drift between encodes. The upstream reader additionally
 * parses decimals as {@code BigDecimal} so upstream numbers round-trip value-exactly —
 * trailing zeros and long significands survive, exponents normalize to uppercase {@code E}
 * notation, and negative zero loses its sign — and it accepts only a body that is exactly one
 * JSON document. Final fields make post-construction mutation impossible.
 */
public final class JsonMappers {

    private final JsonMapper outputMapper;

    private final ObjectReader upstreamReader;

    public JsonMappers() {
        this.outputMapper = JsonMapper.builder()
                .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .build();
        this.upstreamReader = this.outputMapper
                .reader(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** The immutable mapper that serializes every machine document. */
    public JsonMapper outputMapper() {
        return outputMapper;
    }

    /** The immutable reader for upstream body bytes; see the class description for its guarantees. */
    public ObjectReader upstreamReader() {
        return upstreamReader;
    }
}

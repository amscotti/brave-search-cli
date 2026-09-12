package io.amscotti.bravesearch.adapter.cli.presentation.json;

import io.amscotti.bravesearch.domain.metadata.RateLimitWindow;
import java.util.Arrays;
import java.util.List;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Shared tree-building and line-termination helpers of the machine-output codecs. */
final class JsonDocuments {

    static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private JsonDocuments() {}

    /**
     * The quota windows of one observed snapshot, in the one rendering every machine
     * document shares: {@code policy}, {@code limit}, {@code remaining}, and the
     * whole-millisecond {@code reset_ms} — the envelope's {@code meta.rate_limits} and the
     * JSONL error record's {@code rate_limits} render through this single writer so the two
     * channels can never drift apart.
     */
    static ArrayNode windowsArray(List<RateLimitWindow> windows) {
        ArrayNode array = NODES.arrayNode();
        for (RateLimitWindow window : windows) {
            ObjectNode windowNode = array.addObject();
            windowNode.put("policy", window.policy());
            windowNode.put("limit", window.limit());
            windowNode.put("remaining", window.remaining());
            windowNode.put("reset_ms", window.reset().toMillis());
        }
        return array;
    }

    /** Serializes the document compactly and appends exactly one terminating LF. */
    static byte[] lfTerminated(JsonMapper mapper, ObjectNode document) {
        return lfAppended(mapper.writeValueAsBytes(document));
    }

    /**
     * Serializes the document with the stable pretty form: two-space indentation, LF line
     * breaks only, one space after the member colon, empty containers inline as {@code {}}
     * and {@code []}, and exactly one terminating LF. The pretty printer is built per call so
     * no shared mutable serialization state can drift between documents.
     */
    static byte[] prettyLfTerminated(JsonMapper mapper, ObjectNode document) {
        DefaultPrettyPrinter pretty = new DefaultPrettyPrinter(
                        Separators.createDefaultInstance()
                                .withObjectNameValueSpacing(Separators.Spacing.AFTER)
                                .withObjectEntrySpacing(Separators.Spacing.NONE)
                                .withArrayElementSpacing(Separators.Spacing.NONE)
                                .withObjectEmptySeparator("")
                                .withArrayEmptySeparator(""))
                .withArrayIndenter(new DefaultIndenter("  ", "\n"))
                .withObjectIndenter(new DefaultIndenter("  ", "\n"));
        return lfAppended(mapper.writer().with(pretty).writeValueAsBytes(document));
    }

    private static byte[] lfAppended(byte[] body) {
        byte[] line = Arrays.copyOf(body, body.length + 1);
        line[body.length] = '\n';
        return line;
    }

    static void putNullableString(ObjectNode object, String key, String value) {
        if (value == null) {
            object.putNull(key);
        } else {
            object.put(key, value);
        }
    }

    static void putNullableLong(ObjectNode object, String key, Long value) {
        if (value == null) {
            object.putNull(key);
        } else {
            object.put(key, value.longValue());
        }
    }
}

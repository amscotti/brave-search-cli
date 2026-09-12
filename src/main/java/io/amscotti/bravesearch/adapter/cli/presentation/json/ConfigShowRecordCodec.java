package io.amscotti.bravesearch.adapter.cli.presentation.json;

import io.amscotti.bravesearch.domain.config.ConfigShowView;
import java.util.Objects;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Encodes the one machine record of {@code config show --output json}: a single compact,
 * LF-terminated, schema-versioned object — the winning source, the resolved config path,
 * whether an effective credential exists, and the shadowed lower-precedence sources.
 *
 * <p>The record is derived entirely from the redaction-safe {@link ConfigShowView}, so no
 * rendering branch can ever carry credential material; its field inventory is normative in
 * {@code schemas/v1/config-show.schema.json}. The document borrows the envelope's schema
 * version and stable serialization without the envelope's framing: {@code config show} has
 * no exchange, so there is no {@code ok} or {@code meta} to carry — the record is the whole
 * document.
 */
public final class ConfigShowRecordCodec {

    /** The canonical dotted command name of {@code config show}. */
    public static final String COMMAND = "config.show";

    /** The shadowed-source label of the compatibility alias variable. */
    public static final String ALIAS_SOURCE = "BRAVE_SEARCH_API_KEY";

    private final JsonMappers mappers;

    public ConfigShowRecordCodec(JsonMappers mappers) {
        this.mappers = Objects.requireNonNull(mappers, "mappers");
    }

    /** The machine record of {@code view}, compact and terminated by exactly one LF. */
    public byte[] encode(ConfigShowView view) {
        Objects.requireNonNull(view, "view");
        ObjectNode root = JsonDocuments.NODES.objectNode();
        root.put("schema_version", EnvelopeCodec.SCHEMA_VERSION);
        root.put("command", COMMAND);
        root.put("source", view.sourceName());
        root.put("config_path", view.configPath().toString());
        root.put("api_key_present", !ConfigShowView.MISSING_SOURCE.equals(view.sourceName()));
        ArrayNode shadowed = root.putArray("shadowed");
        if (view.aliasShadowed()) {
            shadowed.add(ALIAS_SOURCE);
        }
        if (view.fileShadowed()) {
            shadowed.add(ConfigShowView.FILE_SOURCE);
        }
        return JsonDocuments.lfTerminated(mappers.outputMapper(), root);
    }
}

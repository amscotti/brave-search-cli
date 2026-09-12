package io.amscotti.bravesearch.adapter.cli.presentation.json;

import java.util.List;

/**
 * The wire {@code type} names of every JSON Lines record.
 *
 * <p>The table is complete even while only some types are encodable: a record type becomes
 * encodable when its payload contract and encoder exist, and the name table stays stable for
 * consumers. String constants avoid adapter-owned instances, keeping construction of concrete
 * adapters inside the composition roots.
 */
public final class JsonlRecordTypes {

    public static final String RESULT = "result";
    public static final String ANSWER_DELTA = "answer_delta";
    public static final String CITATION = "citation";
    public static final String ENTITY = "entity";
    public static final String RESEARCH_PROGRESS = "research_progress";
    public static final String UPSTREAM_EVENT = "upstream_event";
    public static final String WARNING = "warning";
    public static final String SUMMARY = "summary";
    public static final String ERROR = "error";

    private JsonlRecordTypes() {}

    /** Every record type wire name, in published order. */
    public static List<String> all() {
        return List.of(
                RESULT,
                ANSWER_DELTA,
                CITATION,
                ENTITY,
                RESEARCH_PROGRESS,
                UPSTREAM_EVENT,
                WARNING,
                SUMMARY,
                ERROR);
    }
}

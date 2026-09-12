package io.amscotti.bravesearch.adapter.cli.presentation;

import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.domain.output.OutputMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Routes advisory warnings to the channel the active output mode owns: human and raw write
 * them as stderr diagnostics, json collects them for the envelope's {@code meta.warnings},
 * and jsonl emits one structured {@code warning} record on stdout.
 *
 * <p>{@code --quiet} suppresses only the advisory human and raw diagnostics; machine records
 * — the collected json warnings and the streamed jsonl records — are never suppressed, and
 * failure explanations never travel through this router at all: they belong to the failure
 * contract of the mode, not to advisory routing.
 */
public final class ModeAwareWarnings {

    private final OutputMode mode;
    private final String command;
    private final DiagnosticsSink diagnostics;
    private final ResultWriter results;
    private final JsonlCodec jsonl;
    private final boolean quiet;
    private final List<String> collected;

    public ModeAwareWarnings(
            OutputMode mode,
            String command,
            DiagnosticsSink diagnostics,
            ResultWriter results,
            JsonlCodec jsonl,
            boolean quiet) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.command = Objects.requireNonNull(command, "command");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.results = Objects.requireNonNull(results, "results");
        this.jsonl = Objects.requireNonNull(jsonl, "jsonl");
        this.quiet = quiet;
        this.collected = new ArrayList<>();
    }

    /** Routes one advisory warning to the channel the active mode owns. */
    public void warning(String message) {
        Objects.requireNonNull(message, "message");
        switch (mode) {
            case HUMAN, RAW -> {
                if (!quiet) {
                    diagnostics.emit(message);
                }
            }
            case JSON -> collected.add(message);
            case JSONL -> results.write(jsonl.encodeWarning(command, message));
        }
    }

    /** The warnings collected for the json envelope's {@code meta.warnings}, in order. */
    public List<String> collected() {
        return List.copyOf(collected);
    }
}

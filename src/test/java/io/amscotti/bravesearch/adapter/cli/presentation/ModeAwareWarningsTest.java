package io.amscotti.bravesearch.adapter.cli.presentation;

import static io.amscotti.bravesearch.domain.output.OutputMode.HUMAN;
import static io.amscotti.bravesearch.domain.output.OutputMode.JSON;
import static io.amscotti.bravesearch.domain.output.OutputMode.JSONL;
import static io.amscotti.bravesearch.domain.output.OutputMode.RAW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.domain.output.OutputMode;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Mode-owned warning routing: every mode receives advisories on the channel it owns, and
 * --quiet suppresses only advisory human and raw diagnostics, never machine records.
 */
final class ModeAwareWarningsTest {

    private static final String WARNING = "rate limit window reset delayed this request";

    private record Channels(StringWriter diagnostics, ByteArrayOutputStream results) {
        DiagnosticsSink sink() {
            return new WriterDiagnosticsSink(diagnostics);
        }

        ResultWriter writer() {
            return new OutputStreamResultWriter(results);
        }
    }

    @Test
    void warningsRouteToTheChannelTheirModeOwns() {
        for (OutputMode mode : OutputMode.values()) {
            Channels channels = new Channels(new StringWriter(), new ByteArrayOutputStream());
            ModeAwareWarnings warnings = new ModeAwareWarnings(mode, "web", channels.sink(), channels.writer(), jsonlCodec(), false);
            warnings.warning(WARNING);
            switch (mode) {
                case HUMAN, RAW -> {
                    assertEquals(WARNING + "\n", channels.diagnostics().toString(), () -> mode + " advisories go to stderr");
                    assertEquals(0, channels.results().size(), () -> mode + " keeps stdout free of advisories");
                    assertEquals(List.of(), warnings.collected());
                }
                case JSON -> {
                    assertEquals(List.of(WARNING), warnings.collected(), "json mode collects warnings for meta.warnings");
                    assertEquals("", channels.diagnostics().toString(), "json mode writes no stderr advisory");
                    assertEquals(0, channels.results().size(), "json mode writes no stdout advisory");
                }
                case JSONL -> {
                    String record = new String(channels.results().toByteArray(), StandardCharsets.UTF_8);
                    assertTrue(
                            record.contains("\"type\":\"warning\"") && record.contains(WARNING),
                            () -> "jsonl mode emits one structured warning record: " + record);
                    assertTrue(record.endsWith("\n") && !record.endsWith("\n\n"), () -> "exactly one LF terminates the record");
                    assertEquals("", channels.diagnostics().toString());
                }
            }
        }
    }

    @Test
    void quietSuppressesOnlyAdvisoryDiagnostics() {
        for (OutputMode mode : List.of(HUMAN, RAW)) {
            Channels channels = new Channels(new StringWriter(), new ByteArrayOutputStream());
            ModeAwareWarnings warnings =
                    new ModeAwareWarnings(mode, "web", channels.sink(), channels.writer(), jsonlCodec(), true);
            warnings.warning(WARNING);
            assertEquals("", channels.diagnostics().toString(), () -> "--quiet suppresses " + mode + " advisories");
            assertEquals(0, channels.results().size());
        }
    }

    @Test
    void quietNeverSuppressesMachineWarningRecords() {
        Channels json = new Channels(new StringWriter(), new ByteArrayOutputStream());
        ModeAwareWarnings collected = new ModeAwareWarnings(JSON, "web", json.sink(), json.writer(), jsonlCodec(), true);
        collected.warning(WARNING);
        assertEquals(List.of(WARNING), collected.collected(), "--quiet never suppresses collected meta.warnings");

        Channels jsonl = new Channels(new StringWriter(), new ByteArrayOutputStream());
        ModeAwareWarnings streamed = new ModeAwareWarnings(JSONL, "web", jsonl.sink(), jsonl.writer(), jsonlCodec(), true);
        streamed.warning(WARNING);
        assertTrue(
                new String(jsonl.results().toByteArray(), StandardCharsets.UTF_8).contains(WARNING),
                "--quiet never suppresses a structured warning record");
    }

    private static JsonlCodec jsonlCodec() {
        return new JsonlCodec(new JsonMappers());
    }
}

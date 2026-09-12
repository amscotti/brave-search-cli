package io.amscotti.bravesearch.bootstrap;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Snapshot stability of every command's help text, rendered from the same assembled
 * command line the process runs and through the same stdout writer it installs, so each
 * command's golden records its full usage output — the whole option inventory included —
 * and an unannounced grammar change fails with a diff, not a surprise. The pinned
 * invariants ride along: the hidden {@code --base-url}
 * never appears, every positional-accepting command documents the {@code --} separator in
 * its own parameter description, the output options appear only where the grammar accepts
 * them (the remote-only set under remote commands, and exactly {@code --output human|json}
 * under {@code config show}), and the documented numeric limits stay visible where users
 * pick values.
 *
 * <p>A missing golden is written and reported as created rather than compared, so adding a
 * command starts as a reviewable new file; from then on the golden is law.
 */
final class HelpSnapshotTest {

    /** Every command path of the CLI, from the root down to each nested subcommand. */
    private static final List<String> COMMAND_PATHS =
            List.of(
                    "",
                    "web",
                    "news",
                    "videos",
                    "images",
                    "context",
                    "suggest",
                    "spellcheck",
                    "rich",
                    "answers",
                    "places",
                    "places search",
                    "places details",
                    "places describe",
                    "config",
                    "config set-key",
                    "config unset-key",
                    "config show",
                    "config repair-permissions",
                    "completion",
                    "version");

    /** The paths whose grammar accepts a positional argument that may look like an option. */
    private static final List<String> POSITIONAL_PATHS =
            List.of(
                    "web",
                    "news",
                    "videos",
                    "images",
                    "context",
                    "suggest",
                    "spellcheck",
                    "rich",
                    "answers",
                    "places search",
                    "places details",
                    "places describe");

    /** The paths whose grammar holds the remote-only output options. */
    private static final List<String> REMOTE_PATHS =
            List.of(
                    "web",
                    "news",
                    "videos",
                    "images",
                    "context",
                    "suggest",
                    "spellcheck",
                    "rich",
                    "answers",
                    "places search",
                    "places details",
                    "places describe");

    /** The paths whose grammar must stay free of the remote-only output options. */
    private static final List<String> LOCAL_PATHS =
            List.of("", "places", "config", "config set-key", "config unset-key", "config repair-permissions", "completion", "version");

    private static final Path GOLDEN_DIRECTORY = Path.of("src", "test", "resources", "help");

    @Test
    void everyCommandHelpMatchesItsGoldenSnapshot() throws IOException {
        Map<String, String> helps = renderedHelps();
        java.util.List<String> created = new java.util.ArrayList<>();
        for (String path : COMMAND_PATHS) {
            Path goldenFile = GOLDEN_DIRECTORY.resolve(goldenName(path));
            if (!Files.isRegularFile(goldenFile)) {
                Files.createDirectories(goldenFile.getParent());
                Files.writeString(goldenFile, helps.get(path), UTF_8);
                created.add(goldenFile.toString());
            }
        }
        assertTrue(
                created.isEmpty(),
                () -> "golden snapshots created for new commands — review each and keep them checked in: "
                        + created);
        for (String path : COMMAND_PATHS) {
            assertEquals(
                    Files.readString(GOLDEN_DIRECTORY.resolve(goldenName(path)), UTF_8),
                    helps.get(path),
                    () -> "help output changed for <" + path + ">: review the diff and refresh the golden"
                            + " only when the grammar change is intended");
        }
    }

    @Test
    void theHiddenBaseUrlOverrideAppearsInNoHelp() throws IOException {
        for (Map.Entry<String, String> help : renderedHelps().entrySet()) {
            assertFalse(
                    help.getValue().contains("--base-url"),
                    () -> "the hidden override leaked into the help of <" + help.getKey() + ">");
        }
    }

    @Test
    void everyPositionalAcceptingCommandDocumentsTheEndOfOptionsSeparator() throws IOException {
        for (String path : POSITIONAL_PATHS) {
            assertTrue(
                    renderedHelps().get(path).contains("use --"),
                    () -> "the help of <" + path + "> documents the -- separator at its positional");
        }
    }

    @Test
    void outputOptionsFollowTheRemoteCommandsAndLocalCommandsStayPlain() throws IOException {
        Map<String, String> helps = renderedHelps();
        for (String path : REMOTE_PATHS) {
            assertTrue(
                    helps.get(path).contains("--output"),
                    () -> "the remote command <" + path + "> shows its output option");
            assertTrue(
                    helps.get(path).contains("--timeout"),
                    () -> "the remote command <" + path + "> shows its timeout option");
        }
        for (String path : LOCAL_PATHS) {
            assertFalse(
                    helps.get(path).contains("--output"),
                    () -> "the local grammar of <" + path + "> holds no output option");
            assertFalse(
                    helps.get(path).contains("--pretty"),
                    () -> "the local grammar of <" + path + "> holds no pretty option");
        }
    }

    /** {@code config show} offers exactly its one machine mode: --output human|json and nothing else. */
    @Test
    void configShowOffersItsOneMachineModeAndNoOtherRemoteOption() throws IOException {
        String help = renderedHelps().get("config show");
        assertTrue(help.contains("--output"), "config show shows its output option");
        assertTrue(
                help.contains("human, or one json machine record"),
                "the option names exactly the modes config show accepts");
        assertFalse(help.contains("--pretty"), "config show keeps no pretty option");
        assertFalse(help.contains("--timeout"), "config show keeps no timeout option");
    }

    @Test
    void documentedNumericLimitsStayVisibleWhereUsersPickValues() throws IOException {
        Map<String, String> helps = renderedHelps();
        assertTrue(helps.get("places details").contains("up to 200"), "the place id budget stays visible");
        assertTrue(helps.get("places describe").contains("up to 200"), "the describe id budget stays visible");
        assertTrue(helps.get("web").contains("<1..10>"), "the page range stays visible in web help");
        assertTrue(helps.get("web").contains("--max-pages"), "the walk budget option stays visible in web help");
    }

    private static Map<String, String> renderedHelps() {
        java.util.LinkedHashMap<String, String> helps = new java.util.LinkedHashMap<>();
        for (String path : COMMAND_PATHS) {
            helps.put(path, renderedHelp(path));
        }
        return helps;
    }

    /**
     * The usage text of one command path, rendered through the production stdout writer the
     * process installs — the ISO-8859-1 passthrough of {@link StdoutWriters} — so a golden
     * holds exactly the bytes the shipped CLI prints: any help character that charset cannot
     * carry surfaces as {@code ?} here first, not as silent mangling at run time.
     */
    private static String renderedHelp(String path) {
        picocli.CommandLine root = new Main().assemble(new PrintWriter(new java.io.StringWriter()));
        picocli.CommandLine command = root;
        if (!path.isEmpty()) {
            for (String token : path.split(" ")) {
                command = command.getSubcommands().get(token);
                if (command == null) {
                    throw new IllegalStateException("no registered command at <" + path + ">");
                }
            }
        }
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (PrintWriter out = StdoutWriters.passthroughBytes(new PrintStream(captured, false, UTF_8))) {
            command.usage(out);
        }
        return captured.toString(UTF_8);
    }

    private static String goldenName(String path) {
        return path.isEmpty() ? "root.txt" : path.replace(' ', '_') + ".txt";
    }
}

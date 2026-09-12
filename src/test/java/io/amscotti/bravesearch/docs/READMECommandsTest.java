package io.amscotti.bravesearch.docs;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.testsupport.ProcessHarness;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Honesty gate of the README's console examples: every fenced {@code console} block declares
 * itself with a leading annotation comment — {@code # runnable: launcher} for the blocks this
 * test executes against the installed launcher, or {@code # illustrative: <reason>} for the
 * blocks that need network, a credential, or a mutation of the reader's machine — and the
 * runnable blocks are actually run.
 *
 * <p>A runnable block's commands must stay inside the offline grammar — help, version, and
 * shell completion — so the gate can never be satisfied by an example that quietly spends a
 * request or writes the reader's configuration. Each runnable command runs through the
 * installed launcher with every credential variable scrubbed from its environment and must
 * exit {@code 0} with output, exactly as a reader copying the block would experience it. The
 * coverage assertions keep one runnable example attached to every command family, so a family
 * can never regress into illustrative-only examples.
 */
final class READMECommandsTest {

    private static final Path README = Path.of("README.md");

    private static final String RUNNABLE_ANNOTATION = "# runnable: launcher";

    private static final Pattern ILLUSTRATIVE_ANNOTATION = Pattern.compile("^# illustrative: \\S.*$");

    /** The credential-bearing variables scrubbed from every runnable example's environment. */
    private static final Set<String> CREDENTIAL_VARIABLES =
            Set.of("BRAVE_API_KEY", "BRAVE_SEARCH_API_KEY", "BRAVE_SEARCH_TEST_KEY");

    /**
     * Every command family the README's runnable examples must cover: one verified help path
     * per family keeps each usage section honest on its own.
     */
    private static final Set<String> COMMAND_FAMILIES =
            Set.of("web", "news", "videos", "images", "suggest", "spellcheck", "places", "rich", "answers", "context");

    /** One parsed console block: its README line number, annotation, and {@code $} commands. */
    private record Block(int lineNumber, String annotation, List<String> commands) {}

    @Test
    void everyConsoleBlockDeclaresRunnableOrIllustrativeWithItsLineNumber() throws IOException {
        List<String> undeclared = new ArrayList<>();
        for (Block block : blocks()) {
            if (block.annotation() == null) {
                undeclared.add("README.md:" + block.lineNumber());
            }
        }
        assertTrue(
                undeclared.isEmpty(),
                "every fenced console block must open with '# runnable: launcher' or"
                        + " '# illustrative: <reason>': " + undeclared);
        assertFalse(blocks().isEmpty(), "the README must carry at least one console block");
    }

    @Test
    void illustrativeAnnotationsCarryAReason() throws IOException {
        List<String> reasonless = new ArrayList<>();
        for (Block block : blocks()) {
            String annotation = block.annotation();
            if (annotation != null
                    && !RUNNABLE_ANNOTATION.equals(annotation)
                    && !ILLUSTRATIVE_ANNOTATION.matcher(annotation).matches()) {
                reasonless.add("README.md:" + block.lineNumber() + " carries " + annotation);
            }
        }
        assertTrue(reasonless.isEmpty(), "unknown or reason-less annotations: " + reasonless);
    }

    @Test
    void runnableBlocksStayInsideTheOfflineGrammarAndExitZero() throws IOException {
        Path launcher = launcher();
        ProcessHarness harness = new ProcessHarness();
        List<String> verified = new ArrayList<>();
        for (Block block : blocks()) {
            if (!RUNNABLE_ANNOTATION.equals(block.annotation())) {
                continue;
            }
            assertFalse(block.commands().isEmpty(), "README.md:" + block.lineNumber() + " declares runnable but shows no $ command");
            for (String command : block.commands()) {
                List<String> arguments = offlineArgumentsOf(command, block.lineNumber());
                List<String> invocation = new ArrayList<>();
                invocation.add(launcher.toString());
                invocation.addAll(arguments);
                ProcessHarness.ProcessResult result =
                        harness.launch(invocation, Map.of(), new byte[0], CREDENTIAL_VARIABLES);
                assertEquals(
                        0,
                        result.exitStatus(),
                        "a README example must run as printed: " + command + " -> " + result);
                assertTrue(
                        result.stdout().length > 0,
                        "a README example must print something on stdout: " + command);
                verified.add(String.join(" ", arguments));
            }
        }
        assertFalse(verified.isEmpty(), "the README must verify at least one command");
    }

    @Test
    void runnableExamplesCoverEveryCommandFamilyAndTheOfflineSurfaces() throws IOException {
        Set<String> families = new LinkedHashSet<>();
        boolean rootHelp = false;
        boolean version = false;
        boolean bashCompletion = false;
        boolean zshCompletion = false;
        for (Block block : blocks()) {
            if (!RUNNABLE_ANNOTATION.equals(block.annotation())) {
                continue;
            }
            for (String command : block.commands()) {
                List<String> arguments = offlineArgumentsOf(command, block.lineNumber());
                String joined = String.join(" ", arguments);
                families.addAll(Arrays.asList(joined.split(" ")));
                if (arguments.isEmpty() || joined.equals("--help") || joined.equals("-h")) {
                    rootHelp = true;
                }
                if (joined.equals("--version") || joined.equals("-V") || joined.equals("version")) {
                    version = true;
                }
                if (joined.equals("completion bash")) {
                    bashCompletion = true;
                }
                if (joined.equals("completion zsh")) {
                    zshCompletion = true;
                }
            }
        }
        Set<String> missing = new LinkedHashSet<>(COMMAND_FAMILIES);
        missing.removeAll(families);
        assertTrue(missing.isEmpty(), "command families without a runnable README example: " + missing);
        assertTrue(rootHelp, "the README must verify the root help path");
        assertTrue(version, "the README must verify a version spelling");
        assertTrue(bashCompletion, "the README must verify the bash completion path");
        assertTrue(zshCompletion, "the README must verify the zsh completion path");
    }

    /**
     * The arguments of one printed {@code $ brave-search ...} command, enforced against the
     * offline grammar: bare root, root help/version spellings, any command path ending in a
     * help flag (help never dispatches), and the two completion spellings.
     */
    private static List<String> offlineArgumentsOf(String command, int lineNumber) {
        List<String> tokens = Arrays.stream(command.split("\\s+"))
                .map(String::strip)
                .filter(token -> !token.isEmpty())
                .toList();
        assertFalse(
                tokens.isEmpty(),
                "README.md:" + lineNumber + " prints a command that does not start with brave-search: " + command);
        assertEquals(
                "brave-search",
                tokens.get(0),
                "README.md:" + lineNumber + " prints a command that does not start with brave-search: " + command);
        List<String> arguments = tokens.subList(1, tokens.size());
        String joined = String.join(" ", arguments);
        boolean offline = arguments.isEmpty()
                || joined.equals("--help")
                || joined.equals("-h")
                || joined.equals("--version")
                || joined.equals("-V")
                || joined.equals("version")
                || joined.equals("completion bash")
                || joined.equals("completion zsh")
                || arguments.get(arguments.size() - 1).equals("--help")
                || arguments.get(arguments.size() - 1).equals("-h");
        assertTrue(
                offline,
                "README.md:" + lineNumber
                        + " declares '# runnable: launcher' but prints a command outside the offline"
                        + " grammar (help, version, completion): " + command);
        return arguments;
    }

    /** The installed launcher the build injects for whole-process JVM tests. */
    private static Path launcher() {
        String launcherProperty = System.getProperty("brave.search.jvm.launcher");
        assertNotNull(launcherProperty, "brave.search.jvm.launcher must be injected by the build");
        Path launcher = Path.of(launcherProperty);
        assertTrue(Files.isRegularFile(launcher), () -> "installed launcher missing: " + launcher);
        assertTrue(Files.isExecutable(launcher), () -> "installed launcher not executable: " + launcher);
        return launcher;
    }

    /** Every fenced console block with its README line number, annotation, and commands. */
    private static List<Block> blocks() throws IOException {
        List<String> lines = Files.readAllLines(README, UTF_8);
        List<Block> blocks = new ArrayList<>();
        int index = 0;
        while (index < lines.size()) {
            String line = lines.get(index);
            if (!line.strip().equals("```console")) {
                index++;
                continue;
            }
            int opening = ++index;
            String annotation = null;
            List<String> commands = new ArrayList<>();
            while (index < lines.size() && !lines.get(index).strip().equals("```")) {
                String body = lines.get(index);
                if (index == opening && !body.isBlank()) {
                    annotation = body.strip();
                }
                if (body.startsWith("$ ")) {
                    commands.add(body.substring(2));
                }
                index++;
            }
            blocks.add(new Block(opening, annotation, commands));
            index++;
        }
        return blocks;
    }
}

package io.amscotti.bravesearch.skills;

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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Honesty gate of the agent skill's console examples: every {@code $ brave-search ...}
 * command printed in {@code skills/brave-search/SKILL.md} is executed against the
 * installed launcher in a child environment with every credential variable scrubbed and
 * both {@code HOME} and the JVM's {@code user.home} pointed at an empty directory, so
 * the credential can resolve from nowhere and no example can ever dispatch a request.
 *
 * <p>Under that environment a fully valid invocation parses, passes every local
 * validation, and stops at the missing credential with exit {@code 3} and empty stdout
 * — the precedence contract decides the parse error (exit {@code 2}) before the
 * credential check, so exit {@code 3} is exactly the proof the example is grammatical:
 * every option belongs to its subcommand, every value sits inside its documented
 * bounds, and every exclusivity holds. An example that would exit {@code 2} names a
 * skill document that teaches an invocation the current command line rejects. The
 * coverage assertions keep one example attached to every remote command family, so no
 * family can regress into being described but never demonstrated.
 *
 * <p>A piped example contributes the invocation after its pipe: the credential storer
 * the skill teaches for exit {@code 3} runs under the same scrubbed environment, where
 * its empty stdin violates the one-line secret contract — the same local-configuration
 * exit {@code 3} with stderr only — so the one taught credential command is held to
 * this gate exactly like every search vertical instead of drifting unvalidated.
 */
final class SkillCommandsTest {

    private static final Path SKILL = Path.of("skills/brave-search/SKILL.md");

    /** The credential-bearing and config-locating variables scrubbed from every child. */
    private static final Set<String> SCRUBBED_VARIABLES =
            Set.of("BRAVE_API_KEY", "BRAVE_SEARCH_API_KEY", "BRAVE_SEARCH_TEST_KEY", "XDG_CONFIG_HOME");

    /** Every remote command family the skill's examples must exercise at least once. */
    private static final Set<String> COMMAND_FAMILIES =
            Set.of("web", "news", "videos", "images", "suggest", "spellcheck", "places", "rich", "answers", "context");

    /**
     * One printed {@code $ }-prefixed example line — carrying the gate's {@code
     * brave-search} invocation, alone or after a pipe — with its line number in the skill.
     */
    private record Example(int lineNumber, String printed, List<String> arguments) {}

    @Test
    void everyExampleParsesCleanlyAndStopsAtTheMissingCredential(@TempDir Path home) throws IOException {
        Path launcher = launcher();
        ProcessHarness harness = new ProcessHarness();
        List<Example> examples = examples();
        assertFalse(examples.isEmpty(), "the skill must print at least one example command");
        for (Example example : examples()) {
            Map<String, String> environment = isolatedEnvironment(home);
            ProcessHarness.ProcessResult result =
                    harness.launch(invocation(launcher, example.arguments()), environment, new byte[0], SCRUBBED_VARIABLES);
            assertEquals(
                    3,
                    result.exitStatus(),
                    () -> "the skill example must parse and stop at the missing credential (exit 3), not fail"
                            + " otherwise — skills/brave-search/SKILL.md:" + example.lineNumber() + " prints '"
                            + example.printed() + "' -> " + describe(result));
            assertEquals(
                    0,
                    result.stdout().length,
                    () -> "a credential stop explains itself on stderr with empty stdout — SKILL.md:"
                            + example.lineNumber() + " '" + example.printed() + "' -> " + describe(result));
        }
    }

    @Test
    void examplesCoverEveryRemoteCommandFamily() throws IOException {
        Set<String> families = new LinkedHashSet<>();
        for (Example example : examples()) {
            assertFalse(example.arguments().isEmpty(), "an example must carry arguments: " + example.printed());
            families.add(example.arguments().getFirst());
        }
        Set<String> missing = new LinkedHashSet<>(COMMAND_FAMILIES);
        missing.removeAll(families);
        assertTrue(missing.isEmpty(), "command families without a skill example: " + missing);
    }

    @Test
    void theSkillCarriesASubstantialExampleSet() throws IOException {
        assertTrue(
                examples().size() >= 20,
                "the skill's playbooks must demonstrate a substantial command set, not a token one: "
                        + examples().size());
    }

    /**
     * The arguments of the printed command's {@code brave-search} invocation, honoring
     * double- and single-quoted segments the way a shell would join them, so a quoted
     * query arrives as one positional. An unquoted pipe splits the line into pipeline
     * segments: the one starting with {@code brave-search} is the invocation the gate
     * executes, while the segments before it — the stdin producers of the piped
     * credential storer — belong to the shell and are never run. The tokenizer honors
     * no backslash escapes and expands no tildes or {@code $} variables — the skill
     * prints none inside the executed invocation — so an example that ever leaned on
     * one would tokenize to a different argv than a shell builds and fail the exit-3
     * gate loudly, never pass as silently parsed. A {@code $} inside the executed
     * segment is rejected outright: a shell would expand or empty it, so the printed
     * form is not the argv the gate could execute.
     */
    private static List<String> tokenize(String printed, int lineNumber) {
        List<String> invocation = null;
        for (List<String> segment : pipelineSegments(printed, lineNumber)) {
            assertFalse(
                    segment.isEmpty(),
                    () -> "SKILL.md:" + lineNumber + " prints a pipeline segment with no command: " + printed);
            if (segment.getFirst().equals("brave-search")) {
                assertTrue(
                        invocation == null,
                        () -> "SKILL.md:" + lineNumber + " pipes brave-search into brave-search: " + printed);
                invocation = segment;
            }
        }
        assertNotNull(
                invocation,
                () -> "SKILL.md:" + lineNumber + " prints a command that does not start with brave-search: " + printed);
        for (String token : invocation) {
            assertFalse(
                    token.contains("$"),
                    () -> "SKILL.md:" + lineNumber + " prints a '$' inside the executed brave-search segment: a"
                            + " shell would expand or empty it, so the gate cannot execute the command as"
                            + " printed — " + printed);
        }
        return invocation.subList(1, invocation.size());
    }

    /** The shell-tokenized segments of one printed command line, split at unquoted pipes. */
    private static List<List<String>> pipelineSegments(String printed, int lineNumber) {
        List<List<String>> segments = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inDouble = false;
        boolean inSingle = false;
        for (char character : printed.toCharArray()) {
            if (inDouble) {
                if (character == '"') {
                    inDouble = false;
                } else {
                    current.append(character);
                }
            } else if (inSingle) {
                if (character == '\'') {
                    inSingle = false;
                } else {
                    current.append(character);
                }
            } else if (character == '"') {
                inDouble = true;
            } else if (character == '\'') {
                inSingle = true;
            } else if (character == '|') {
                finishToken(tokens, current);
                segments.add(tokens);
                tokens = new ArrayList<>();
            } else if (Character.isWhitespace(character)) {
                finishToken(tokens, current);
            } else {
                current.append(character);
            }
        }
        assertTrue(!inDouble && !inSingle, () -> "SKILL.md:" + lineNumber + " prints an unterminated quote: " + printed);
        finishToken(tokens, current);
        segments.add(tokens);
        return segments;
    }

    private static void finishToken(List<String> tokens, StringBuilder current) {
        if (!current.isEmpty()) {
            tokens.add(current.toString());
            current.setLength(0);
        }
    }

    /** Every {@code $ }-prefixed command line of the skill's fenced console blocks. */
    private static List<Example> examples() throws IOException {
        List<Example> examples = new ArrayList<>();
        List<String> lines = Files.readAllLines(SKILL, UTF_8);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.startsWith("$ ")) {
                String printed = line.substring(2);
                examples.add(new Example(index + 1, printed, tokenize(printed, index + 1)));
            }
        }
        return examples;
    }

    private static List<String> invocation(Path launcher, List<String> arguments) {
        List<String> invocation = new ArrayList<>();
        invocation.add(launcher.toString());
        invocation.addAll(arguments);
        return invocation;
    }

    /**
     * A child environment whose credential variables are scrubbed by the harness and
     * whose home points at the empty test directory, so neither the environment
     * variables nor a config file can supply a key.
     */
    private static Map<String, String> isolatedEnvironment(Path home) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("HOME", home.toString());
        environment.put("JAVA_OPTS", "-Duser.home=" + home);
        return environment;
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

    private static String describe(ProcessHarness.ProcessResult result) {
        return "exitStatus=" + result.exitStatus() + ", stdout=<" + new String(result.stdout(), UTF_8)
                + ">, stderr=<" + new String(result.stderr(), UTF_8) + ">";
    }
}

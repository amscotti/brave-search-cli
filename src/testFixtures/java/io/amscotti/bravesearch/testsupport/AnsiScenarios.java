package io.amscotti.bravesearch.testsupport;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The ANSI-enablement scenarios of the human web listing, run against a whole CLI process
 * and a scripted loopback server. The positive proof runs under a real pseudo-terminal
 * with a colorful TERM and no disabler, where the heading must arrive wrapped in the bold
 * intensity escape; every negative keeps the same listing byte-plain: {@code NO_COLOR} under
 * the terminal, a colorful TERM behind a plain pipe with no terminal at all, and — the
 * stream-independence half — a console-attached process whose stdout alone leaves the
 * terminal through a pipe. Each scenario takes the command prefix that launches the web
 * command, so the JVM launcher and the native binary must show identical evidence.
 */
public final class AnsiScenarios extends SearchScenarios {

    /** The served one-result success body of every scenario. */
    private static final byte[] ONE_RESULT =
            ("{\"query\":{\"original\":\"three word query\"},\"web\":{\"results\":["
                            + "{\"title\":\"First Title\",\"url\":\"https://example.com/first\"}]}}")
                    .getBytes(UTF_8);

    /** The served one-result body whose description is long enough to wrap at narrow widths. */
    private static final byte[] LONG_DESCRIPTION_RESULT =
            ("{\"query\":{\"original\":\"three word query\"},\"web\":{\"results\":["
                            + "{\"title\":\"Long Title\",\"url\":\"https://example.com/first\","
                            + "\"description\":\"alpha beta gamma delta epsilon zeta eta theta\"}]}}")
                    .getBytes(UTF_8);

    private static final String SANDBOX_PREFIX = "brave-ansi-";

    private static final Set<String> SCRUBBED =
            Set.of(
                    "BRAVE_API_KEY",
                    "BRAVE_SEARCH_API_KEY",
                    "BRAVE_SEARCH_TEST_KEY",
                    "XDG_CONFIG_HOME",
                    "JAVA_OPTS",
                    "NO_COLOR",
                    "CLICOLOR",
                    "CLICOLOR_FORCE",
                    "COLUMNS",
                    "TERM");

    private AnsiScenarios() {}

    /** Under a real terminal the heading carries the bold intensity escape and nothing else. */
    public static void ptyRunEmitsTheBoldHeading(List<String> webCommand, String python) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = fixtureServer().start()) {
            PtyHarness pty = new PtyHarness(python);
            PtyHarness.PtyResult result =
                    pty.run(webArgs(webCommand, server), childEnvironment(sandbox, testKey, null), SCRUBBED, false);

            assertEquals(0, result.exitStatus(), () -> "a terminal run of the happy path succeeds");
            String output = new String(result.stdout(), UTF_8);
            assertTrue(
                    output.contains("\u001b[1mWeb results for: three word query\u001b[0m"),
                    "the heading arrives bold under a terminal");
            assertFalse(output.contains("\u001b[1m1."), "result lines stay unstyled");
        }
    }

    /** Under a real terminal the entry title arrives bold and the url line dim, byte-exactly. */
    public static void ptyRunStylesTheTitleBoldAndTheUrlDim(List<String> webCommand, String python) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = fixtureServer().start()) {
            PtyHarness pty = new PtyHarness(python);
            PtyHarness.PtyResult result =
                    pty.run(webArgs(webCommand, server), childEnvironment(sandbox, testKey, null), SCRUBBED, false);

            assertEquals(0, result.exitStatus(), () -> "a terminal run of the styled listing succeeds");
            String output = new String(result.stdout(), UTF_8);
            assertTrue(
                    output.contains("\u001b[1mFirst Title\u001b[0m"),
                    "the entry title arrives bold under a terminal");
            assertTrue(
                    output.contains("\u001b[2mhttps://example.com/first\u001b[0m"),
                    "the url line arrives dim under a terminal");
        }
    }

    /**
     * The piped run renders exactly the pseudo-terminal run's layout with zero ANSI bytes:
     * the styled listing minus its escapes — after normalizing the terminal's own
     * carriage-return line discipline — is byte-identical to the plain piped document.
     */
    public static void pipedRunMatchesThePtyLayoutWithZeroAnsiBytes(List<String> webCommand, String python)
            throws Exception {
        String testKey = freshToken();
        String plainListing = "Web results for: three word query\n"
                + "\n"
                + " 1  First Title\n"
                + "    https://example.com/first\n"
                + "1 result.\n";
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = fixtureServer().start()) {
            ProcessHarness.ProcessResult piped =
                    new ProcessHarness()
                            .launch(webArgs(webCommand, server), childEnvironment(sandbox, testKey, null), new byte[0], SCRUBBED);

            assertEquals(0, piped.exitStatus());
            String pipedText = new String(piped.stdout(), UTF_8);
            assertFalse(pipedText.contains("\u001b"), "a piped run never writes an escape byte");
            assertFalse(pipedText.contains("\r"), "a piped run keeps LF-only line endings");
            assertEquals(plainListing, pipedText, "the piped document is the plain layout byte-for-byte");

            PtyHarness pty = new PtyHarness(python);
            PtyHarness.PtyResult terminal =
                    pty.run(webArgs(webCommand, server), childEnvironment(sandbox, testKey, null), SCRUBBED, false);
            assertEquals(0, terminal.exitStatus(), () -> "the terminal run of the same listing succeeds");
            String normalized =
                    new String(terminal.stdout(), UTF_8).replace("\r\n", "\n").replaceAll("\u001b\\[[0-9;]*m", "");
            assertEquals(
                    plainListing,
                    normalized,
                    "the terminal run stripped of styles is the same layout byte-for-byte");
        }
    }

    /** {@code NO_COLOR} under the same terminal keeps the listing plain. */
    public static void ptyRunWithNoColorStaysPlain(List<String> webCommand, String python) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = fixtureServer().start()) {
            PtyHarness pty = new PtyHarness(python);
            PtyHarness.PtyResult result =
                    pty.run(webArgs(webCommand, server), childEnvironment(sandbox, testKey, "1"), SCRUBBED, false);

            assertEquals(0, result.exitStatus(), () -> "a NO_COLOR terminal run still succeeds");
            assertFalse(
                    new String(result.stdout(), UTF_8).contains("\u001b"),
                    "NO_COLOR under a real terminal writes no escape byte");
        }
    }

    /** A colorful TERM alone, behind a plain pipe with no terminal, keeps the listing plain. */
    public static void pipedRunWithColorfulTermStaysPlain(List<String> webCommand) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = fixtureServer().start()) {
            ProcessHarness.ProcessResult result = new ProcessHarness()
                    .launch(webArgs(webCommand, server), childEnvironment(sandbox, testKey, null), new byte[0], SCRUBBED);

            assertEquals(0, result.exitStatus());
            String output = new String(result.stdout(), UTF_8);
            assertFalse(output.contains("\u001b"), "a piped run never writes an escape byte");
            assertTrue(
                    output.startsWith("Web results for: three word query\n"),
                    "the plain listing still renders behind the pipe");
        }
    }

    /**
     * A console-attached process whose stdout alone leaves the terminal through a pipe keeps
     * the listing plain: the decision needs stdout itself proven terminal, not the console's
     * existence.
     */
    public static void ptyRunStdoutThroughPipeStaysPlain(List<String> webCommand, String python) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = fixtureServer().start()) {
            PtyHarness pty = new PtyHarness(python);
            PtyHarness.PtyResult result =
                    pty.run(webArgs(webCommand, server), childEnvironment(sandbox, testKey, null), SCRUBBED, true);

            assertEquals(0, result.exitStatus(), () -> "a piped-stdout terminal run still succeeds");
            String output = new String(result.stdout(), UTF_8);
            assertFalse(output.contains("\u001b"), "a non-terminal stdout never receives escapes");
            assertTrue(
                    output.startsWith("Web results for: three word query\n"),
                    "the piped stdout carries the plain listing");
        }
    }

    private static List<String> webArgs(List<String> webCommand, ScriptedSseServer server) {
        return java.util.stream.Stream.concat(
                        webCommand.stream(), java.util.stream.Stream.of("--base-url", server.baseUrl().toString(), "three word query"))
                .toList();
    }

    /**
     * The child environment of one ANSI scenario run: a private home, the loopback test key,
     * a colorful terminal type, and — when the scenario demands a disabler — {@code NO_COLOR}.
     */
    private static Map<String, String> childEnvironment(Sandbox sandbox, String testKey, String noColor) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("HOME", sandbox.directory.toString());
        environment.put("JAVA_OPTS", "-Duser.home=" + sandbox.directory);
        environment.put("TERM", "xterm-256color");
        if (noColor != null) {
            environment.put("NO_COLOR", noColor);
        }
        environment.put("BRAVE_SEARCH_TEST_KEY", testKey);
        return environment;
    }

    /**
     * An exported sane {@code COLUMNS} supplies the wrap width of the human listing, and an
     * out-of-range value falls back to the default: the same body wraps its description at
     * 40 columns under {@code COLUMNS=40} and stays one line under {@code COLUMNS=1000}.
     */
    public static void pipedRunsHonorAnExportedSaneColumnsValue(List<String> webCommand) throws Exception {
        String testKey = freshToken();
        try (Sandbox sandbox = Sandbox.create(SANDBOX_PREFIX);
                ScriptedSseServer server = longDescriptionServer().start()) {
            Map<String, String> narrow = childEnvironment(sandbox, testKey, null);
            narrow.put("COLUMNS", "40");
            ProcessHarness.ProcessResult wrapped =
                    new ProcessHarness().launch(webArgs(webCommand, server), narrow, new byte[0], SCRUBBED);

            assertEquals(0, wrapped.exitStatus());
            assertEquals(
                    "Web results for: three word query\n"
                            + "\n"
                            + " 1  Long Title\n"
                            + "    https://example.com/first\n"
                            + "    alpha beta gamma delta epsilon zeta\n"
                            + "    eta theta\n"
                            + "1 result.\n",
                    new String(wrapped.stdout(), UTF_8),
                    "an exported COLUMNS=40 wraps the description at the exported width");

            Map<String, String> insane = childEnvironment(sandbox, testKey, null);
            insane.put("COLUMNS", "1000");
            ProcessHarness.ProcessResult unwrapped =
                    new ProcessHarness().launch(webArgs(webCommand, server), insane, new byte[0], SCRUBBED);

            assertEquals(0, unwrapped.exitStatus());
            assertEquals(
                    "Web results for: three word query\n"
                            + "\n"
                            + " 1  Long Title\n"
                            + "    https://example.com/first\n"
                            + "    alpha beta gamma delta epsilon zeta eta theta\n"
                            + "1 result.\n",
                    new String(unwrapped.stdout(), UTF_8),
                    "an out-of-range COLUMNS leaves the default width of 100");
        }
    }

    private static ScriptedSseServer.Builder longDescriptionServer() {
        return ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .writeBytes(LONG_DESCRIPTION_RESULT);
    }

    private static ScriptedSseServer.Builder fixtureServer() {
        return ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .writeBytes(ONE_RESULT);
    }
}

package io.amscotti.bravesearch.testsupport;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The whole-process config-command scenarios, shared by the JVM process suite and the native
 * smoke suite: the one-line stdin contract stores an owner-only file that show reports
 * without ever carrying token material, the machine record of the file source, the missing
 * console-and-stdin exit status, garbage input that leaves the prior file byte-intact, the
 * environment sources shadowing the file as documented, and the clean single-line failure of
 * a misconfigured {@code XDG_CONFIG_HOME}.
 *
 * <p>Every scenario takes the root command tokens that launch the CLI (the installed JVM
 * launcher or the native executable) plus the child environment and the credential-file path
 * that environment must resolve to, because the two substrates isolate the store differently:
 * the JVM launcher honors a {@code user.home} flag through {@code JAVA_OPTS}, while a
 * Linux-classified native image redirects the file through an absolute {@code
 * XDG_CONFIG_HOME}. Each scenario body copies the offered environment before adding its own
 * sources, so no scenario observes another's variables.
 */
public final class ConfigScenarios {

    /** Inherited variables scrubbed from every child so ambient credentials cannot interfere. */
    private static final Set<String> SCRUBBED =
            Set.of("BRAVE_API_KEY", "BRAVE_SEARCH_API_KEY", "BRAVE_SEARCH_TEST_KEY", "XDG_CONFIG_HOME", "JAVA_OPTS");

    private static final ProcessHarness HARNESS = new ProcessHarness();

    private ConfigScenarios() {}

    /**
     * The one-line stdin contract stores the offered secret as an owner-only file, and show
     * reports the file source without ever carrying token material.
     */
    public static void setKeyStdinWritesAnOwnerOnlyFileAndShowReportsTheFileSource(
            List<String> rootCommand, Map<String, String> environment, Path configPath) throws IOException {
        String sentinel = sentinel();

        ProcessHarness.ProcessResult stored =
                HARNESS.launch(command(rootCommand, "set-key", "--stdin"), environment, (sentinel + "\n").getBytes(UTF_8), SCRUBBED);

        assertEquals(0, stored.exitStatus(), () -> describe(stored, sentinel));
        assertEquals("api key stored in " + configPath + "\n", new String(stored.stdout(), UTF_8));
        assertEquals("", new String(stored.stderr(), UTF_8), () -> describe(stored, sentinel));
        assertTrue(Files.isRegularFile(configPath), "the credential file must exist after set-key");
        assertEquals(
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(configPath),
                "the stored credential file must be owner-only");
        assertEquals(
                "{\"schema_version\":\"1\",\"api_key\":\"" + sentinel + "\"}\n",
                Files.readString(configPath, UTF_8),
                "the stored document carries exactly the offered line");

        ProcessHarness.ProcessResult shown =
                HARNESS.launch(command(rootCommand, "show"), environment, new byte[0], SCRUBBED);

        assertEquals(0, shown.exitStatus(), () -> describe(shown, sentinel));
        assertEquals(
                "credential source: config file\n" + "config file: " + configPath + " (api key stored)\n",
                new String(shown.stdout(), UTF_8));
        assertNowhere(shown, sentinel);
        assertNowhere(stored, sentinel);
    }

    /**
     * The machine record describes exactly one stored file source, and the line channels stay
     * rejected for a one-record command.
     */
    public static void showOutputJsonEmitsOneMachineRecordAndRejectsTheLineChannels(
            List<String> rootCommand, Map<String, String> environment, Path configPath) throws IOException {
        String sentinel = sentinel();
        assertEquals(
                0,
                HARNESS.launch(
                                command(rootCommand, "set-key", "--stdin"),
                                environment,
                                (sentinel + "\n").getBytes(UTF_8),
                                SCRUBBED)
                        .exitStatus(),
                "the file source must exist before the machine record describes it");

        ProcessHarness.ProcessResult machine =
                HARNESS.launch(command(rootCommand, "show", "--output", "json"), environment, new byte[0], SCRUBBED);
        assertEquals(0, machine.exitStatus(), () -> describe(machine, sentinel));
        assertEquals(
                "{\"schema_version\":\"1\",\"command\":\"config.show\",\"source\":\"config file\","
                        + "\"config_path\":\""
                        + configPath
                        + "\","
                        + "\"api_key_present\":true,\"shadowed\":[]}\n",
                new String(machine.stdout(), UTF_8),
                () -> describe(machine, sentinel));
        assertEquals("", new String(machine.stderr(), UTF_8), () -> describe(machine, sentinel));
        assertNowhere(machine, sentinel);

        for (String rejected : List.of("jsonl", "raw")) {
            ProcessHarness.ProcessResult refused = HARNESS.launch(
                    command(rootCommand, "show", "--output", rejected),
                    environment,
                    new byte[0],
                    SCRUBBED);
            assertEquals(2, refused.exitStatus(), () -> rejected + ": " + describe(refused));
            assertEquals("", new String(refused.stdout(), UTF_8), () -> describe(refused));
            assertTrue(new String(refused.stderr(), UTF_8).contains("not accepted"), () -> describe(refused));
        }
    }

    /**
     * Without {@code --stdin} and without a console the command exits three with its
     * documented diagnostic; when the scenario owns the credential-file path, nothing may be
     * created by the refused run.
     */
    public static void setKeyWithoutAConsoleAndWithoutStdinExitsThree(
            List<String> rootCommand, Map<String, String> environment, Path configPath) throws IOException {
        ProcessHarness.ProcessResult result =
                HARNESS.launch(command(rootCommand, "set-key"), environment, new byte[0], SCRUBBED);

        assertEquals(3, result.exitStatus(), () -> describe(result));
        assertEquals("", new String(result.stdout(), UTF_8), () -> describe(result));
        String stderr = new String(result.stderr(), UTF_8);
        assertTrue(stderr.contains("local configuration error"), () -> describe(result));
        assertTrue(stderr.contains("--stdin"), () -> describe(result));
        assertFalse(Files.exists(configPath), "no file may be created without a readable secret");
    }

    /**
     * The console-and-stdin contract without a test-owned credential-file path: a substrate
     * that pins the path to the OS account's home cannot honor the no-file assertion, but the
     * exit status and diagnostic still prove the secret contract.
     */
    public static void setKeyWithoutAConsoleAndWithoutStdinExitsThree(
            List<String> rootCommand, Map<String, String> environment) throws IOException {
        ProcessHarness.ProcessResult result =
                HARNESS.launch(command(rootCommand, "set-key"), environment, new byte[0], SCRUBBED);

        assertEquals(3, result.exitStatus(), () -> describe(result));
        assertEquals("", new String(result.stdout(), UTF_8), () -> describe(result));
        String stderr = new String(result.stderr(), UTF_8);
        assertTrue(stderr.contains("local configuration error"), () -> describe(result));
        assertTrue(stderr.contains("--stdin"), () -> describe(result));
    }

    /** Garbage on stdin fails and leaves the prior stored file byte-intact. */
    public static void setKeyStdinGarbageFailsAndLeavesThePriorConfigByteIntact(
            List<String> rootCommand, Map<String, String> environment, Path configPath) throws IOException {
        String prior = sentinel();
        ProcessHarness.ProcessResult stored = HARNESS.launch(
                command(rootCommand, "set-key", "--stdin"), environment, (prior + "\n").getBytes(UTF_8), SCRUBBED);
        assertEquals(0, stored.exitStatus(), () -> describe(stored, prior));
        byte[] original = Files.readAllBytes(configPath);

        ProcessHarness.ProcessResult garbage =
                HARNESS.launch(
                        command(rootCommand, "set-key", "--stdin"),
                        environment,
                        ("rejected\u0007line" + prior + "\n").getBytes(UTF_8),
                        SCRUBBED);

        assertEquals(3, garbage.exitStatus(), () -> describe(garbage, prior));
        assertTrue(
                new String(garbage.stderr(), UTF_8).contains("local configuration error"),
                () -> describe(garbage, prior));
        assertTrue(Files.exists(configPath), "the prior file must survive a rejected input");
        assertEquals(original.length, Files.readAllBytes(configPath).length, "the prior file is byte-intact");
        assertEquals(
                "{\"schema_version\":\"1\",\"api_key\":\"" + prior + "\"}\n",
                Files.readString(configPath, UTF_8));
        assertNowhere(garbage, prior);
    }

    /** The canonical and alias environment variables shadow the stored file as documented. */
    public static void environmentSourcesShadowTheStoredFile(
            List<String> rootCommand, Map<String, String> environment, Path configPath) throws IOException {
        String fileSentinel = sentinel();
        String environmentSentinel = sentinel();
        assertEquals(
                0,
                HARNESS.launch(
                                command(rootCommand, "set-key", "--stdin"),
                                environment,
                                (fileSentinel + "\n").getBytes(UTF_8),
                                SCRUBBED)
                        .exitStatus(),
                "the file source must exist before it can be shadowed");

        Map<String, String> canonical = new LinkedHashMap<>(environment);
        canonical.put("BRAVE_API_KEY", environmentSentinel);
        ProcessHarness.ProcessResult canonicalShow =
                HARNESS.launch(command(rootCommand, "show"), canonical, new byte[0], SCRUBBED);

        assertEquals(0, canonicalShow.exitStatus(), () -> describe(canonicalShow, fileSentinel, environmentSentinel));
        assertEquals(
                "credential source: environment BRAVE_API_KEY\n"
                        + "config file: "
                        + configPath
                        + " (api key stored)\n"
                        + "shadowed: the config file api key is overridden\n",
                new String(canonicalShow.stdout(), UTF_8));

        Map<String, String> alias = new LinkedHashMap<>(environment);
        alias.put("BRAVE_SEARCH_API_KEY", environmentSentinel);
        ProcessHarness.ProcessResult aliasShow =
                HARNESS.launch(command(rootCommand, "show"), alias, new byte[0], SCRUBBED);

        assertEquals(0, aliasShow.exitStatus(), () -> describe(aliasShow, fileSentinel, environmentSentinel));
        assertEquals(
                "credential source: environment BRAVE_SEARCH_API_KEY\n"
                        + "config file: "
                        + configPath
                        + " (api key stored)\n"
                        + "shadowed: the config file api key is overridden\n",
                new String(aliasShow.stdout(), UTF_8));
        assertNowhere(canonicalShow, fileSentinel, environmentSentinel);
        assertNowhere(aliasShow, fileSentinel, environmentSentinel);
    }

    /**
     * A misconfigured {@code XDG_CONFIG_HOME} fails as one clean stderr line with the
     * documented exit status and no stack trace, on a child classified as Linux. The offered
     * environment must already carry the offending value and the Linux classification: the
     * JVM launcher takes both from its flags, a Linux-classified native image from its baked
     * operating-system name and the real environment.
     */
    public static void misconfiguredXdgExitsThreeWithOneCleanStderrLineAndNoStackTrace(
            List<String> rootCommand, Map<String, String> misconfiguredEnvironment) throws IOException {
        ProcessHarness.ProcessResult result =
                HARNESS.launch(command(rootCommand, "show"), misconfiguredEnvironment, new byte[0], SCRUBBED);

        assertEquals(3, result.exitStatus(), () -> describe(result));
        String stderr = new String(result.stderr(), UTF_8);
        assertEquals(1, stderr.lines().count(), "exactly one diagnostic line: " + describe(result));
        assertTrue(stderr.contains("XDG_CONFIG_HOME"), () -> describe(result));
        assertFalse(stderr.contains("Exception"), "no raw stack trace may reach stderr: " + describe(result));
        assertTrue(
                stderr.lines().noneMatch(line -> line.startsWith("\tat ") || line.startsWith(" at ")),
                "no stack-trace frame lines may reach stderr: " + describe(result));
        assertEquals("", new String(result.stdout(), UTF_8), () -> describe(result));
    }

    /** The version contract still runs under a misconfigured credential environment. */
    public static void versionStillRunsUnderAMisconfiguredCredentialEnvironment(
            List<String> rootCommand, Map<String, String> misconfiguredEnvironment) throws IOException {
        ProcessHarness.ProcessResult result = HARNESS.launch(
                java.util.stream.Stream.concat(rootCommand.stream(), java.util.stream.Stream.of("--version"))
                                .toList(),
                misconfiguredEnvironment,
                new byte[0],
                SCRUBBED);

        assertEquals(0, result.exitStatus(), () -> describe(result));
        assertTrue(
                new String(result.stdout(), UTF_8).startsWith("brave-search "),
                "the version contract survives a broken credential environment: " + describe(result));
        assertEquals("", new String(result.stderr(), UTF_8), () -> describe(result));
    }

    /** The root command plus the config subcommand tokens. */
    private static List<String> command(List<String> rootCommand, String... arguments) {
        return java.util.stream.Stream.concat(
                        rootCommand.stream(), java.util.stream.Stream.concat(java.util.stream.Stream.of("config"), java.util.stream.Stream.of(arguments)))
                .toList();
    }

    /** Byte-level absence over both streams is recursive absence for alphanumeric tokens. */
    private static void assertNowhere(ProcessHarness.ProcessResult result, String... sentinel) {
        for (String token : sentinel) {
            assertFalse(
                    new String(result.stdout(), UTF_8).contains(token), "token material leaked to child stdout");
            assertFalse(
                    new String(result.stderr(), UTF_8).contains(token), "token material leaked to child stderr");
        }
    }

    /** Command, exit status, and streams for reports, with every known token redacted first. */
    private static String describe(ProcessHarness.ProcessResult result, String... secrets) {
        String stdout = new String(result.stdout(), UTF_8);
        String stderr = new String(result.stderr(), UTF_8);
        for (String secret : secrets) {
            stdout = stdout.replace(secret, "<redacted>");
            stderr = stderr.replace(secret, "<redacted>");
        }
        return "exitStatus=" + result.exitStatus() + ", stdout=<" + stdout + ">, stderr=<" + stderr + ">";
    }

    /**
     * A unique random-looking sentinel per invocation: it is embedded in token material under
     * test, so any appearance in captured child output is a leak of the token itself.
     */
    private static String sentinel() {
        return "BSK" + Long.toUnsignedString(UUID.randomUUID().getMostSignificantBits(), 36)
                + Long.toUnsignedString(UUID.randomUUID().getLeastSignificantBits(), 36);
    }
}

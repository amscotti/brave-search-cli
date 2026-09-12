package io.amscotti.bravesearch.adapter.cli.command.config;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.presentation.OutputStreamResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.WriterDiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.json.ConfigShowRecordCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.application.port.out.ConfigStore;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.CredentialResolutionException;
import io.amscotti.bravesearch.application.port.out.ResolvedCredential;
import io.amscotti.bravesearch.domain.config.ConfigFileSight;
import io.amscotti.bravesearch.domain.config.ConfigFileSummary;
import io.amscotti.bravesearch.domain.config.ConfigState;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.config.InvalidCredentialException;
import io.amscotti.bravesearch.domain.config.LocalConfigException;
import io.amscotti.bravesearch.domain.config.PermissionRepair;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Human contracts of the config command group through injected ports: set-key stores the read
 * secret without ever echoing it, unset-key treats an absent or invalid key as a successful
 * no-op-or-removal, show renders source/path/shadowing while never carrying token material,
 * repair-permissions reports exactly what it changed, remote-only output flags are usage
 * errors, every local failure keeps the configuration exit status, and the result channel's
 * write verdicts hold: a downstream broken pipe is silent zero, any other stdout write
 * failure exits six with exactly one stderr line.
 */
final class ConfigCommandsTest {

    private static final Path CONFIG_PATH = Path.of("/tmp/hermetic/brave-search/config.json");

    @Test
    void setKeyStoresTheReadSecretUnderTheConfigPath() {
        String sentinel = sentinel();
        RecordingStore store = new RecordingStore();
        ScriptedSecrets secrets = ScriptedSecrets.delivering(sentinel);

        RunResult result = run(new Cli(secrets, store), "set-key");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(
                "api key stored in " + CONFIG_PATH + "\n", result.stdout(), "the success line is fixed human text");
        assertEquals(sentinel, new String(store.lastStored.tokenBytes(), UTF_8));
        assertNoLeak(result, sentinel);
    }

    @Test
    void setKeyRoutesToStdinOnlyWhenTheFlagIsGiven() {
        ScriptedSecrets withFlag = ScriptedSecrets.delivering(sentinel());
        ScriptedSecrets withoutFlag = ScriptedSecrets.delivering(sentinel());

        run(new Cli(withFlag, new RecordingStore()), "set-key", "--stdin");
        run(new Cli(withoutFlag, new RecordingStore()), "set-key");

        assertEquals(Boolean.TRUE, withFlag.lastFromStdin);
        assertEquals(Boolean.FALSE, withoutFlag.lastFromStdin);
    }

    @Test
    void setKeyWithoutAConsoleAndWithoutStdinFailsWithExit3AndNeverStores() {
        RecordingStore store = new RecordingStore();
        ScriptedSecrets secrets = ScriptedSecrets.failing(
                LocalConfigException.secretInput("no console delivered a secret and --stdin was not requested"));

        RunResult result = run(new Cli(secrets, store), "set-key");

        assertEquals(3, result.exitCode(), () -> result.describe());
        assertTrue(result.stderr().contains("local configuration error"), () -> result.describe());
        assertTrue(result.stderr().contains("console"), () -> result.describe());
        assertEquals(0, store.storeCalls, "an unreadable secret must never reach the store");
    }

    @Test
    void setKeyInvalidSecretLeavesTheConfigUntouched() {
        RecordingStore store = new RecordingStore();
        ScriptedSecrets secrets = ScriptedSecrets.failing(LocalConfigException.secretInput("the secret is rejected"));

        RunResult result = run(new Cli(secrets, store), "set-key", "--stdin");

        assertEquals(3, result.exitCode(), () -> result.describe());
        assertEquals(0, store.storeCalls, "a rejected secret must never reach the store");
        assertNull(store.lastStored);
    }

    @Test
    void setKeyStoreFailureFailsWithExit3() {
        FailingStore store = new FailingStore(LocalConfigException.insecureFile("the config file is a symbolic link"));

        RunResult result =
                run(new Cli(ScriptedSecrets.delivering(sentinel()), store), "set-key", "--stdin");

        assertEquals(3, result.exitCode(), () -> result.describe());
        assertTrue(result.stderr().contains("local configuration error"), () -> result.describe());
    }

    @Test
    void setKeyRejectsRemoteOutputFlagsAsUsageErrors() {
        RecordingStore store = new RecordingStore();

        RunResult output = run(new Cli(ScriptedSecrets.delivering(sentinel()), store), "set-key", "--output", "json");
        RunResult pretty = run(new Cli(ScriptedSecrets.delivering(sentinel()), store), "set-key", "--pretty");

        assertEquals(2, output.exitCode(), () -> "an --output flag is a usage error: " + output.describe());
        assertTrue(output.stderr().contains("Usage:"), () -> output.describe());
        assertEquals(2, pretty.exitCode(), () -> "a --pretty flag is a usage error: " + pretty.describe());
        assertEquals(0, store.storeCalls, "a rejected invocation must never reach the store");
    }

    @Test
    void unsetKeyRemovesAStoredKey() {
        RecordingStore store = RecordingStore.holding(sentinel());

        RunResult result = run(new Cli(ScriptedSecrets.absent(), store), "unset-key");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals("api key removed from " + CONFIG_PATH + "\n", result.stdout());
        assertEquals(1, store.clearCalls);
    }

    @Test
    void unsetKeyOnAnAbsentKeyIsASuccessfulNoOp() {
        RecordingStore store = new RecordingStore();

        RunResult result = run(new Cli(ScriptedSecrets.absent(), store), "unset-key");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals("no api key stored at " + CONFIG_PATH + "; nothing to remove\n", result.stdout());
        assertEquals(0, store.clearCalls, "nothing is cleared when nothing was stored");
    }

    @Test
    void unsetKeyRemovesAStoredButInvalidKey() {
        RecordingStore store = new RecordingStore();
        store.loadFailure = new InvalidCredentialException("contains a control character");

        RunResult result = run(new Cli(ScriptedSecrets.absent(), store), "unset-key");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals("api key removed from " + CONFIG_PATH + "\n", result.stdout());
        assertEquals(1, store.clearCalls, "an invalid stored key is exactly what unsetting remedies");
    }

    @Test
    void unsetKeyOnAnUnsafeFileFailsWithExit3() {
        FailingStore store = new FailingStore(LocalConfigException.insecureFile("the config file is a symbolic link"));

        RunResult result = run(new Cli(ScriptedSecrets.absent(), store), "unset-key");

        assertEquals(3, result.exitCode(), () -> result.describe());
        assertTrue(result.stderr().contains("local configuration error"), () -> result.describe());
    }

    @Test
    void unsetKeyRejectsRemoteOutputFlagsAsUsageErrors() {
        RunResult result = run(new Cli(ScriptedSecrets.absent(), new RecordingStore()), "unset-key", "--output", "json");

        assertEquals(2, result.exitCode(), () -> result.describe());
        assertTrue(result.stderr().contains("Usage:"), () -> result.describe());
    }

    @Test
    void showReportsTheEnvironmentSourcePathAndBothShadowings() {
        String sentinel = sentinel();
        RecordingStore store = RecordingStore.holding(sentinel);
        ScriptedProvider provider = ScriptedProvider.environmentCanonical(true);

        RunResult result = run(new Cli(ScriptedSecrets.absent(), store, provider), "show");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(
                "credential source: environment BRAVE_API_KEY\n"
                        + "config file: "
                        + CONFIG_PATH
                        + " (api key stored)\n"
                        + "shadowed: BRAVE_SEARCH_API_KEY is set but overridden\n"
                        + "shadowed: the config file api key is overridden\n",
                result.stdout(),
                "the show record is fixed human text");
        assertNoLeak(result, sentinel);
    }

    @Test
    void showReportsTheFileSourceWithoutShadowedLines() {
        RecordingStore store = RecordingStore.holding(sentinel());
        ScriptedProvider provider = ScriptedProvider.file();

        RunResult result = run(new Cli(ScriptedSecrets.absent(), store, provider), "show");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(
                "credential source: config file\n" + "config file: " + CONFIG_PATH + " (api key stored)\n",
                result.stdout());
    }

    @Test
    void showReportsTheAliasSourceAndAShadowedFile() {
        RecordingStore store = RecordingStore.holding(sentinel());

        RunResult result = run(new Cli(ScriptedSecrets.absent(), store, ScriptedProvider.environmentAlias()), "show");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(
                "credential source: environment BRAVE_SEARCH_API_KEY\n"
                        + "config file: "
                        + CONFIG_PATH
                        + " (api key stored)\n"
                        + "shadowed: the config file api key is overridden\n",
                result.stdout());
    }

    @Test
    void showReportsAMissingCredentialWithTheFileStateAndExitsZero() {
        RecordingStore absent = new RecordingStore();
        RecordingStore schemaOnly = new RecordingStore();
        schemaOnly.present = true;

        RunResult absentResult = run(
                new Cli(ScriptedSecrets.absent(), absent, ScriptedProvider.missing()), "show");
        RunResult schemaOnlyResult = run(
                new Cli(ScriptedSecrets.absent(), schemaOnly, ScriptedProvider.missing()), "show");

        assertEquals(0, absentResult.exitCode(), () -> absentResult.describe());
        assertEquals(
                "credential source: missing\n" + "config file: " + CONFIG_PATH + " (absent)\n",
                absentResult.stdout());
        assertEquals(
                "credential source: missing\n" + "config file: " + CONFIG_PATH + " (no api key stored)\n",
                schemaOnlyResult.stdout());
    }

    @Test
    void showReportsAnInvalidCredentialSourceAsExit3() {
        ScriptedProvider provider = ScriptedProvider.invalid("environment BRAVE_API_KEY", "contains a control character");

        RunResult result = run(new Cli(ScriptedSecrets.absent(), new RecordingStore(), provider), "show");

        assertEquals(3, result.exitCode(), () -> result.describe());
        assertEquals("", result.stdout(), "a failing show writes no result record");
        assertTrue(result.stderr().contains("invalid credential"), () -> result.describe());
        assertTrue(result.stderr().contains("environment BRAVE_API_KEY"), () -> result.describe());
    }

    @Test
    void showReportsAShadowedFileThatCannotBeReadSafely() {
        FailingStore store = new FailingStore(LocalConfigException.insecureFile("the config file is a symbolic link"));

        RunResult result = run(new Cli(ScriptedSecrets.absent(), store, ScriptedProvider.environmentAlias()), "show");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(
                "credential source: environment BRAVE_SEARCH_API_KEY\n"
                        + "config file: "
                        + CONFIG_PATH
                        + " (present, not safely readable)\n",
                result.stdout());
    }

    @Test
    void showTreatsAnInvalidShadowedFileKeyAsStored() {
        RecordingStore store = new RecordingStore();
        store.loadFailure = new InvalidCredentialException("contains a control character");
        store.sight = ConfigFileSight.KEY_STORED;

        RunResult result = run(new Cli(ScriptedSecrets.absent(), store, ScriptedProvider.environmentCanonical(false)), "show");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertTrue(result.stdout().contains("(api key stored)"), () -> result.describe());
        assertTrue(result.stdout().contains("shadowed: the config file api key is overridden"), () -> result.describe());
    }

    @Test
    void showOnAnUnsafelyReadableWinningFileFailsWithExit3() {
        ScriptedProvider provider =
                ScriptedProvider.failingLocally(LocalConfigException.insecureFile("the config file is a symbolic link"));

        RunResult result = run(new Cli(ScriptedSecrets.absent(), new RecordingStore(), provider), "show");

        assertEquals(3, result.exitCode(), () -> result.describe());
        assertEquals("", result.stdout());
        assertTrue(result.stderr().contains("local configuration error"), () -> result.describe());
    }

    @Test
    void showRejectsRemoteOutputFlagsAsUsageErrors() {
        RunResult output = run(new Cli(ScriptedSecrets.absent(), new RecordingStore(), ScriptedProvider.missing()), "show", "--output", "raw");
        RunResult pretty = run(new Cli(ScriptedSecrets.absent(), new RecordingStore(), ScriptedProvider.missing()), "show", "--pretty");

        assertEquals(2, output.exitCode(), () -> output.describe());
        assertTrue(output.stderr().contains("Usage:"), () -> output.describe());
        assertEquals(2, pretty.exitCode(), () -> pretty.describe());
    }

    @Test
    void showJsonEmitsOneMachineRecordNamingSourcePresenceAndShadowing() {
        String sentinel = sentinel();
        RecordingStore store = RecordingStore.holding(sentinel);
        ScriptedProvider provider = ScriptedProvider.environmentCanonical(true);

        RunResult result = run(new Cli(ScriptedSecrets.absent(), store, provider), "show", "--output", "json");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(
                "{\"schema_version\":\"1\",\"command\":\"config.show\","
                        + "\"source\":\"environment BRAVE_API_KEY\","
                        + "\"config_path\":\""
                        + CONFIG_PATH
                        + "\","
                        + "\"api_key_present\":true,"
                        + "\"shadowed\":[\"BRAVE_SEARCH_API_KEY\",\"config file\"]}\n",
                result.stdout(),
                "the json record is one LF-terminated machine document");
        assertNoLeak(result, sentinel);
    }

    @Test
    void showJsonOfAMissingCredentialStatesAbsenceWithNoShadowedSources() {
        RunResult result =
                run(new Cli(ScriptedSecrets.absent(), new RecordingStore(), ScriptedProvider.missing()), "show", "--output", "json");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(
                "{\"schema_version\":\"1\",\"command\":\"config.show\",\"source\":\"missing\","
                        + "\"config_path\":\""
                        + CONFIG_PATH
                        + "\","
                        + "\"api_key_present\":false,\"shadowed\":[]}\n",
                result.stdout());
    }

    @Test
    void aFailedMachineRecordWriteExitsSixWithExactlyOneStderrLine() {
        RunResult result = run(
                failingWriter("stdout is gone"),
                new Cli(ScriptedSecrets.absent(), new RecordingStore(), ScriptedProvider.missing()),
                "show",
                "--output",
                "json");

        assertEquals(6, result.exitCode(), "a non-broken-pipe stdout write failure keeps the transport status");
        List<String> diagnostics = result.stderr().lines().toList();
        assertEquals(1, diagnostics.size(), () -> "exactly one diagnostic line: " + result.describe());
        assertTrue(diagnostics.getFirst().contains("config show"), "the line names the command");
        assertFalse(diagnostics.getFirst().contains("Exception"), "no stack trace may reach stderr");
    }

    @Test
    void aFailedHumanResultLineWriteExitsSixWithExactlyOneStderrLine() {
        RunResult result = run(
                failingWriter("stdout is gone"),
                new Cli(ScriptedSecrets.absent(), new RecordingStore(), ScriptedProvider.file()),
                "show");

        assertEquals(6, result.exitCode(), "a failed human result write keeps the transport status");
        List<String> diagnostics = result.stderr().lines().toList();
        assertEquals(1, diagnostics.size(), () -> "exactly one diagnostic line: " + result.describe());
        assertTrue(diagnostics.getFirst().contains("config show"), "the line names the command");
    }

    @Test
    void aDownstreamBrokenPipeEndsTheConfigRunAsSilentZero() {
        RunResult brokenJson = run(
                failingWriter("Broken pipe"),
                new Cli(ScriptedSecrets.absent(), new RecordingStore(), ScriptedProvider.missing()),
                "show",
                "--output",
                "json");
        RunResult brokenHuman = run(
                failingWriter("Broken pipe"),
                new Cli(ScriptedSecrets.absent(), new RecordingStore(), ScriptedProvider.file()),
                "show");

        assertEquals(0, brokenJson.exitCode(), "a broken pipe on the machine record is silent success");
        assertEquals("", brokenJson.stderr(), "a broken pipe stays silent on stderr");
        assertEquals(0, brokenHuman.exitCode(), "a broken pipe on the human lines is silent success");
        assertEquals("", brokenHuman.stderr(), "a broken pipe stays silent on stderr");
    }

    @Test
    void showHumanSpelledExplicitlyStaysTheFixedHumanText() {
        RecordingStore store = RecordingStore.holding(sentinel());

        RunResult result = run(new Cli(ScriptedSecrets.absent(), store, ScriptedProvider.file()), "show", "--output", "human");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(
                "credential source: config file\n" + "config file: " + CONFIG_PATH + " (api key stored)\n",
                result.stdout());
    }

    @Test
    void showRejectsTheLineAndByteChannelsAsUsageErrors() {
        for (String rejected : List.of("jsonl", "raw")) {
            RunResult result = run(
                    new Cli(ScriptedSecrets.absent(), new RecordingStore(), ScriptedProvider.missing()),
                    "show",
                    "--output",
                    rejected);

            assertEquals(2, result.exitCode(), () -> rejected + ": " + result.describe());
            assertTrue(
                    result.stderr().contains("not accepted by this command"),
                    () -> rejected + " is rejected by name: " + result.describe());
            assertEquals("", result.stdout(), () -> result.describe());
        }
    }

    @Test
    void showDescribesTheFileThroughPeekSummaryWithoutLoadingACredential() {
        RecordingStore store = RecordingStore.holding(sentinel());

        RunResult result = run(new Cli(ScriptedSecrets.absent(), store, ScriptedProvider.environmentCanonical(true)), "show");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertTrue(result.stdout().contains("(api key stored)"), () -> result.describe());
        assertEquals(0, store.loadCalls, "the show path must never load the credential");
        assertEquals(1, store.peekCalls, "the show path describes the file through peekSummary");
        assertNoLeak(result, sentinel());
    }

    @Test
    void repairReportsExactlyThePartsItReset() {
        assertEquals(
                "permissions repaired: directory mode reset to 0700\n",
                repairOutcome(new PermissionRepair(true, false, false)).stdout());
        assertEquals(
                "permissions repaired: file mode reset to 0600\n",
                repairOutcome(new PermissionRepair(false, true, false)).stdout());
        assertEquals(
                "permissions repaired: directory mode reset to 0700, file mode reset to 0600\n",
                repairOutcome(new PermissionRepair(true, true, false)).stdout());
    }

    @Test
    void repairWithNothingToDoConfirmsThePosixModesOnly() {
        RecordingStore store = new RecordingStore();
        store.repairOutcome = new PermissionRepair(false, false, false);

        RunResult result = run(new Cli(ScriptedSecrets.absent(), store), "repair-permissions");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(
                "POSIX modes already owner-only (macOS ACLs not inspected)\n", result.stdout());
    }

    @Test
    void repairPrintsTheAclAdvisoryWhenTheProbeSuspectsNonOwnerEntries() {
        assertEquals(
                "POSIX modes already owner-only (macOS ACLs not inspected)\n"
                        + "advisory: macOS ACL entries beyond the owner are present on the credential file;"
                        + " review them with ls -le and remove non-owner grants\n",
                repairOutcome(new PermissionRepair(false, false, true)).stdout());
        assertEquals(
                "permissions repaired: file mode reset to 0600\n"
                        + "advisory: macOS ACL entries beyond the owner are present on the credential file;"
                        + " review them with ls -le and remove non-owner grants\n",
                repairOutcome(new PermissionRepair(false, true, true)).stdout());
    }

    @Test
    void repairOnAnUnsafeFileFailsWithExit3() {
        FailingStore store = new FailingStore(LocalConfigException.insecureFile("the config file is a symbolic link"));

        RunResult result = run(new Cli(ScriptedSecrets.absent(), store), "repair-permissions");

        assertEquals(3, result.exitCode(), () -> result.describe());
        assertTrue(result.stderr().contains("local configuration error"), () -> result.describe());
    }

    @Test
    void repairRejectsRemoteOutputFlagsAsUsageErrors() {
        RunResult result = run(new Cli(ScriptedSecrets.absent(), new RecordingStore()), "repair-permissions", "--pretty");

        assertEquals(2, result.exitCode(), () -> result.describe());
        assertTrue(result.stderr().contains("Usage:"), () -> result.describe());
    }

    @Test
    void bareConfigPrintsItsUsageToStdoutAndExitsZero() {
        RunResult result = run(new Cli(ScriptedSecrets.absent(), new RecordingStore()));

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertTrue(result.stdout().contains("Usage:"), () -> result.describe());
        assertEquals("", result.stderr(), () -> result.describe());
    }

    @Test
    void theConfigGroupRejectsRemoteOutputFlagsBeforeDispatch() {
        RecordingStore store = new RecordingStore();
        RecordingStore bareStore = new RecordingStore();

        RunResult dispatched = run(new Cli(ScriptedSecrets.absent(), store), "--output", "json", "show");
        RunResult bare = run(new Cli(ScriptedSecrets.absent(), bareStore), "--pretty");

        assertEquals(2, dispatched.exitCode(), () -> dispatched.describe());
        assertTrue(dispatched.stderr().contains("Usage:"), () -> dispatched.describe());
        assertEquals(2, bare.exitCode(), () -> bare.describe());
        assertTrue(bare.stderr().contains("Usage:"), () -> bare.describe());
    }

    @Test
    void anUnknownConfigSubcommandIsAUsageError() {
        RunResult result = run(new Cli(ScriptedSecrets.absent(), new RecordingStore()), "frobnicate");

        assertEquals(2, result.exitCode(), () -> result.describe());
        assertTrue(result.stderr().contains("Usage:"), () -> result.describe());
    }

    private RunResult repairOutcome(PermissionRepair outcome) {
        RecordingStore store = new RecordingStore();
        store.repairOutcome = outcome;
        RunResult result = run(new Cli(ScriptedSecrets.absent(), store), "repair-permissions");
        assertEquals(0, result.exitCode(), () -> result.describe());
        return result;
    }

    private static void assertNoLeak(RunResult result, String sentinel) {
        assertFalse(result.stdout().contains(sentinel), "token material leaked to stdout");
        assertFalse(result.stderr().contains(sentinel), "token material leaked to stderr");
    }

    private static RunResult run(Cli cli, String... args) {
        // one shared sink keeps usage help and result bytes in their real order
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        Execution executed = execute(new OutputStreamResultWriter(stdout), byteExact(stdout), cli, args);
        return new RunResult(executed.exitCode(), stdout.toString(UTF_8), executed.stderr());
    }

    private static RunResult run(ResultWriter results, Cli cli, String... args) {
        // usage help rides a sink of its own: the observed stdout is the result channel's
        Execution executed = execute(results, new PrintWriter(new java.io.StringWriter()), cli, args);
        return new RunResult(executed.exitCode(), "", executed.stderr());
    }

    private static Execution execute(ResultWriter results, PrintWriter out, Cli cli, String... args) {
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        CommandLine config = new CommandLine(new ConfigCommand());
        config.addSubcommand(
                SetKeyCommand.NAME,
                new SetKeyCommand(cli.secrets, cli.store, cli.configPath, results, WriterDiagnosticsSink::new));
        config.addSubcommand(
                UnsetKeyCommand.NAME,
                new UnsetKeyCommand(cli.store, cli.configPath, results, WriterDiagnosticsSink::new));
        config.addSubcommand(
                ShowCommand.NAME,
                new ShowCommand(
                        cli.provider,
                        cli.store,
                        new ConfigShowRecordCodec(new JsonMappers()),
                        cli.configPath,
                        results,
                        WriterDiagnosticsSink::new));
        config.addSubcommand(
                RepairPermissionsCommand.NAME,
                new RepairPermissionsCommand(cli.store, results, WriterDiagnosticsSink::new));
        PrintWriter err = new PrintWriter(stderr, true);
        config.setOut(out);
        config.setErr(err);
        config.getSubcommands().values().forEach(subcommand -> subcommand.setOut(out));
        int exitCode = config.execute(args);
        out.flush();
        err.flush();
        return new Execution(exitCode, stderr.toString(UTF_8));
    }

    /** A result channel whose every write fails with the operating system's own report. */
    private static ResultWriter failingWriter(String failureMessage) {
        return document -> {
            throw new UncheckedIOException(new IOException(failureMessage));
        };
    }

    private static PrintWriter byteExact(ByteArrayOutputStream sink) {
        return new PrintWriter(new OutputStreamWriter(
                new OutputStream() {
                    @Override
                    public void write(int b) {
                        sink.write(b);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) {
                        sink.write(b, off, len);
                    }
                },
                StandardCharsets.ISO_8859_1));
    }

    /**
     * A unique random-looking sentinel per invocation: it is embedded in token material under
     * test, so any appearance in an output or diagnostic is a leak of the token itself.
     */
    private static String sentinel() {
        return "BSK" + Long.toUnsignedString(UUID.randomUUID().getMostSignificantBits(), 36)
                + Long.toUnsignedString(UUID.randomUUID().getLeastSignificantBits(), 36);
    }

    private static Credential credential(String token) {
        return Credential.of(token.getBytes(UTF_8));
    }

    private record Cli(SecretSource secrets, ConfigStore store, Supplier<Path> configPath, CredentialProvider provider) {
        Cli(SecretSource secrets, ConfigStore store) {
            this(secrets, store, () -> CONFIG_PATH, ScriptedProvider.missing());
        }

        Cli(SecretSource secrets, ConfigStore store, CredentialProvider provider) {
            this(secrets, store, () -> CONFIG_PATH, provider);
        }
    }

    private record RunResult(int exitCode, String stdout, String stderr) {
        String describe() {
            return "exitCode=" + exitCode + ", stdout=<" + stdout + ">, stderr=<" + stderr + ">";
        }
    }

    private record Execution(int exitCode, String stderr) {}

    /** In-memory store covering the states show and unset-key read, with failure injection. */
    private static class RecordingStore implements ConfigStore {
        private Optional<Credential> stored = Optional.empty();
        private boolean present;
        private RuntimeException loadFailure;
        private ConfigFileSight sight;
        private PermissionRepair repairOutcome = new PermissionRepair(false, false, false);
        private int storeCalls;
        private int clearCalls;
        private int loadCalls;
        private int peekCalls;
        private Credential lastStored;

        static RecordingStore holding(String token) {
            RecordingStore store = new RecordingStore();
            store.stored = Optional.of(credential(token));
            store.present = true;
            return store;
        }

        @Override
        public ConfigState load() {
            loadCalls++;
            if (loadFailure != null) {
                throw loadFailure;
            }
            return new ConfigState(stored, new ConfigFileSummary(stored.isPresent() ? 1 : 0, CONFIG_PATH, present));
        }

        @Override
        public ConfigFileSight peekSummary() {
            peekCalls++;
            if (loadFailure instanceof LocalConfigException unreadable) {
                throw unreadable;
            }
            if (sight != null) {
                return sight;
            }
            if (!present) {
                return ConfigFileSight.ABSENT;
            }
            return stored.isPresent() ? ConfigFileSight.KEY_STORED : ConfigFileSight.NO_KEY_STORED;
        }

        @Override
        public void store(Credential credential) {
            storeCalls++;
            lastStored = credential;
            stored = Optional.of(credential);
            present = true;
        }

        @Override
        public void clear() {
            clearCalls++;
            stored = Optional.empty();
        }

        @Override
        public PermissionRepair repairPermissions() {
            if (loadFailure != null) {
                throw loadFailure;
            }
            return repairOutcome;
        }
    }

    /** A store whose operations fail with the given local-configuration failure. */
    private static final class FailingStore extends RecordingStore {
        private final LocalConfigException failure;

        FailingStore(LocalConfigException failure) {
            this.failure = failure;
        }

        @Override
        public ConfigState load() {
            throw failure;
        }

        @Override
        public ConfigFileSight peekSummary() {
            throw failure;
        }

        @Override
        public void store(Credential credential) {
            throw failure;
        }

        @Override
        public void clear() {
            throw failure;
        }

        @Override
        public PermissionRepair repairPermissions() {
            throw failure;
        }
    }

    /** Secret-source stub recording the chosen channel and delivering a scripted outcome. */
    private static final class ScriptedSecrets implements SecretSource {
        private final Credential delivered;
        private final LocalConfigException failure;
        private Boolean lastFromStdin;

        static ScriptedSecrets delivering(String token) {
            return new ScriptedSecrets(credential(token), null);
        }

        static ScriptedSecrets failing(LocalConfigException failure) {
            return new ScriptedSecrets(null, failure);
        }

        static ScriptedSecrets absent() {
            return new ScriptedSecrets(null, null);
        }

        private ScriptedSecrets(Credential delivered, LocalConfigException failure) {
            this.delivered = delivered;
            this.failure = failure;
        }

        @Override
        public Credential read(boolean fromStdin) {
            lastFromStdin = fromStdin;
            if (failure != null) {
                throw failure;
            }
            if (delivered == null) {
                throw new AssertionError("no secret was scripted for this invocation");
            }
            return delivered;
        }
    }

    /** Provider stub resolving to a scripted source or failing with a typed resolution failure. */
    private static final class ScriptedProvider implements CredentialProvider {
        private final ResolvedCredential resolution;
        private final CredentialResolutionException failure;
        private final LocalConfigException localFailure;

        static ScriptedProvider environmentCanonical(boolean aliasShadowed) {
            return new ScriptedProvider(
                    new ResolvedCredential(credential("canonical-sentinel"), "environment BRAVE_API_KEY", aliasShadowed), null);
        }

        static ScriptedProvider environmentAlias() {
            return new ScriptedProvider(
                    new ResolvedCredential(credential("alias-sentinel"), "environment BRAVE_SEARCH_API_KEY", false), null);
        }

        static ScriptedProvider file() {
            return new ScriptedProvider(new ResolvedCredential(credential("file-sentinel"), "config file", false), null);
        }

        static ScriptedProvider missing() {
            return new ScriptedProvider(null, CredentialResolutionException.missing("environment BRAVE_API_KEY"));
        }

        static ScriptedProvider invalid(String sourceName, String reason) {
            return new ScriptedProvider(null, CredentialResolutionException.invalid(sourceName, reason));
        }

        static ScriptedProvider failingLocally(LocalConfigException failure) {
            return new ScriptedProvider(null, null, failure);
        }

        private ScriptedProvider(ResolvedCredential resolution, CredentialResolutionException failure) {
            this(resolution, failure, null);
        }

        private ScriptedProvider(
                ResolvedCredential resolution,
                CredentialResolutionException failure,
                LocalConfigException localFailure) {
            this.resolution = resolution;
            this.failure = failure;
            this.localFailure = localFailure;
        }

        @Override
        public Credential resolve() throws CredentialResolutionException {
            return resolveWithProvenance().credential();
        }

        @Override
        public ResolvedCredential resolveWithProvenance() throws CredentialResolutionException {
            if (localFailure != null) {
                throw localFailure;
            }
            if (failure != null) {
                throw failure;
            }
            return resolution;
        }
    }
}

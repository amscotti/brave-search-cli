package io.amscotti.bravesearch.bootstrap;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.presentation.OutputStreamResultWriter;
import io.amscotti.bravesearch.application.port.out.ConfigStore;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.CredentialResolutionException;
import io.amscotti.bravesearch.application.port.out.ResolvedCredential;
import io.amscotti.bravesearch.domain.config.ConfigFileSight;
import io.amscotti.bravesearch.domain.config.ConfigFileSummary;
import io.amscotti.bravesearch.domain.config.ConfigState;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.config.LocalConfigException;
import io.amscotti.bravesearch.domain.config.PermissionRepair;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The config command group runs through the root command line exactly as the process entry
 * point wires it, with every port injected: show renders through the root's writers, a secret
 * that no console can deliver keeps the configuration exit status, and the version contract
 * stays byte-identical with the group registered.
 */
final class ConfigCommandMainTest {

    @TempDir
    Path home;

    @Test
    void configShowRunsThroughTheRootCommandLineWithInjectedPorts() {
        String sentinel = sentinel();
        InMemoryStore store = InMemoryStore.holding(sentinel);
        Path configPath = configPath();

        RunResult result = runMain(
                new ConfigComposition(
                        environmentProvider("environment BRAVE_API_KEY", true),
                        store,
                        fromStdin -> {
                            throw LocalConfigException.secretInput("no console delivered a secret");
                        },
                        () -> configPath),
                "config",
                "show");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(
                "credential source: environment BRAVE_API_KEY\n"
                        + "config file: "
                        + configPath
                        + " (api key stored)\n"
                        + "shadowed: BRAVE_SEARCH_API_KEY is set but overridden\n"
                        + "shadowed: the config file api key is overridden\n",
                result.stdout(),
                "the root wiring keeps the fixed human record");
        assertFalse(result.stdout().contains(sentinel), "token material leaked to stdout");
        assertFalse(result.stderr().contains(sentinel), "token material leaked to stderr");
    }

    @Test
    void setKeyWithoutAConsoleAndWithoutStdinKeepsExit3ThroughTheRoot() {
        InMemoryStore store = new InMemoryStore();

        RunResult result = runMain(
                new ConfigComposition(
                        missingProvider(), store, fromStdin -> {
                            throw LocalConfigException.secretInput(
                                    "no console delivered a secret and --stdin was not requested");
                        },
                        () -> configPath()),
                "config",
                "set-key");

        assertEquals(3, result.exitCode(), () -> result.describe());
        assertTrue(result.stderr().contains("local configuration error"), () -> result.describe());
        assertEquals(0, store.storeCalls, "the store is never reached without a secret");
    }

    @Test
    void versionOutputStaysIdenticalWithTheConfigGroupRegistered() {
        RunResult result = runMain(
                new ConfigComposition(
                        missingProvider(),
                        new InMemoryStore(),
                        fromStdin -> {
                            throw LocalConfigException.secretInput("no console delivered a secret");
                        },
                        () -> configPath()),
                "--version");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertTrue(
                result.stdout().startsWith(Main.COMMAND_NAME + " "),
                "the version line is unchanged: " + result.describe());
        assertTrue(result.stdout().endsWith("\n") && !result.stdout().contains("\r"), () -> result.describe());
    }

    private static CredentialProvider missingProvider() {
        return new CredentialProvider() {
            @Override
            public Credential resolve() throws CredentialResolutionException {
                return resolveWithProvenance().credential();
            }

            @Override
            public ResolvedCredential resolveWithProvenance() throws CredentialResolutionException {
                throw CredentialResolutionException.missing("environment BRAVE_API_KEY");
            }
        };
    }

    private static CredentialProvider environmentProvider(String sourceName, boolean aliasShadowed) {
        return new CredentialProvider() {
            @Override
            public Credential resolve() {
                return credential("environment-sentinel");
            }

            @Override
            public ResolvedCredential resolveWithProvenance() {
                return new ResolvedCredential(credential("environment-sentinel"), sourceName, aliasShadowed);
            }
        };
    }

    private static Credential credential(String token) {
        return Credential.of(token.getBytes(UTF_8));
    }

    private Path configPath() {
        return home.resolve("Library/Application Support/brave-search/config.json");
    }

    private static RunResult runMain(ConfigComposition config, String... args) {
        ByteArrayOutputStream stdoutSink = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrSink = new ByteArrayOutputStream();
        // help text rides the invocation's stdout writer, while the fixed config text and
        // the machine record travel through the injected result channel exactly as the
        // process wires them, so both stay observable in-process
        picocli.CommandLine commandLine = new Main(new ExchangeRegistry(), config)
                .assemble(
                        new java.io.PrintWriter(new java.io.OutputStreamWriter(stdoutSink, StandardCharsets.UTF_8), true),
                        new OutputStreamResultWriter(stdoutSink));
        commandLine.setErr(new java.io.PrintWriter(new java.io.OutputStreamWriter(stderrSink, StandardCharsets.UTF_8), true));
        int exitCode = commandLine.execute(args);
        return new RunResult(
                exitCode, stdoutSink.toString(StandardCharsets.UTF_8), stderrSink.toString(StandardCharsets.UTF_8));
    }

    /**
     * A unique random-looking sentinel per invocation: it is embedded in token material under
     * test, so any appearance in an output or diagnostic is a leak of the token itself.
     */
    private static String sentinel() {
        return "BSK" + Long.toUnsignedString(UUID.randomUUID().getMostSignificantBits(), 36)
                + Long.toUnsignedString(UUID.randomUUID().getLeastSignificantBits(), 36);
    }

    private record RunResult(int exitCode, String stdout, String stderr) {
        String describe() {
            return "exitCode=" + exitCode + ", stdout=<" + stdout + ">, stderr=<" + stderr + ">";
        }
    }

    /** In-memory store double good enough for the root-wiring assertions. */
    private static class InMemoryStore implements ConfigStore {
        private Optional<Credential> stored = Optional.empty();
        private boolean present;
        private int storeCalls;

        static InMemoryStore holding(String token) {
            InMemoryStore store = new InMemoryStore();
            store.stored = Optional.of(credential(token));
            store.present = true;
            return store;
        }

        @Override
        public ConfigState load() {
            return new ConfigState(stored, new ConfigFileSummary(stored.isPresent() ? 1 : 0, Path.of("/x/config.json"), present));
        }

        @Override
        public void store(Credential credential) {
            storeCalls++;
            stored = Optional.of(credential);
            present = true;
        }

        @Override
        public void clear() {
            stored = Optional.empty();
        }

        @Override
        public PermissionRepair repairPermissions() {
            return new PermissionRepair(false, false, false);
        }

        @Override
        public ConfigFileSight peekSummary() {
            return present ? (stored.isPresent() ? ConfigFileSight.KEY_STORED : ConfigFileSight.NO_KEY_STORED) : ConfigFileSight.ABSENT;
        }
    }
}

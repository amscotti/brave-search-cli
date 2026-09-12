package io.amscotti.bravesearch.adapter.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.domain.config.ConfigFileSight;
import io.amscotti.bravesearch.domain.config.ConfigFileSummary;
import io.amscotti.bravesearch.domain.config.ConfigState;
import io.amscotti.bravesearch.domain.config.InvalidCredentialException;
import io.amscotti.bravesearch.domain.config.LocalConfigException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading the credential file through the secure store: the absent case, the happy path, and
 * every structural refusal — symlinks, hard links, non-regular files, loose modes, foreign
 * ownership, oversized or malformed documents — each verified on the real macOS filesystem
 * under an isolated fabricated home.
 */
final class SecureConfigStoreLoadTest {

    @TempDir
    Path fabricatedHome;

    @Test
    void loadReportsAnAbsentFileAsEmptyState() {
        ConfigState state = ConfigStoreHarness.store(ConfigStoreHarness.configPath(fabricatedHome)).load();
        assertEquals(Optional.empty(), state.credential());
        ConfigFileSummary summary = state.summary();
        assertEquals(0, summary.schemaVersion());
        assertEquals(ConfigStoreHarness.configPath(fabricatedHome), summary.sourcePath());
        assertEquals(false, summary.present());
    }

    @Test
    void loadReturnsTheStoredCredentialAndFileIdentity() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        ConfigState state = ConfigStoreHarness.store(configPath).load();
        assertEquals(Optional.of(ConfigStoreHarness.credential("token-one")), state.credential());
        assertEquals(1, state.summary().schemaVersion());
        assertEquals(configPath, state.summary().sourcePath());
        assertTrue(state.summary().present());
    }

    @Test
    void loadPreservesMultibyteTokensByteForByte() throws IOException {
        String token = "ключ-キー-\uD83E\uDDEA-key";
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, token);
        ConfigState state = ConfigStoreHarness.store(configPath).load();
        assertArrayEquals(
                token.getBytes(StandardCharsets.UTF_8),
                state.credential().orElseThrow().tokenBytes());
    }

    @Test
    void loadAcceptsAStricterThan0600Mode() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        ConfigStoreHarness.chmod(configPath, "r--------");

        ConfigState state = ConfigStoreHarness.store(configPath).load();

        assertEquals(Optional.of(ConfigStoreHarness.credential("token-one")), state.credential());
        assertTrue(state.summary().present());
    }

    @Test
    void loadRefusesWhenTheHardLinkCountIsUnobservable() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        SecureConfigStore store = ConfigStoreHarness.storeWithHardLinkCounter(
                configPath,
                path -> {
                    throw new UnsupportedOperationException("unix:nlink is unavailable");
                });

        LocalConfigException failure = assertThrows(LocalConfigException.class, () -> store.load());

        assertEquals(LocalConfigException.Reason.UNSAFE_FILESYSTEM, failure.reason());
    }

    @Test
    void peekSummaryReportsEachFileStateWithoutMaterializingACredential() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);

        assertEquals(ConfigFileSight.ABSENT, ConfigStoreHarness.store(configPath).peekSummary());

        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        assertEquals(ConfigFileSight.KEY_STORED, ConfigStoreHarness.store(configPath).peekSummary());

        ConfigStoreHarness.writeRawConfig(configPath, "{\"schema_version\":\"1\"}\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(ConfigFileSight.NO_KEY_STORED, ConfigStoreHarness.store(configPath).peekSummary());
    }

    @Test
    void peekSummaryCountsAnInvalidStoredKeyAsStored() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, " ");

        assertEquals(ConfigFileSight.KEY_STORED, ConfigStoreHarness.store(configPath).peekSummary());
    }

    @Test
    void peekSummaryRefusesLikeALoadWhenTheFileIsUnreadable() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        ConfigStoreHarness.chmod(configPath, "rw-r-----");

        LocalConfigException failure =
                assertThrows(LocalConfigException.class, () -> ConfigStoreHarness.store(configPath).peekSummary());

        assertEquals(LocalConfigException.Reason.INSECURE_FILE, failure.reason());
    }

    @Test
    void loadRejectsAnInvalidStoredTokenAsACredentialFailure() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, " ");
        assertThrows(InvalidCredentialException.class, () -> ConfigStoreHarness.store(configPath).load());
    }

    @Test
    void loadRefusesASymlinkedConfigFile() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        Path decoy = fabricatedHome.resolve("decoy.json");
        Files.writeString(decoy, "decoy");
        Files.delete(configPath);
        Files.createSymbolicLink(configPath, decoy);
        LocalConfigException failure =
                assertThrows(LocalConfigException.class, () -> ConfigStoreHarness.store(configPath).load());
        assertEquals(LocalConfigException.Reason.INSECURE_FILE, failure.reason());
    }

    @Test
    void loadRefusesAHardLinkedConfigFile() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        Files.createLink(ConfigStoreHarness.managedDirectory(fabricatedHome).resolve("second-link"), configPath);
        LocalConfigException failure =
                assertThrows(LocalConfigException.class, () -> ConfigStoreHarness.store(configPath).load());
        assertEquals(LocalConfigException.Reason.INSECURE_FILE, failure.reason());
    }

    @Test
    void loadRefusesADirectoryAtTheConfigPath() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        Files.createDirectories(configPath);
        LocalConfigException failure =
                assertThrows(LocalConfigException.class, () -> ConfigStoreHarness.store(configPath).load());
        assertEquals(LocalConfigException.Reason.INSECURE_FILE, failure.reason());
    }

    @Test
    void loadRefusesAGroupReadableConfigFile() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        ConfigStoreHarness.chmod(configPath, "rw-r-----");
        LocalConfigException failure =
                assertThrows(LocalConfigException.class, () -> ConfigStoreHarness.store(configPath).load());
        assertEquals(LocalConfigException.Reason.INSECURE_FILE, failure.reason());
    }

    @Test
    void loadRefusesAFileOwnedByAnotherUser() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        LocalConfigException failure = assertThrows(
                LocalConfigException.class, () -> ConfigStoreHarness.storeExpectingForeignOwner(configPath).load());
        assertEquals(LocalConfigException.Reason.INSECURE_FILE, failure.reason());
    }

    @Test
    void loadRefusesASymlinkedManagedDirectory() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        Path managedDirectory = ConfigStoreHarness.managedDirectory(fabricatedHome);
        Path realDirectory = fabricatedHome.resolve("elsewhere");
        Files.move(managedDirectory, realDirectory);
        Files.createSymbolicLink(managedDirectory, realDirectory);
        LocalConfigException failure =
                assertThrows(LocalConfigException.class, () -> ConfigStoreHarness.store(configPath).load());
        assertEquals(LocalConfigException.Reason.INSECURE_FILE, failure.reason());
    }

    @Test
    void loadRefusesAManagedDirectoryWithoutOwnerOnlyMode() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        ConfigStoreHarness.chmod(ConfigStoreHarness.managedDirectory(fabricatedHome), "rwxr-x---");
        assertEquals(
                LocalConfigException.Reason.INSECURE_FILE,
                assertThrows(LocalConfigException.class, () -> ConfigStoreHarness.store(configPath).load())
                        .reason());
        ConfigStoreHarness.chmod(ConfigStoreHarness.managedDirectory(fabricatedHome), "rwxrwxrwx");
        assertEquals(
                LocalConfigException.Reason.INSECURE_FILE,
                assertThrows(LocalConfigException.class, () -> ConfigStoreHarness.store(configPath).load())
                        .reason());
    }

    @Test
    void loadRefusesAnOversizedConfigFile() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        String padding = "A".repeat(JsonConfigFile.MAX_BYTES);
        ConfigStoreHarness.writeRawConfig(
                configPath,
                ("{\"schema_version\":\"1\",\"pad\":\"" + padding + "\"}\n").getBytes(StandardCharsets.UTF_8));
        LocalConfigException failure =
                assertThrows(LocalConfigException.class, () -> ConfigStoreHarness.store(configPath).load());
        assertEquals(LocalConfigException.Reason.MALFORMED_FILE, failure.reason());
    }

    @Test
    void loadRefusesAnUnsupportedSchemaVersion() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeRawConfig(
                configPath, "{\"schema_version\":\"9\",\"api_key\":\"token-one\"}\n".getBytes(StandardCharsets.UTF_8));
        LocalConfigException failure =
                assertThrows(LocalConfigException.class, () -> ConfigStoreHarness.store(configPath).load());
        assertEquals(LocalConfigException.Reason.UNSUPPORTED_SCHEMA, failure.reason());
    }

    @Test
    void loadRefusesADuplicateKeyDocument() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeRawConfig(
                configPath,
                "{\"schema_version\":\"1\",\"api_key\":\"a\",\"api_key\":\"b\"}\n".getBytes(StandardCharsets.UTF_8));
        LocalConfigException failure =
                assertThrows(LocalConfigException.class, () -> ConfigStoreHarness.store(configPath).load());
        assertEquals(LocalConfigException.Reason.MALFORMED_FILE, failure.reason());
    }
}

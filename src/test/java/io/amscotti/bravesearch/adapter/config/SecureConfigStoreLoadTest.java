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
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.Optional;
import java.util.Set;
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
    void peekSummaryRefusesLikeALoadWhenTheFileIsNotOwnerOnly() throws IOException {
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
    void loadRefusesAManagedDirectoryOwnedByAnotherUser() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        LocalConfigException failure = assertThrows(
                LocalConfigException.class, () -> ConfigStoreHarness.storeExpectingForeignOwner(configPath).load());
        assertEquals(LocalConfigException.Reason.INSECURE_FILE, failure.reason());
        assertTrue(
                failure.getMessage().contains("the config directory is not owned by the current user"),
                () -> "the refusal is the directory's own: " + failure.getMessage());
    }

    @Test
    void loadRefusesAConfigFileOwnedByAnotherUserEvenInsideAWellOwnedDirectory() throws IOException {
        // the state a set-key run as another account leaves behind once the owner repairs the
        // directory but not the file: the directory passes, and the file must still be refused
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        SecureConfigStore store = ConfigStoreHarness.storeSeeingAConfigFileOwnedByAnotherUser(configPath);

        LocalConfigException refusal = assertThrows(LocalConfigException.class, store::load);

        assertEquals(LocalConfigException.Reason.INSECURE_FILE, refusal.reason());
        assertTrue(
                refusal.getMessage().contains("the config file is not owned by the current user"),
                () -> "the refusal is the file's own: " + refusal.getMessage());
        LocalConfigException sight = assertThrows(LocalConfigException.class, store::peekSummary);
        assertEquals(LocalConfigException.Reason.INSECURE_FILE, sight.reason(), "a sight refuses like a load");
    }

    @Test
    void aManagedDirectoryWithoutAConfigFileReadsAsNeverConfigured() throws IOException {
        // the directory survived a restore or a manual deletion; the file did not
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        Files.delete(configPath);

        ConfigState state = ConfigStoreHarness.store(configPath).load();

        assertEquals(Optional.empty(), state.credential(), "nothing is stored, so nothing is loaded");
        assertEquals(false, state.summary().present());
        assertEquals(ConfigFileSight.ABSENT, ConfigStoreHarness.store(configPath).peekSummary());
    }

    @Test
    void loadFailsClosedOnAFilesystemThatCannotPinDirectoryOperations() throws IOException {
        // never a silent downgrade to path-based checks, and never an absent state either
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        SecureConfigStore unpinnable = ConfigStoreHarness.storeOnAnUnpinnableFilesystem(configPath);

        LocalConfigException failure = assertThrows(LocalConfigException.class, unpinnable::load);

        assertEquals(LocalConfigException.Reason.UNSAFE_FILESYSTEM, failure.reason());
        assertTrue(
                failure.getMessage().contains("cannot pin directory operations"),
                () -> failure.getMessage());
        LocalConfigException sight = assertThrows(LocalConfigException.class, unpinnable::peekSummary);
        assertEquals(LocalConfigException.Reason.UNSAFE_FILESYSTEM, sight.reason(), "a sight fails like a load");
    }

    @Test
    void loadRefusesAManagedDirectorySwappedForAnotherWhileItWasBeingOpened() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        SecureConfigStore racing = ConfigStoreHarness.storeWithOpener(configPath, directory -> {
            // between the store's attribute read and its open, the entry names a different directory
            Files.move(directory, directory.resolveSibling("brave-search-moved"));
            ConfigStoreHarness.writeValidConfig(configPath, "attacker-token");
            return SecureConfigStore.SecureStreamOpener.processFilesystem().open(directory);
        });

        LocalConfigException failure = assertThrows(LocalConfigException.class, racing::load);

        assertEquals(LocalConfigException.Reason.INSECURE_FILE, failure.reason());
        assertTrue(
                failure.getMessage().contains("changed identity while it was opened"),
                () -> failure.getMessage());
    }

    @Test
    void aConfigFileTheProcessCannotReadIsAFilesystemFailureNeverAnAbsentState() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        Path configEntry = ConfigStoreHarness.entryName(configPath);
        SecureConfigStore unreadable = ConfigStoreHarness.storeWithOpener(
                configPath,
                directory -> SecureConfigStore.SecureStreamOpener.processFilesystem()
                        .open(directory)
                        .map(real -> new DelegatingSecureStream(real) {
                            @Override
                            public SeekableByteChannel newByteChannel(
                                    Path entry, Set<? extends OpenOption> options, FileAttribute<?>... attrs)
                                    throws IOException {
                                if (configEntry.equals(entry)) {
                                    throw new AccessDeniedException(entry.toString(), null, "Permission denied");
                                }
                                return delegate.newByteChannel(entry, options, attrs);
                            }
                        }));

        LocalConfigException failure = assertThrows(LocalConfigException.class, unreadable::load);

        assertEquals(LocalConfigException.Reason.UNSAFE_FILESYSTEM, failure.reason());
        assertTrue(failure.getMessage().contains("Permission denied"), () -> failure.getMessage());
        LocalConfigException sight = assertThrows(LocalConfigException.class, unreadable::peekSummary);
        assertEquals(LocalConfigException.Reason.UNSAFE_FILESYSTEM, sight.reason(), "a sight fails like a load");
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

package io.amscotti.bravesearch.adapter.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.domain.config.LocalConfigException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Writing and clearing through the secure store: the owner-only create/replace happy paths with
 * no leftover artifacts, every structural refusal leaving the old config byte-intact, fail-closed
 * behavior when atomic replacement is unsupported, and adversarial destination swaps detected by
 * the pre-move revalidation — all on the real filesystem, portable across macOS and Linux.
 */
final class SecureConfigStoreWriteTest {

    @TempDir
    Path fabricatedHome;

    @Test
    void storeCreatesTheManagedDirectoryAndAnOwnerOnlyFileFromNothing() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.store(configPath).store(ConfigStoreHarness.credential("token-one"));

        assertEquals(
                PosixFilePermissions.fromString("rwx------"),
                ConfigStoreHarness.noFollowMode(ConfigStoreHarness.managedDirectory(fabricatedHome)));
        assertEquals(PosixFilePermissions.fromString("rw-------"), ConfigStoreHarness.noFollowMode(configPath));
        assertEquals(ConfigStoreHarness.configDocument("token-one"), ConfigStoreHarness.readContent(configPath));
        assertEquals(List.of("config.json"), entryNames(ConfigStoreHarness.managedDirectory(fabricatedHome)));
        assertEquals(
                Optional.of(ConfigStoreHarness.credential("token-one")), ConfigStoreHarness.store(configPath).load()
                        .credential());
    }

    @Test
    void storeSucceedsThroughADelegatingStream() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        SecureConfigStore store = new SecureConfigStore(
                () -> configPath,
                new JsonConfigFile(),
                ConfigStoreHarness::processOwner,
                directory -> SecureConfigStore.SecureStreamOpener.processFilesystem()
                        .open(directory)
                        .map(DelegatingSecureStream::new),
                SecureConfigStore.HardLinkCounter.processFilesystem(),
                SecureConfigStore.AclProbe.disabled());

        store.store(ConfigStoreHarness.credential("token-one"));

        assertEquals(ConfigStoreHarness.configDocument("token-one"), ConfigStoreHarness.readContent(configPath));
    }

    @Test
    void storeAtomicallyReplacesAnExistingConfig() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");

        ConfigStoreHarness.store(configPath).store(ConfigStoreHarness.credential("token-two"));

        assertEquals(ConfigStoreHarness.configDocument("token-two"), ConfigStoreHarness.readContent(configPath));
        assertEquals(PosixFilePermissions.fromString("rw-------"), ConfigStoreHarness.noFollowMode(configPath));
        assertEquals(List.of("config.json"), entryNames(ConfigStoreHarness.managedDirectory(fabricatedHome)));
    }

    @Test
    void storeRoundTripsMultibyteTokensByteForByte() throws IOException {
        String token = "ключ-キー-\uD83E\uDDEA-key";
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.store(configPath).store(ConfigStoreHarness.credential(token));
        assertArrayEquals(
                token.getBytes(StandardCharsets.UTF_8),
                ConfigStoreHarness.store(configPath).load().credential().orElseThrow().tokenBytes());
    }

    @Test
    void clearReplacesTheConfigWithASchemaOnlyDocument() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");

        ConfigStoreHarness.store(configPath).clear();

        assertEquals("{\"schema_version\":\"1\"}\n", ConfigStoreHarness.readContent(configPath));
        assertEquals(PosixFilePermissions.fromString("rw-------"), ConfigStoreHarness.noFollowMode(configPath));
        var state = ConfigStoreHarness.store(configPath).load();
        assertTrue(state.summary().present());
        assertEquals(1, state.summary().schemaVersion());
        assertEquals(Optional.empty(), state.credential());
    }

    @Test
    void clearIsANoOpWhenNoConfigExists() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.store(configPath).clear();
        assertFalse(Files.exists(configPath));
        assertEquals(false, ConfigStoreHarness.store(configPath).load().summary().present());
    }

    @Test
    void storeRefusesASymlinkedDestinationAndLeavesTheTargetUntouched() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        Path decoy = fabricatedHome.resolve("decoy.json");
        Files.writeString(decoy, "decoy-content");
        Files.createDirectories(
                ConfigStoreHarness.managedDirectory(fabricatedHome),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.createSymbolicLink(configPath, decoy);

        LocalConfigException failure = assertThrows(
                LocalConfigException.class,
                () -> ConfigStoreHarness.store(configPath).store(ConfigStoreHarness.credential("token-two")));

        assertEquals(LocalConfigException.Reason.INSECURE_FILE, failure.reason());
        assertEquals("decoy-content", ConfigStoreHarness.readContent(decoy));
        assertTrue(Files.isSymbolicLink(configPath));
        assertEquals(List.of("config.json"), entryNames(ConfigStoreHarness.managedDirectory(fabricatedHome)));
    }

    @Test
    void storeRefusesAHardLinkedDestination() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        Files.createLink(ConfigStoreHarness.managedDirectory(fabricatedHome).resolve("second-link"), configPath);

        LocalConfigException failure = assertThrows(
                LocalConfigException.class,
                () -> ConfigStoreHarness.store(configPath).store(ConfigStoreHarness.credential("token-two")));

        assertEquals(LocalConfigException.Reason.INSECURE_FILE, failure.reason());
        assertEquals(ConfigStoreHarness.configDocument("token-one"), ConfigStoreHarness.readContent(configPath));
    }

    @Test
    void storeRefusesADirectoryAtTheDestination() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        Files.delete(configPath);
        Files.createDirectory(configPath);

        LocalConfigException failure = assertThrows(
                LocalConfigException.class,
                () -> ConfigStoreHarness.store(configPath).store(ConfigStoreHarness.credential("token-two")));

        assertEquals(LocalConfigException.Reason.INSECURE_FILE, failure.reason());
        assertTrue(Files.isDirectory(configPath));
    }

    @Test
    void storeRefusesAManagedDirectoryOwnedByAnotherUser() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");

        LocalConfigException failure = assertThrows(
                LocalConfigException.class,
                () -> ConfigStoreHarness.storeExpectingForeignOwner(configPath)
                        .store(ConfigStoreHarness.credential("token-two")));

        assertEquals(LocalConfigException.Reason.INSECURE_FILE, failure.reason());
        assertTrue(
                failure.getMessage().contains("the config directory is not owned by the current user"),
                () -> "the refusal is the directory's own: " + failure.getMessage());
        assertEquals(ConfigStoreHarness.configDocument("token-one"), ConfigStoreHarness.readContent(configPath));
    }

    @Test
    void storeRefusesToReplaceAConfigFileOwnedByAnotherUser() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        SecureConfigStore store = ConfigStoreHarness.storeSeeingAConfigFileOwnedByAnotherUser(configPath);

        LocalConfigException failure = assertThrows(
                LocalConfigException.class, () -> store.store(ConfigStoreHarness.credential("token-two")));

        assertEquals(LocalConfigException.Reason.INSECURE_FILE, failure.reason());
        assertTrue(
                failure.getMessage().contains("the config file is not owned by the current user"),
                () -> "the refusal is the file's own: " + failure.getMessage());
        assertEquals(ConfigStoreHarness.configDocument("token-one"), ConfigStoreHarness.readContent(configPath));
        assertEquals(List.of("config.json"), entryNames(ConfigStoreHarness.managedDirectory(fabricatedHome)));
    }

    @Test
    void storeFailsClosedOnAFilesystemThatCannotPinDirectoryOperationsAndWritesNothing() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");

        LocalConfigException failure = assertThrows(
                LocalConfigException.class,
                () -> ConfigStoreHarness.storeOnAnUnpinnableFilesystem(configPath)
                        .store(ConfigStoreHarness.credential("token-two")));

        assertEquals(LocalConfigException.Reason.UNSAFE_FILESYSTEM, failure.reason());
        assertTrue(failure.getMessage().contains("cannot pin directory operations"), () -> failure.getMessage());
        assertEquals(ConfigStoreHarness.configDocument("token-one"), ConfigStoreHarness.readContent(configPath));
        assertEquals(List.of("config.json"), entryNames(ConfigStoreHarness.managedDirectory(fabricatedHome)));
    }

    @Test
    void storeRefusesAManagedPathBehindADanglingSymlinkInsteadOfWritingThroughIt() throws IOException {
        // Application Support relocated to a volume that is not mounted right now
        Path library = fabricatedHome.resolve("Library");
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        Path unmountedTarget = fabricatedHome.resolve("Volumes/Relocated");
        Files.createDirectories(library);
        Files.createSymbolicLink(library.resolve("Application Support"), unmountedTarget);

        LocalConfigException failure = assertThrows(
                LocalConfigException.class, () -> ConfigStoreHarness.store(configPath).store(credential("token-two")));

        assertEquals(LocalConfigException.Reason.INSECURE_FILE, failure.reason());
        assertTrue(
                failure.getMessage().contains("exists but is not a user-owned 0700 directory"),
                () -> failure.getMessage());
        assertFalse(Files.exists(unmountedTarget), "nothing is created at the link's target");
        assertTrue(Files.isSymbolicLink(library.resolve("Application Support")), "the link is left as it was");
    }

    @Test
    void storeRefusesToClobberAConfigFileThatAppearedBehindAFirstWrite() throws IOException {
        // a concurrent set-key, or a restore, lands a config between this write's first look
        // and its replacement: the file that appeared is left exactly as it arrived
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        Files.delete(configPath);
        Path watchedEntry = ConfigStoreHarness.entryName(configPath);
        AtomicInteger configViews = new AtomicInteger();
        SecureConfigStore store = ConfigStoreHarness.storeWithOpener(
                configPath,
                directory -> SecureConfigStore.SecureStreamOpener.processFilesystem()
                        .open(directory)
                        .map(real -> new DelegatingSecureStream(real) {
                            @Override
                            public <V extends FileAttributeView> V getFileAttributeView(
                                    Path entry, Class<V> type, LinkOption... options) {
                                if (watchedEntry.equals(entry)
                                        && type == PosixFileAttributeView.class
                                        && configViews.incrementAndGet() == 2) {
                                    placeConfigBehindTheWrite(configPath, "rival-token");
                                }
                                return delegate.getFileAttributeView(entry, type, options);
                            }
                        }));

        LocalConfigException failure = assertThrows(
                LocalConfigException.class, () -> store.store(credential("token-two")));

        assertEquals(LocalConfigException.Reason.INSECURE_FILE, failure.reason());
        assertTrue(failure.getMessage().contains("appeared during the write"), () -> failure.getMessage());
        assertEquals(ConfigStoreHarness.configDocument("rival-token"), ConfigStoreHarness.readContent(configPath));
        assertEquals(List.of("config.json"), entryNames(ConfigStoreHarness.managedDirectory(fabricatedHome)));
    }

    @Test
    void storeRefusesWhenTheConfigFileVanishesBehindAWrite() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        Path watchedEntry = ConfigStoreHarness.entryName(configPath);
        AtomicInteger configViews = new AtomicInteger();
        SecureConfigStore store = ConfigStoreHarness.storeWithOpener(
                configPath,
                directory -> SecureConfigStore.SecureStreamOpener.processFilesystem()
                        .open(directory)
                        .map(real -> new DelegatingSecureStream(real) {
                            @Override
                            public <V extends FileAttributeView> V getFileAttributeView(
                                    Path entry, Class<V> type, LinkOption... options) {
                                if (watchedEntry.equals(entry)
                                        && type == PosixFileAttributeView.class
                                        && configViews.incrementAndGet() == 2) {
                                    vanishBehindTheWrite(configPath);
                                }
                                return delegate.getFileAttributeView(entry, type, options);
                            }
                        }));

        LocalConfigException failure = assertThrows(
                LocalConfigException.class, () -> store.store(credential("token-two")));

        assertEquals(LocalConfigException.Reason.INSECURE_FILE, failure.reason());
        assertTrue(failure.getMessage().contains("vanished during the write"), () -> failure.getMessage());
        assertFalse(Files.exists(configPath, LinkOption.NOFOLLOW_LINKS), "the deletion is not silently undone");
        assertEquals(List.of(), entryNames(ConfigStoreHarness.managedDirectory(fabricatedHome)));
    }

    @Test
    void storeRefusesToInstallATemporaryFileWhoseModeWasLoosenedBehindTheWrite() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        Path managedDirectory = ConfigStoreHarness.managedDirectory(fabricatedHome);
        Path watchedEntry = ConfigStoreHarness.entryName(configPath);
        SecureConfigStore store = ConfigStoreHarness.storeWithOpener(
                configPath,
                directory -> SecureConfigStore.SecureStreamOpener.processFilesystem()
                        .open(directory)
                        .map(real -> new DelegatingSecureStream(real) {
                            @Override
                            public <V extends FileAttributeView> V getFileAttributeView(
                                    Path entry, Class<V> type, LinkOption... options) {
                                if (!watchedEntry.equals(entry) && type == PosixFileAttributeView.class) {
                                    // the first look at the temporary file: its mode was changed since the write
                                    loosenModeBehindTheWrite(managedDirectory.resolve(entry));
                                }
                                return delegate.getFileAttributeView(entry, type, options);
                            }
                        }));

        LocalConfigException failure = assertThrows(
                LocalConfigException.class, () -> store.store(credential("token-two")));

        assertEquals(LocalConfigException.Reason.INSECURE_FILE, failure.reason());
        assertTrue(failure.getMessage().contains("failed its revalidation"), () -> failure.getMessage());
        assertEquals(ConfigStoreHarness.configDocument("token-one"), ConfigStoreHarness.readContent(configPath));
        assertEquals(List.of("config.json"), entryNames(managedDirectory));
    }

    @Test
    void storeRefusesAGroupReadableDestination() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        ConfigStoreHarness.chmod(configPath, "rw-r-----");

        LocalConfigException failure = assertThrows(
                LocalConfigException.class,
                () -> ConfigStoreHarness.store(configPath).store(ConfigStoreHarness.credential("token-two")));

        assertEquals(LocalConfigException.Reason.INSECURE_FILE, failure.reason());
        assertEquals(ConfigStoreHarness.configDocument("token-one"), ConfigStoreHarness.readContent(configPath));
    }

    @Test
    void storeRefusesASymlinkedManagedDirectory() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        Path managedDirectory = ConfigStoreHarness.managedDirectory(fabricatedHome);
        Path realDirectory = fabricatedHome.resolve("elsewhere");
        Files.move(managedDirectory, realDirectory);
        Files.createSymbolicLink(managedDirectory, realDirectory);

        LocalConfigException failure = assertThrows(
                LocalConfigException.class,
                () -> ConfigStoreHarness.store(configPath).store(ConfigStoreHarness.credential("token-two")));

        assertEquals(LocalConfigException.Reason.INSECURE_FILE, failure.reason());
        assertEquals(
                ConfigStoreHarness.configDocument("token-one"), ConfigStoreHarness.readContent(realDirectory.resolve("config.json")));
    }

    @Test
    void storeRefusesAManagedDirectoryWithLooseMode() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        ConfigStoreHarness.chmod(ConfigStoreHarness.managedDirectory(fabricatedHome), "rwxr-x---");

        LocalConfigException failure = assertThrows(
                LocalConfigException.class,
                () -> ConfigStoreHarness.store(configPath).store(ConfigStoreHarness.credential("token-two")));

        assertEquals(LocalConfigException.Reason.INSECURE_FILE, failure.reason());
        assertEquals(ConfigStoreHarness.configDocument("token-one"), ConfigStoreHarness.readContent(configPath));
        assertEquals(List.of("config.json"), entryNames(ConfigStoreHarness.managedDirectory(fabricatedHome)));
    }

    @Test
    void storeRefusesAFileAtTheManagedDirectoryPath() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        Files.createDirectories(configPath.getParent().getParent());
        Files.writeString(ConfigStoreHarness.managedDirectory(fabricatedHome), "not-a-directory");

        LocalConfigException failure = assertThrows(
                LocalConfigException.class,
                () -> ConfigStoreHarness.store(configPath).store(ConfigStoreHarness.credential("token-two")));

        assertEquals(LocalConfigException.Reason.INSECURE_FILE, failure.reason());
        assertEquals("not-a-directory", ConfigStoreHarness.readContent(ConfigStoreHarness.managedDirectory(fabricatedHome)));
    }

    @Test
    void storeRefusesWhenTheHardLinkCountIsUnobservable() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        SecureConfigStore store = ConfigStoreHarness.storeWithHardLinkCounter(configPath, path -> -1L);

        LocalConfigException failure = assertThrows(LocalConfigException.class, () -> store.store(credential("token-two")));

        assertEquals(LocalConfigException.Reason.UNSAFE_FILESYSTEM, failure.reason());
        assertEquals(ConfigStoreHarness.configDocument("token-one"), ConfigStoreHarness.readContent(configPath));
    }

    @Test
    void storeFailsClosedOnFirstWriteWhenTheHardLinkCountIsUnobservable() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        SecureConfigStore store = ConfigStoreHarness.storeWithHardLinkCounter(configPath, path -> -1L);

        LocalConfigException failure = assertThrows(LocalConfigException.class, () -> store.store(credential("token-one")));

        assertEquals(LocalConfigException.Reason.UNSAFE_FILESYSTEM, failure.reason());
        assertFalse(Files.exists(configPath, LinkOption.NOFOLLOW_LINKS), "no config file may be installed on refusal");
        assertEquals(
                List.of(),
                entryNames(ConfigStoreHarness.managedDirectory(fabricatedHome)),
                "the temporary file must be discarded on refusal");
    }

    @Test
    void storeRefusesAFirstWriteWhenTheTemporaryFileGainsAnExtraHardLink() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        Path managedDirectory = ConfigStoreHarness.managedDirectory(fabricatedHome);
        Path watchedEntry = ConfigStoreHarness.entryName(configPath);
        SecureConfigStore store = ConfigStoreHarness.storeWithOpener(
                configPath,
                directory -> SecureConfigStore.SecureStreamOpener.processFilesystem()
                        .open(directory)
                        .map(real -> new DelegatingSecureStream(real) {
                            @Override
                            public <V extends FileAttributeView> V getFileAttributeView(
                                    Path entry, Class<V> type, LinkOption... options) {
                                if (!watchedEntry.equals(entry) && type == PosixFileAttributeView.class) {
                                    addHardLinkBehindTheWrite(
                                            managedDirectory.resolve(entry), managedDirectory.resolve("temp-link"));
                                }
                                return delegate.getFileAttributeView(entry, type, options);
                            }
                        }));

        LocalConfigException failure = assertThrows(LocalConfigException.class, () -> store.store(credential("token-one")));

        assertEquals(LocalConfigException.Reason.INSECURE_FILE, failure.reason());
        assertTrue(failure.getMessage().contains("hard links"), () -> failure.getMessage());
        assertFalse(Files.exists(configPath, LinkOption.NOFOLLOW_LINKS), "no config file may be installed on refusal");
        assertEquals(
                List.of("temp-link"),
                entryNames(managedDirectory),
                "the temporary entry must be unlinked, leaving only the injected sibling");
    }

    @Test
    void clearRefusesASymlinkedConfigFileAndLeavesTheTargetUntouched() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        Path decoy = fabricatedHome.resolve("decoy.json");
        Files.writeString(decoy, "decoy-content", StandardCharsets.UTF_8);
        Files.createDirectories(
                ConfigStoreHarness.managedDirectory(fabricatedHome),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.createSymbolicLink(configPath, decoy);

        LocalConfigException failure =
                assertThrows(LocalConfigException.class, () -> ConfigStoreHarness.store(configPath).clear());

        assertEquals(LocalConfigException.Reason.INSECURE_FILE, failure.reason());
        assertEquals("decoy-content", ConfigStoreHarness.readContent(decoy));
        assertTrue(Files.isSymbolicLink(configPath));
    }

    @Test
    void storeFailsClosedWhenAtomicReplacementIsUnsupported() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        SecureConfigStore store = new SecureConfigStore(
                () -> configPath,
                new JsonConfigFile(),
                ConfigStoreHarness::processOwner,
                directory -> SecureConfigStore.SecureStreamOpener.processFilesystem()
                        .open(directory)
                        .map(real -> new DelegatingSecureStream(real) {
                            @Override
                            public void move(Path source, SecureDirectoryStream<Path> targetDirectory, Path target)
                                    throws IOException {
                                throw new AtomicMoveNotSupportedException(
                                        source.toString(), target.toString(), "atomic replacement unsupported here");
                            }
                        }),
                SecureConfigStore.HardLinkCounter.processFilesystem(),
                SecureConfigStore.AclProbe.disabled());

        LocalConfigException failure = assertThrows(LocalConfigException.class, () -> store.store(credential("token-two")));

        assertEquals(LocalConfigException.Reason.UNSAFE_FILESYSTEM, failure.reason());
        assertEquals(ConfigStoreHarness.configDocument("token-one"), ConfigStoreHarness.readContent(configPath));
        assertEquals(List.of("config.json"), entryNames(ConfigStoreHarness.managedDirectory(fabricatedHome)));
    }

    @Test
    void storeFailsClosedWhenTheDestinationIsSwappedForASymlinkMidWrite() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        Path decoy = fabricatedHome.resolve("decoy.json");
        Files.writeString(decoy, "decoy-content");
        Path watchedEntry = ConfigStoreHarness.entryName(configPath);
        AtomicInteger configViews = new AtomicInteger();
        SecureConfigStore store = new SecureConfigStore(
                () -> configPath,
                new JsonConfigFile(),
                ConfigStoreHarness::processOwner,
                directory -> SecureConfigStore.SecureStreamOpener.processFilesystem()
                        .open(directory)
                        .map(real -> new DelegatingSecureStream(real) {
                            @Override
                            public <V extends FileAttributeView> V getFileAttributeView(
                                    Path entry, Class<V> type, LinkOption... options) {
                                if (watchedEntry.equals(entry) && type == PosixFileAttributeView.class
                                        && configViews.incrementAndGet() == 2) {
                                    swapDestinationForSymlink(configPath, decoy);
                                }
                                return delegate.getFileAttributeView(entry, type, options);
                            }
                        }),
                SecureConfigStore.HardLinkCounter.processFilesystem(),
                SecureConfigStore.AclProbe.disabled());

        LocalConfigException failure = assertThrows(LocalConfigException.class, () -> store.store(credential("token-two")));

        assertEquals(LocalConfigException.Reason.INSECURE_FILE, failure.reason());
        assertEquals("decoy-content", ConfigStoreHarness.readContent(decoy), "the new token must never reach through the link");
        assertTrue(Files.isSymbolicLink(configPath));
        assertEquals(List.of("config.json"), entryNames(ConfigStoreHarness.managedDirectory(fabricatedHome)));
    }

    @Test
    void storeFailsClosedWhenTheDestinationIsReplacedWithADifferentFileMidWrite() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        String attackerDocument = ConfigStoreHarness.configDocument("attacker-token");
        Path watchedEntry = ConfigStoreHarness.entryName(configPath);
        AtomicInteger configViews = new AtomicInteger();
        SecureConfigStore store = new SecureConfigStore(
                () -> configPath,
                new JsonConfigFile(),
                ConfigStoreHarness::processOwner,
                directory -> SecureConfigStore.SecureStreamOpener.processFilesystem()
                        .open(directory)
                        .map(real -> new DelegatingSecureStream(real) {
                            @Override
                            public <V extends FileAttributeView> V getFileAttributeView(
                                    Path entry, Class<V> type, LinkOption... options) {
                                if (watchedEntry.equals(entry) && type == PosixFileAttributeView.class
                                        && configViews.incrementAndGet() == 2) {
                                    swapDestinationForADifferentFile(configPath, attackerDocument);
                                }
                                return delegate.getFileAttributeView(entry, type, options);
                            }
                        }),
                SecureConfigStore.HardLinkCounter.processFilesystem(),
                SecureConfigStore.AclProbe.disabled());

        LocalConfigException failure = assertThrows(LocalConfigException.class, () -> store.store(credential("token-two")));

        assertEquals(LocalConfigException.Reason.INSECURE_FILE, failure.reason());
        assertTrue(failure.getMessage().contains("identity"), () -> failure.getMessage());
        assertEquals(attackerDocument, ConfigStoreHarness.readContent(configPath));
        assertEquals(List.of("config.json"), entryNames(ConfigStoreHarness.managedDirectory(fabricatedHome)));
    }

    /** Lands a rival config while the store believes none exists. */
    private static void placeConfigBehindTheWrite(Path configPath, String apiKey) {
        try {
            ConfigStoreHarness.writeValidConfig(configPath, apiKey);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** Removes the destination while the store believes it still exists. */
    private static void vanishBehindTheWrite(Path path) {
        try {
            Files.delete(path);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** Opens a file's mode to its group while the store believes it is still owner-only. */
    private static void loosenModeBehindTheWrite(Path file) {
        try {
            ConfigStoreHarness.chmod(file, "rw-r--r--");
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** Creates an extra hard link to the temporary file before pre-move revalidation runs. */
    private static void addHardLinkBehindTheWrite(Path existing, Path link) {
        try {
            Files.createLink(link, existing);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void swapDestinationForSymlink(Path configPath, Path decoy) {
        try {
            Files.delete(configPath);
            Files.createSymbolicLink(configPath, decoy);
        } catch (IOException swapFailure) {
            throw new UncheckedIOException(swapFailure);
        }
    }

    private static void swapDestinationForADifferentFile(Path configPath, String document) {
        // the replacement must change the file identity on every filesystem: delete plus
        // recreate reuses the freed inode on several Linux filesystems, which looks like no
        // change at all. A hard link to a separate attacker file always carries a different
        // live identity, and deleting the decoy afterwards keeps the directory listing intact.
        Path decoy = configPath.resolveSibling("attacker-decoy.json");
        try {
            Files.writeString(decoy, document, StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(decoy, PosixFilePermissions.fromString("rw-------"));
            Files.delete(configPath);
            Files.createLink(configPath, decoy);
            Files.delete(decoy);
        } catch (IOException swapFailure) {
            throw new UncheckedIOException(swapFailure);
        }
    }

    private static io.amscotti.bravesearch.domain.config.Credential credential(String token) {
        return ConfigStoreHarness.credential(token);
    }

    private static List<String> entryNames(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            return entries.map(entry -> entry.getFileName().toString()).sorted().toList();
        }
    }
}

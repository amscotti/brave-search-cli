package io.amscotti.bravesearch.adapter.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.domain.config.LocalConfigException;
import io.amscotti.bravesearch.domain.config.PermissionRepair;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Permission repair restores the owner-only modes of the managed directory and the credential
 * file, reports exactly what changed, stays a no-op when nothing is loose, and refuses
 * structural violations instead of chmod-ing through them.
 */
final class ConfigPermissionRepairTest {

    @TempDir
    Path fabricatedHome;

    @Test
    void repairResetsLooseModesAndReportsBothChanges() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        Path managedDirectory = ConfigStoreHarness.managedDirectory(fabricatedHome);
        ConfigStoreHarness.chmod(managedDirectory, "rwxr-xr-x");
        ConfigStoreHarness.chmod(configPath, "rw-r--r--");

        PermissionRepair report = ConfigStoreHarness.store(configPath).repairPermissions();

        assertEquals(new PermissionRepair(true, true, false), report);
        assertEquals(PosixFilePermissions.fromString("rwx------"), ConfigStoreHarness.noFollowMode(managedDirectory));
        assertEquals(PosixFilePermissions.fromString("rw-------"), ConfigStoreHarness.noFollowMode(configPath));
        assertEquals(new PermissionRepair(false, false, false), ConfigStoreHarness.store(configPath).repairPermissions());
    }

    @Test
    void repairReportsOnlyTheDirectoryWhenNoFileExists() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        Path managedDirectory = ConfigStoreHarness.managedDirectory(fabricatedHome);
        Files.createDirectories(
                managedDirectory, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
        ConfigStoreHarness.chmod(managedDirectory, "rwxr-x---");

        PermissionRepair report = ConfigStoreHarness.store(configPath).repairPermissions();

        assertEquals(new PermissionRepair(true, false, false), report);
        assertFalse(Files.exists(configPath));
    }

    @Test
    void repairIsANoOpWhenEverythingIsAlreadyTight() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        assertEquals(new PermissionRepair(false, false, false), ConfigStoreHarness.store(configPath).repairPermissions());
    }

    @Test
    void repairLeavesAStricterThan0600FileUntouched() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        ConfigStoreHarness.chmod(configPath, "r--------");

        PermissionRepair report = ConfigStoreHarness.store(configPath).repairPermissions();

        assertEquals(new PermissionRepair(false, false, false), report);
        assertEquals(PosixFilePermissions.fromString("r--------"), ConfigStoreHarness.noFollowMode(configPath));
    }

    @Test
    void repairMarksTheAdvisoryWhenTheProbeSeesAnEntryBeyondTheOwner() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");

        PermissionRepair report = ConfigStoreHarness.store(configPath, probeListing("""
                -rw-------@ 1 someone staff 62 Aug 31 10:00 config.json
                 0: user:intruder allow read
                """))
                .repairPermissions();

        assertEquals(new PermissionRepair(false, false, true), report);
    }

    @Test
    void repairSeesNoAdvisoryWhenTheProbeListsOnlyOwnerEntries() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        String owner = ConfigStoreHarness.processOwner().getName();

        PermissionRepair report = ConfigStoreHarness.store(configPath, probeListing(
                        "-rw-------@ 1 someone staff 62 Aug 31 10:00 config.json\n 0: user:" + owner
                                + " allow read,write\n 1: owner allow write\n"))
                .repairPermissions();

        assertEquals(new PermissionRepair(false, false, false), report);
    }

    @Test
    void repairIgnoresAFailingOrUnavailableProbe() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");

        assertEquals(
                new PermissionRepair(false, false, false),
                ConfigStoreHarness.store(configPath, file -> Optional.empty()).repairPermissions());
        assertEquals(
                new PermissionRepair(false, false, false),
                ConfigStoreHarness
                        .store(configPath, file -> {
                            throw new UnsupportedOperationException("ls unavailable");
                        })
                        .repairPermissions());
    }

    @Test
    void repairConsultsNoProbeWithoutAConfigFile() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        java.util.concurrent.atomic.AtomicBoolean probed = new java.util.concurrent.atomic.AtomicBoolean();

        PermissionRepair report = ConfigStoreHarness.store(configPath, file -> {
                    probed.set(true);
                    return Optional.of(List.of(" 0: user:intruder allow read"));
                })
                .repairPermissions();

        assertEquals(new PermissionRepair(false, false, false), report);
        assertFalse(probed.get(), "no file means no ACL listing to consult");
    }

    @Test
    void repairRefusesASymlinkedConfigFile() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        Path decoy = fabricatedHome.resolve("decoy.json");
        Files.writeString(decoy, "decoy");
        Files.delete(configPath);
        Files.createSymbolicLink(configPath, decoy);
        LocalConfigException failure =
                assertThrows(LocalConfigException.class, () -> ConfigStoreHarness.store(configPath).repairPermissions());
        assertEquals(LocalConfigException.Reason.INSECURE_FILE, failure.reason());
        assertTrue(Files.isSymbolicLink(configPath), "the symlink must be left untouched");
    }

    @Test
    void repairRefusesASymlinkedManagedDirectory() throws IOException {
        Path configPath = ConfigStoreHarness.configPath(fabricatedHome);
        ConfigStoreHarness.writeValidConfig(configPath, "token-one");
        Path managedDirectory = ConfigStoreHarness.managedDirectory(fabricatedHome);
        Path realDirectory = fabricatedHome.resolve("elsewhere");
        Files.move(managedDirectory, realDirectory);
        Files.createSymbolicLink(managedDirectory, realDirectory);
        LocalConfigException failure =
                assertThrows(LocalConfigException.class, () -> ConfigStoreHarness.store(configPath).repairPermissions());
        assertEquals(LocalConfigException.Reason.INSECURE_FILE, failure.reason());
    }

    /** A probe double delivering a fixed {@code ls -le}-shaped listing. */
    private static SecureConfigStore.AclProbe probeListing(String listing) {
        return file -> Optional.of(List.of(listing.split("\\R")));
    }
}

package io.amscotti.bravesearch.adapter.config;

import io.amscotti.bravesearch.domain.config.Credential;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Fixture of the secure-store tests: a fabricated macOS-shaped home directory under an isolated
 * temporary filesystem, helpers to place valid or hostile configs, and store construction with
 * the real pinned-directory opener or injected fakes.
 */
final class ConfigStoreHarness {

    private ConfigStoreHarness() {}

    /** The managed directory of a fabricated macOS home. */
    static Path managedDirectory(Path fabricatedHome) {
        return fabricatedHome.resolve("Library/Application Support/brave-search");
    }

    /** The credential-file path of a fabricated macOS home. */
    static Path configPath(Path fabricatedHome) {
        return managedDirectory(fabricatedHome).resolve("config.json");
    }

    /** The real process owner, as the composition root would inject it. */
    static UserPrincipal processOwner() {
        try {
            return FileSystems.getDefault()
                    .getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"));
        } catch (IOException missing) {
            throw new IllegalStateException("the test user principal cannot be looked up", missing);
        }
    }

    /** A store wired exactly like the composition root would wire it. */
    static SecureConfigStore store(Path configPath) {
        return store(configPath, SecureConfigStore.AclProbe.disabled());
    }

    /** A store wired like the composition root but with the ACL probe double injected. */
    static SecureConfigStore store(Path configPath, SecureConfigStore.AclProbe aclProbe) {
        return new SecureConfigStore(
                () -> configPath,
                new JsonConfigFile(),
                ConfigStoreHarness::processOwner,
                SecureConfigStore.SecureStreamOpener.processFilesystem(),
                SecureConfigStore.HardLinkCounter.processFilesystem(),
                aclProbe);
    }

    /** A store wired like the composition root but with the hard-link counter double injected. */
    static SecureConfigStore storeWithHardLinkCounter(Path configPath, SecureConfigStore.HardLinkCounter hardLinks) {
        return new SecureConfigStore(
                () -> configPath,
                new JsonConfigFile(),
                ConfigStoreHarness::processOwner,
                SecureConfigStore.SecureStreamOpener.processFilesystem(),
                hardLinks,
                SecureConfigStore.AclProbe.disabled());
    }

    /** A store whose expected owner is deliberately wrong, to exercise the ownership refusal. */
    static SecureConfigStore storeExpectingForeignOwner(Path configPath) {
        try {
            UserPrincipal nobody =
                    FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName("nobody");
            return new SecureConfigStore(
                    () -> configPath,
                    new JsonConfigFile(),
                    () -> nobody,
                    SecureConfigStore.SecureStreamOpener.processFilesystem(),
                    SecureConfigStore.HardLinkCounter.processFilesystem(),
                    SecureConfigStore.AclProbe.disabled());
        } catch (IOException missing) {
            throw new IllegalStateException("the nobody principal cannot be looked up", missing);
        }
    }

    static Credential credential(String token) {
        return Credential.of(token.getBytes(StandardCharsets.UTF_8));
    }

    /** Places a well-formed owner-only v1 config, creating the managed directory 0700. */
    static void writeValidConfig(Path configPath, String apiKey) throws IOException {
        Files.createDirectories(
                configPath.getParent(),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.writeString(configPath, configDocument(apiKey), StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(configPath, PosixFilePermissions.fromString("rw-------"));
    }

    /** Places raw document bytes as the config, creating the managed directory 0700. */
    static void writeRawConfig(Path configPath, byte[] document) throws IOException {
        Files.createDirectories(
                configPath.getParent(),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.write(configPath, document);
        Files.setPosixFilePermissions(configPath, PosixFilePermissions.fromString("rw-------"));
    }

    static String configDocument(String apiKey) {
        return "{\"schema_version\":\"1\",\"api_key\":\"" + apiKey + "\"}\n";
    }

    /** The relative entry name of the config file, as the pinned-directory operations see it. */
    static Path entryName(Path configPath) {
        return configPath.getFileSystem().getPath(configPath.getFileName().toString());
    }

    static void chmod(Path path, String mode) throws IOException {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode));
    }

    static Set<PosixFilePermission> noFollowMode(Path path) throws IOException {
        return Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                .permissions();
    }

    static String readContent(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    static FileSystem defaultFileSystem() {
        return FileSystems.getDefault();
    }

    static Supplier<UserPrincipal> ownerSupplier(UserPrincipal principal) {
        return () -> principal;
    }
}

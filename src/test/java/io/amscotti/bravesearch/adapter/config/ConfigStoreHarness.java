package io.amscotti.bravesearch.adapter.config;

import io.amscotti.bravesearch.domain.config.Credential;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Optional;
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

    /**
     * A store whose expected owner is deliberately wrong, to exercise the ownership refusal of
     * the managed directory — the first ownership check every exchange makes, so the config
     * file's own ownership rule is never reached through this store.
     */
    static SecureConfigStore storeExpectingForeignOwner(Path configPath) {
        return new SecureConfigStore(
                () -> configPath,
                new JsonConfigFile(),
                ConfigStoreHarness.ownerSupplier(foreignOwner()),
                SecureConfigStore.SecureStreamOpener.processFilesystem(),
                SecureConfigStore.HardLinkCounter.processFilesystem(),
                SecureConfigStore.AclProbe.disabled());
    }

    /** An account that owns nothing of this process: the standing-in outsider. */
    static UserPrincipal foreignOwner() {
        try {
            return FileSystems.getDefault()
                    .getUserPrincipalLookupService()
                    .lookupPrincipalByName("nobody");
        } catch (IOException missing) {
            throw new IllegalStateException("the nobody principal cannot be looked up", missing);
        }
    }

    /** A store wired like the composition root but with the directory opener double injected. */
    static SecureConfigStore storeWithOpener(Path configPath, SecureConfigStore.SecureStreamOpener opener) {
        return new SecureConfigStore(
                () -> configPath,
                new JsonConfigFile(),
                ConfigStoreHarness::processOwner,
                opener,
                SecureConfigStore.HardLinkCounter.processFilesystem(),
                SecureConfigStore.AclProbe.disabled());
    }

    /** A store on a filesystem that offers no pinned directory operations at all. */
    static SecureConfigStore storeOnAnUnpinnableFilesystem(Path configPath) {
        return storeWithOpener(configPath, directory -> Optional.empty());
    }

    /**
     * A store that observes the config entry owned by the outsider while the managed directory
     * is seen exactly as the process filesystem reports it — the state of a file another
     * account created inside an otherwise well-owned directory.
     */
    static SecureConfigStore storeSeeingAConfigFileOwnedByAnotherUser(Path configPath) {
        UserPrincipal outsider = foreignOwner();
        Path configEntry = entryName(configPath);
        return storeWithOpener(
                configPath,
                directory -> SecureConfigStore.SecureStreamOpener.processFilesystem()
                        .open(directory)
                        .map(real -> new DelegatingSecureStream(real) {
                            @Override
                            public <V extends FileAttributeView> V getFileAttributeView(
                                    Path entry, Class<V> type, LinkOption... options) {
                                V view = delegate.getFileAttributeView(entry, type, options);
                                if (configEntry.equals(entry) && view instanceof PosixFileAttributeView posix) {
                                    return type.cast(new ForeignOwnedView(posix, outsider));
                                }
                                return view;
                            }
                        }));
    }

    /** The config entry's POSIX view, presenting the outsider as the file's owner. */
    private record ForeignOwnedView(PosixFileAttributeView real, UserPrincipal outsider)
            implements PosixFileAttributeView {

        @Override
        public PosixFileAttributes readAttributes() throws IOException {
            return new ForeignOwnedAttributes(real.readAttributes(), outsider);
        }

        @Override
        public String name() {
            return real.name();
        }

        @Override
        public UserPrincipal getOwner() {
            return outsider;
        }

        @Override
        public void setOwner(UserPrincipal owner) throws IOException {
            real.setOwner(owner);
        }

        @Override
        public void setTimes(FileTime lastModified, FileTime lastAccess, FileTime create) throws IOException {
            real.setTimes(lastModified, lastAccess, create);
        }

        @Override
        public void setPermissions(Set<PosixFilePermission> perms) throws IOException {
            real.setPermissions(perms);
        }

        @Override
        public void setGroup(GroupPrincipal group) throws IOException {
            real.setGroup(group);
        }
    }

    /** The entry's real attributes, presented under the outsider's ownership. */
    private record ForeignOwnedAttributes(PosixFileAttributes real, UserPrincipal outsider)
            implements PosixFileAttributes {

        @Override
        public UserPrincipal owner() {
            return outsider;
        }

        @Override
        public GroupPrincipal group() {
            return real.group();
        }

        @Override
        public Set<PosixFilePermission> permissions() {
            return real.permissions();
        }

        @Override
        public FileTime lastModifiedTime() {
            return real.lastModifiedTime();
        }

        @Override
        public FileTime lastAccessTime() {
            return real.lastAccessTime();
        }

        @Override
        public FileTime creationTime() {
            return real.creationTime();
        }

        @Override
        public boolean isDirectory() {
            return real.isDirectory();
        }

        @Override
        public boolean isOther() {
            return real.isOther();
        }

        @Override
        public boolean isRegularFile() {
            return real.isRegularFile();
        }

        @Override
        public boolean isSymbolicLink() {
            return real.isSymbolicLink();
        }

        @Override
        public long size() {
            return real.size();
        }

        @Override
        public Object fileKey() {
            return real.fileKey();
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

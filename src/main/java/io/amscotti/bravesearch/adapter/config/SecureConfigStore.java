package io.amscotti.bravesearch.adapter.config;

import io.amscotti.bravesearch.application.port.out.ConfigStore;
import io.amscotti.bravesearch.domain.config.ConfigFileSight;
import io.amscotti.bravesearch.domain.config.ConfigFileSummary;
import io.amscotti.bravesearch.domain.config.ConfigState;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.config.LocalConfigException;
import io.amscotti.bravesearch.domain.config.PermissionRepair;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Secure credential-file access behind the {@link ConfigStore} port.
 *
 * <p>Every exchange pins the managed directory with a {@link SecureDirectoryStream} and performs
 * all entry operations — attribute reads, the temporary file, and the replacement — relative to
 * that pinned directory descriptor, without following symbolic links. Load validates a
 * user-owned {@code 0700} managed directory and a user-owned owner-only regular file with a
 * single hard link before reading bounded bytes. Replacement creates a random {@code 0600}
 * sibling with {@code CREATE_NEW}, writes and fsyncs it, revalidates the temporary file and the
 * destination identity immediately before an atomic same-directory move, and fails closed —
 * retaining the old config — on any detected change or when atomic replacement is unsupported.
 * A best-effort directory fsync follows the move.
 *
 * <p>Platform path: the macOS/Linux JDK returns {@code sun.nio.fs.UnixSecureDirectoryStream}
 * from {@link Files#newDirectoryStream}, so this build always takes the dir-relative path; a
 * filesystem that cannot pin directory operations fails closed rather than silently downgrading
 * to path-based checks. The hard-link count has no dir-relative JDK accessor — {@code
 * PosixFileAttributes} carries no {@code nlink} — so it is read additionally through the {@code
 * unix:nlink} view and enforced fail-closed: more than one link is the extra-link refusal, and
 * an unobservable count is itself a refusal, because both advertised platforms expose it.
 *
 * <p>Repair additionally consults a best-effort {@link AclProbe} listing, because POSIX modes
 * cannot see macOS ACL entries; a listing that shows an entry beyond the owner turns into an
 * advisory on the repair report and never into a repair.
 *
 * <p>Residual threats, deliberately out of scope: a concurrent same-user attacker who replaces
 * an ancestor directory of the managed path (including the managed directory's own entry in its
 * parent) can suppress an update — the pinned write still lands safely inside the validated
 * directory, and the visible path keeps the attacker's file; detecting that requires pinning
 * parent directories, which the JDK does not expose, and the post-move directory fsync — opened
 * by path once the pinned stream has closed — inherits the same weakening as best-effort
 * durability only. A normal atomic replacement intentionally does not preserve the old file's
 * ACLs or extended attributes; re-establish such metadata explicitly after a write if it is
 * required. Narrow time-of-check/time-of-use windows remain between the no-follow attribute
 * reads and subsequent opens. The owner comparison uses principal names, and Windows
 * persistence stays disabled until its user-only DACL contract exists.
 */
public final class SecureConfigStore implements ConfigStore {

    private static final Set<PosixFilePermission> DIRECTORY_MODE =
            PosixFilePermissions.fromString("rwx------");

    private static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-------");

    private static final Set<PosixFilePermission> NON_OWNER_BITS = Collections.unmodifiableSet(
            EnumSet.of(
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_WRITE,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_WRITE,
                    PosixFilePermission.OTHERS_EXECUTE));

    private static final String TEMP_PREFIX = "brave-search-config-";

    private static final String TEMP_SUFFIX = ".tmp";

    /** An indexed {@code ls -le} ACL entry line: {@code N: principal allow|deny actions}. */
    private static final Pattern ACL_ENTRY = Pattern.compile("\\s*\\d+:\\s+(\\S+)\\s+(?:allow|deny)\\b.*");

    private final Supplier<Path> configPath;

    private final JsonConfigFile codec;

    private final Supplier<UserPrincipal> processOwner;

    private final SecureStreamOpener secureStreams;

    private final HardLinkCounter hardLinks;

    private final AclProbe aclProbe;

    private Path resolvedConfigPath;

    /**
     * The credential-file path arrives as a supplier so a composition root can build the whole
     * command line before — and independently of — the platform path rules deciding anything:
     * resolution happens on first use, at most once per store, and a misconfigured environment
     * surfaces as the typed configuration failure at that first use instead of at construction.
     * The hard-link counter and ACL probe are the injectable seams behind the corresponding
     * platform probes.
     */
    public SecureConfigStore(
            Supplier<Path> configPath,
            JsonConfigFile codec,
            Supplier<UserPrincipal> processOwner,
            SecureStreamOpener secureStreams,
            HardLinkCounter hardLinks,
            AclProbe aclProbe) {
        this.configPath = Objects.requireNonNull(configPath, "configPath");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.processOwner = Objects.requireNonNull(processOwner, "processOwner");
        this.secureStreams = Objects.requireNonNull(secureStreams, "secureStreams");
        this.hardLinks = Objects.requireNonNull(hardLinks, "hardLinks");
        this.aclProbe = Objects.requireNonNull(aclProbe, "aclProbe");
    }

    private Path configPath() {
        Path resolved = resolvedConfigPath;
        if (resolved == null) {
            resolved = Objects.requireNonNull(configPath.get(), "configPath");
            if (!resolved.isAbsolute()) {
                throw new IllegalArgumentException("configPath must be absolute: " + resolved);
            }
            resolvedConfigPath = resolved;
        }
        return resolved;
    }

    /**
     * Reads a file's hard-link count through the {@code unix:nlink} view; both advertised
     * platforms expose it, so an unobservable count is a refusal, never a silent pass.
     */
    @FunctionalInterface
    public interface HardLinkCounter {

        /**
         * The number of directory entries naming the file.
         *
         * @throws IOException when the attribute view fails
         */
        long count(Path path) throws IOException;

        /** The process filesystem: the {@code unix:nlink} attribute, no-follow. */
        static HardLinkCounter processFilesystem() {
            return path -> {
                Object nlink = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
                if (nlink instanceof Number number) {
                    return number.longValue();
                }
                throw new UnsupportedOperationException("unix:nlink is not numeric");
            };
        }
    }

    /**
     * Best-effort macOS ACL inspection for repair reporting: the JDK on this platform exposes
     * no ACL view, so the only window is a bounded {@code ls -le} listing. The listing is an
     * advisory input only — probe failures vanish, and nothing about the secure-access contract
     * depends on it.
     */
    @FunctionalInterface
    public interface AclProbe {

        /**
         * The {@code ls -le} listing lines of the file, empty when no listing is available.
         */
        Optional<List<String>> listing(Path file);

        /**
         * The real macOS probe: a bounded {@code /bin/ls -le} subprocess whose failures are
         * ignored, so a hung or missing {@code ls} costs nothing but the advisory.
         */
        static AclProbe macOsListing() {
            return file -> {
                try {
                    Process lister = new ProcessBuilder("/bin/ls", "-le", file.toString())
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start();
                    if (!lister.waitFor(2, TimeUnit.SECONDS)) {
                        lister.destroyForcibly();
                        return Optional.empty();
                    }
                    String listing;
                    try (InputStream output = lister.getInputStream()) {
                        listing = new String(output.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    return lister.exitValue() == 0
                            ? Optional.of(List.of(listing.split("\\R")))
                            : Optional.empty();
                } catch (IOException | InterruptedException unavailable) {
                    if (unavailable instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    return Optional.empty();
                }
            };
        }

        /** A probe that never inspects, for platforms without the blind spot and for tests. */
        static AclProbe disabled() {
            return file -> Optional.empty();
        }
    }

    /**
     * Opens a managed directory as a pinned dir-relative stream; the empty case means the
     * filesystem cannot pin directory operations and callers must fail closed.
     */
    @FunctionalInterface
    public interface SecureStreamOpener {

        /**
         * Opens the directory, keeping the stream only when secure pinning is available.
         *
         * @throws IOException when the directory cannot be opened
         */
        Optional<SecureDirectoryStream<Path>> open(Path directory) throws IOException;

        /** The process filesystem: POSIX-capable JDKs return a pinned secure stream. */
        static SecureStreamOpener processFilesystem() {
            return directory -> {
                DirectoryStream<Path> stream = Files.newDirectoryStream(directory);
                if (stream instanceof SecureDirectoryStream<Path> secure) {
                    return Optional.of(secure);
                }
                stream.close();
                return Optional.empty();
            };
        }
    }

    @Override
    public ConfigState load() {
        Path directory = configPath().getParent();
        if (readNoFollow(directory).isEmpty()) {
            return absentState();
        }
        try (SecureDirectoryStream<Path> stream = openManagedDirectory(directory, true)) {
            PosixFileAttributes attributes = noFollowAttributes(stream, entryName());
            if (attributes == null) {
                return absentState();
            }
            requireSecureConfigFile(attributes, configPath());
            byte[] content = readBounded(stream, entryName());
            Optional<String> apiKey = codec.decodeApiKey(content);
            Optional<Credential> credential =
                    apiKey.map(key -> Credential.of(key.getBytes(StandardCharsets.UTF_8)));
            return new ConfigState(credential, new ConfigFileSummary(1, configPath(), true));
        } catch (IOException failure) {
            throw unsafeFilesystem(failure);
        }
    }

    /**
     * How the file presents without building the stored credential: the same structural
     * validation a load applies, then only whether the document stores a key — an invalid key
     * still reads as stored, and every structural or format failure surfaces as its typed
     * configuration error.
     */
    @Override
    public ConfigFileSight peekSummary() {
        Path directory = configPath().getParent();
        if (readNoFollow(directory).isEmpty()) {
            return ConfigFileSight.ABSENT;
        }
        try (SecureDirectoryStream<Path> stream = openManagedDirectory(directory, true)) {
            PosixFileAttributes attributes = noFollowAttributes(stream, entryName());
            if (attributes == null) {
                return ConfigFileSight.ABSENT;
            }
            requireSecureConfigFile(attributes, configPath());
            byte[] content = readBounded(stream, entryName());
            return codec.decodeApiKey(content).isPresent() ? ConfigFileSight.KEY_STORED : ConfigFileSight.NO_KEY_STORED;
        } catch (IOException failure) {
            throw unsafeFilesystem(failure);
        }
    }

    @Override
    public void store(Credential credential) {
        Objects.requireNonNull(credential, "credential");
        replaceWith(codec.encode(credential));
    }

    /**
     * Removes the stored key by atomically replacing the file with the schema-only document; a
     * file that is already absent stays absent, so the absent state keeps meaning "never
     * configured" rather than "configured and cleared".
     */
    @Override
    public void clear() {
        if (readNoFollow(configPath()).isEmpty()) {
            return;
        }
        replaceWith(codec.encodeCleared());
    }

    @Override
    public PermissionRepair repairPermissions() {
        Path directory = configPath().getParent();
        boolean directoryChanged = false;
        boolean fileChanged = false;
        boolean nonOwnerAclSuspected = false;
        if (readNoFollow(directory).isPresent()) {
            try (SecureDirectoryStream<Path> stream = openManagedDirectory(directory, false)) {
                PosixFileAttributeView self = selfView(stream);
                if (!DIRECTORY_MODE.equals(self.readAttributes().permissions())) {
                    self.setPermissions(DIRECTORY_MODE);
                    directoryChanged = true;
                }
                PosixFileAttributes fileAttributes = noFollowAttributes(stream, entryName());
                if (fileAttributes != null) {
                    requireRepairableConfigFile(fileAttributes, configPath());
                    if (!Collections.disjoint(fileAttributes.permissions(), NON_OWNER_BITS)) {
                        // tighten only: non-owner bits earn the full 0600 reset, while an
                        // already-owner-only file stricter than 0600 (for example 0400) is
                        // never loosened back up to it
                        fileView(stream, entryName()).setPermissions(FILE_MODE);
                        fileChanged = true;
                    }
                    nonOwnerAclSuspected = suspectsEntryBeyondTheOwner();
                }
            } catch (IOException failure) {
                throw unsafeFilesystem(failure);
            }
        }
        return new PermissionRepair(directoryChanged, fileChanged, nonOwnerAclSuspected);
    }

    /** Consults the probe inside the best-effort contract: a failing probe costs only the advisory. */
    private boolean suspectsEntryBeyondTheOwner() {
        try {
            return listsEntryBeyondTheOwner(aclProbe.listing(configPath()));
        } catch (RuntimeException probeFailed) {
            return false;
        }
    }

    /**
     * Whether the best-effort listing carries an ACL entry whose principal is someone other
     * than the process owner. The parse is deliberately conservative: any indexed entry line
     * whose principal is not an owner-shaped one counts, so the advisory may fire on entries a
     * stricter reading would excuse — it never clears one.
     */
    private boolean listsEntryBeyondTheOwner(Optional<List<String>> listing) {
        return listing.map(lines -> {
                    String ownerName = processOwner.get().getName();
                    return lines.stream().anyMatch(line -> {
                        Matcher entry = ACL_ENTRY.matcher(line);
                        return entry.matches() && !ownerShapedPrincipal(entry.group(1), ownerName);
                    });
                })
                .orElse(false);
    }

    private static boolean ownerShapedPrincipal(String principal, String ownerName) {
        return principal.equals("owner") || principal.equals(ownerName) || principal.equals("user:" + ownerName);
    }

    private void replaceWith(byte[] document) {
        Path directory = configPath().getParent();
        ensureDirectoryExists(directory);
        try (SecureDirectoryStream<Path> stream = openManagedDirectory(directory, true)) {
            PosixFileAttributes destinationBefore = noFollowAttributes(stream, entryName());
            Object destinationKeyBefore = null;
            if (destinationBefore != null) {
                requireSecureConfigFile(destinationBefore, configPath());
                destinationKeyBefore = destinationBefore.fileKey();
            }
            Path temp = directory.getFileSystem().getPath(TEMP_PREFIX + UUID.randomUUID() + TEMP_SUFFIX);
            boolean replaced = false;
            try {
                writeSyncedTemp(stream, temp, document);
                revalidateBeforeReplacement(stream, temp, destinationBefore, destinationKeyBefore);
                stream.move(temp, stream, entryName());
                replaced = true;
            } finally {
                if (!replaced) {
                    discardTemp(stream, temp);
                }
            }
        } catch (IOException failure) {
            throw unsafeFilesystem(failure);
        }
        syncDirectoryBestEffort(directory);
    }

    private void revalidateBeforeReplacement(
            SecureDirectoryStream<Path> stream, Path temp, PosixFileAttributes destinationBefore, Object destinationKeyBefore)
            throws IOException {
        PosixFileAttributes tempAttributes = noFollowAttributes(stream, temp);
        if (tempAttributes == null
                || !tempAttributes.isRegularFile()
                || !FILE_MODE.equals(tempAttributes.permissions())
                || !ownedByProcess(tempAttributes)) {
            throw LocalConfigException.insecureFile("the freshly written temporary config file failed its revalidation");
        }
        PosixFileAttributes destinationNow = noFollowAttributes(stream, entryName());
        if (destinationBefore == null) {
            if (destinationNow != null) {
                throw LocalConfigException.insecureFile("the config file appeared during the write; refusing to replace");
            }
            return;
        }
        if (destinationNow == null) {
            throw LocalConfigException.insecureFile("the config file vanished during the write; refusing to replace");
        }
        requireSecureConfigFile(destinationNow, configPath());
        requireSingleHardLink(configPath().getParent().resolve(temp));
        if (!Objects.equals(destinationKeyBefore, destinationNow.fileKey())) {
            throw LocalConfigException.insecureFile("the config file changed identity during the write; refusing to replace");
        }
    }

    private void writeSyncedTemp(SecureDirectoryStream<Path> stream, Path temp, byte[] document) throws IOException {
        try (SeekableByteChannel created = stream.newByteChannel(
                temp,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(EnumSet.copyOf(FILE_MODE)))) {
            if (!(created instanceof FileChannel channel)) {
                throw LocalConfigException.unsafeFilesystem("the filesystem cannot fsync a freshly created file");
            }
            ByteBuffer buffer = ByteBuffer.wrap(document);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private void ensureDirectoryExists(Path directory) {
        if (readNoFollow(directory).isPresent()) {
            return;
        }
        try {
            Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(EnumSet.copyOf(DIRECTORY_MODE)));
        } catch (FileAlreadyExistsException occupied) {
            throw LocalConfigException.insecureFile(
                    "the config directory path exists but is not a user-owned 0700 directory: " + directory);
        } catch (UnsupportedOperationException nonPosix) {
            throw LocalConfigException.unsafeFilesystem(
                    "the filesystem of " + directory + " does not expose POSIX attributes");
        } catch (IOException unavailable) {
            throw unsafeFilesystem(unavailable);
        }
    }

    private SecureDirectoryStream<Path> openManagedDirectory(Path directory, boolean requireOwnerOnlyMode)
            throws IOException {
        PosixFileAttributes expected = readNoFollow(directory)
                .orElseThrow(() -> LocalConfigException.insecureFile("the config directory is missing: " + directory));
        requireManagedDirectory(expected, requireOwnerOnlyMode);
        Optional<SecureDirectoryStream<Path>> opened = secureStreams.open(directory);
        if (opened.isEmpty()) {
            throw LocalConfigException.unsafeFilesystem(
                    "the filesystem of " + directory + " cannot pin directory operations");
        }
        SecureDirectoryStream<Path> stream = opened.get();
        PosixFileAttributes pinned = selfView(stream).readAttributes();
        if (!sameIdentity(expected, pinned)) {
            throw LocalConfigException.insecureFile(
                    "the config directory changed identity while it was opened: " + directory);
        }
        requireManagedDirectory(pinned, requireOwnerOnlyMode);
        return stream;
    }

    private void requireManagedDirectory(PosixFileAttributes attributes, boolean requireOwnerOnlyMode) {
        if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
            throw LocalConfigException.insecureFile(
                    "the config directory is not a real directory: " + configPath().getParent());
        }
        if (!ownedByProcess(attributes)) {
            throw LocalConfigException.insecureFile(
                    "the config directory is not owned by the current user: " + configPath().getParent());
        }
        if (requireOwnerOnlyMode && !DIRECTORY_MODE.equals(attributes.permissions())) {
            throw LocalConfigException.insecureFile("the config directory must have mode 0700: " + configPath().getParent());
        }
    }

    private void requireSecureConfigFile(PosixFileAttributes attributes, Path path) {
        if (attributes.isSymbolicLink()) {
            throw LocalConfigException.insecureFile("the config file is a symbolic link: " + path);
        }
        if (!attributes.isRegularFile()) {
            throw LocalConfigException.insecureFile("the config file is not a regular file: " + path);
        }
        if (!Collections.disjoint(attributes.permissions(), NON_OWNER_BITS)) {
            throw LocalConfigException.insecureFile("the config file must be owner-only (0600 or stricter): " + path);
        }
        requireSingleHardLink(path);
        if (!ownedByProcess(attributes)) {
            throw LocalConfigException.insecureFile("the config file is not owned by the current user: " + path);
        }
    }

    private void requireRepairableConfigFile(PosixFileAttributes attributes, Path path) {
        if (attributes.isSymbolicLink()) {
            throw LocalConfigException.insecureFile(
                    "the config file is a symbolic link and cannot be repaired, only refused: " + path);
        }
        if (!attributes.isRegularFile()) {
            throw LocalConfigException.insecureFile("the config file is not a regular file: " + path);
        }
        if (!ownedByProcess(attributes)) {
            throw LocalConfigException.insecureFile("the config file is not owned by the current user: " + path);
        }
        requireSingleHardLink(path);
    }

    /**
     * Exactly one hard link passes. More is the extra-link refusal; anything else — a missing
     * count, a failed attribute view, a nonsensical zero — is the fail-closed filesystem
     * refusal, because both advertised platforms expose the count and an unobservable one is
     * suspicious rather than reassuring.
     */
    private void requireSingleHardLink(Path path) {
        long count;
        try {
            count = hardLinks.count(path);
        } catch (IOException | UnsupportedOperationException unobservable) {
            throw LocalConfigException.unsafeFilesystem("the hard-link count of " + path + " cannot be observed");
        }
        if (count > 1) {
            throw LocalConfigException.insecureFile("the config file has " + count + " hard links: " + path);
        }
        if (count != 1) {
            throw LocalConfigException.unsafeFilesystem("the hard-link count of " + path + " cannot be observed");
        }
    }

    private static PosixFileAttributes noFollowAttributes(SecureDirectoryStream<Path> stream, Path entry)
            throws IOException {
        PosixFileAttributes attributes;
        try {
            attributes = fileView(stream, entry).readAttributes();
        } catch (NoSuchFileException absent) {
            return null;
        }
        return attributes;
    }

    private static PosixFileAttributeView fileView(SecureDirectoryStream<Path> stream, Path entry) {
        PosixFileAttributeView view = stream.getFileAttributeView(entry, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw LocalConfigException.unsafeFilesystem("the filesystem exposes no POSIX attributes for " + entry);
        }
        return view;
    }

    private static PosixFileAttributeView selfView(SecureDirectoryStream<Path> stream) {
        PosixFileAttributeView view = stream.getFileAttributeView(PosixFileAttributeView.class);
        if (view == null) {
            throw LocalConfigException.unsafeFilesystem("the filesystem exposes no POSIX attributes for the config directory");
        }
        return view;
    }

    private byte[] readBounded(SecureDirectoryStream<Path> stream, Path entry) throws IOException {
        try (SeekableByteChannel channel =
                stream.newByteChannel(entry, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            ByteBuffer buffer = ByteBuffer.allocate(JsonConfigFile.MAX_BYTES + 1);
            while (channel.read(buffer) >= 0 && buffer.hasRemaining()) {
                // read until end of stream or the bound plus one byte, whichever comes first
            }
            if (buffer.position() > JsonConfigFile.MAX_BYTES) {
                throw LocalConfigException.malformedFile(
                        "the config file exceeds the " + JsonConfigFile.MAX_BYTES + "-byte bound");
            }
            return Arrays.copyOf(buffer.array(), buffer.position());
        }
    }

    private ConfigState absentState() {
        return new ConfigState(Optional.empty(), new ConfigFileSummary(0, configPath(), false));
    }

    private boolean ownedByProcess(PosixFileAttributes attributes) {
        UserPrincipal owner = attributes.owner();
        UserPrincipal expected = processOwner.get();
        return owner != null && expected != null && owner.getName().equals(expected.getName());
    }

    private Path entryName() {
        return configPath().getFileSystem().getPath(configPath().getFileName().toString());
    }

    private static void discardTemp(SecureDirectoryStream<Path> stream, Path temp) {
        try {
            stream.deleteFile(temp);
        } catch (IOException unrestored) {
            // best effort: a retained owner-only temp sibling does not reduce the stored secret's safety
        }
    }

    private static void syncDirectoryBestEffort(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException unsynced) {
            // best effort: the replacement itself is atomic and the stored file is already fsynced
        }
    }

    private static boolean sameIdentity(PosixFileAttributes before, PosixFileAttributes after) {
        Object beforeKey = before.fileKey();
        Object afterKey = after.fileKey();
        if (beforeKey != null && afterKey != null) {
            return beforeKey.equals(afterKey);
        }
        return before.isRegularFile() == after.isRegularFile()
                && before.size() == after.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime());
    }

    private static Optional<PosixFileAttributes> readNoFollow(Path path) {
        try {
            return Optional.of(Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
        } catch (NoSuchFileException absent) {
            return Optional.empty();
        } catch (UnsupportedOperationException nonPosix) {
            throw LocalConfigException.unsafeFilesystem("the filesystem of " + path + " does not expose POSIX attributes");
        } catch (IOException unavailable) {
            throw unsafeFilesystem(unavailable);
        }
    }

    private static LocalConfigException unsafeFilesystem(IOException failure) {
        if (failure instanceof FileSystemException system && system.getReason() != null) {
            return LocalConfigException.unsafeFilesystem("the credential file exchange failed: " + system.getReason());
        }
        return LocalConfigException.unsafeFilesystem(
                "the credential file exchange failed: " + failure.getClass().getSimpleName());
    }
}

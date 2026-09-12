package io.amscotti.bravesearch.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies the packaged release archive end to end against the durable release contract: the
 * target-triple name and one-top-level-directory layout, the preserved executable mode of the
 * native binary inside the tar, a checksum file that covers every release artifact and matches
 * its digests, and a CycloneDX SBOM scoped to exactly the production runtime.
 *
 * <p>The archive path and the release directory arrive as system properties from the build, the
 * same wiring the native binary path uses; unpacking and digesting use the platform's own tar
 * and SHA-256 so the proof mirrors what an installer does, not what the packager did.
 */
final class ReleaseArchiveVerificationTest {

    private static final Pattern ARCHIVE_NAME =
            Pattern.compile("^brave-search-[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z.-]+)?-(macos|linux)-(aarch64|x86_64)\\.tar\\.gz$");

    /** Same form as {@link #ARCHIVE_NAME} with the version and platform captured. */
    private static final Pattern ARCHIVE_STEM =
            Pattern.compile("^brave-search-(.+)-(macos|linux)-(aarch64|x86_64)$");

    private static final Pattern VERSION_LINE = Pattern.compile("brave-search \\S+");

    /** Test-only dependencies that must never appear in the runtime-scoped SBOM. */
    private static final Set<String> FORBIDDEN_SBOM_GROUPS =
            Set.of("org.junit", "org.junit.jupiter", "org.junit.platform", "com.tngtech.archunit", "com.networknt");

    @Test
    @Timeout(value = 300)
    void archiveNameAndTopLevelDirectoryFollowTheTargetTripleForm() throws Exception {
        Path archive = releaseArchive();
        assertTrue(
                ARCHIVE_NAME.matcher(archive.getFileName().toString()).matches(),
                "archive name must follow brave-search-<version>-<os>-<arch>.tar.gz: "
                        + archive.getFileName());
        String topDir = archive.getFileName().toString().replaceAll("\\.tar\\.gz$", "");
        Set<String> entries = new LinkedHashSet<>(tarListing(archive));

        Set<String> topLevel = new LinkedHashSet<>();
        for (String entry : entries) {
            int slash = entry.indexOf('/');
            assertTrue(slash > 0, () -> "archive entry must live under the one top-level directory: " + entry);
            topLevel.add(entry.substring(0, slash));
        }
        assertEquals(Set.of(topDir), topLevel, "exactly one top-level directory named like the archive");

        Set<String> inside = new LinkedHashSet<>();
        for (String entry : entries) {
            if (!entry.endsWith("/")) {
                inside.add(entry.substring(topDir.length() + 1));
            }
        }
        String version = archiveVersion(topDir);
        for (String required :
                List.of(
                        "bin/brave-search",
                        "README.md",
                        "LICENSE",
                        "completions/brave-search.bash",
                        "completions/brave-search.zsh",
                        "skills/brave-search/SKILL.md",
                        "sbom/bom.json",
                        "lib/brave-search-client-" + version + ".jar",
                        "lib/brave-search-client-" + version + "-sources.jar",
                        "lib/brave-search-client-" + version + "-javadoc.jar")) {
            assertTrue(inside.contains(required), () -> "archive lacks the packaged file: " + required);
        }
        for (String entry : inside) {
            assertFalse(entry.startsWith(".env"), "credential files never ride inside the archive: " + entry);
            assertFalse(entry.endsWith(".lockfile") || entry.endsWith("lockState"), "lock state never ships: " + entry);
        }
    }

    @Test
    @Timeout(value = 300)
    void archiveCarriesTheMachineOutputSchemas() throws Exception {
        Path archive = releaseArchive();
        String topDir = archive.getFileName().toString().replaceAll("\\.tar\\.gz$", "");
        Set<String> schemas = new LinkedHashSet<>();
        for (String entry : tarListing(archive)) {
            String inside = entry.startsWith(topDir + "/") ? entry.substring(topDir.length() + 1) : entry;
            if (inside.startsWith("schemas/") && !inside.endsWith("/")) {
                schemas.add(inside);
            }
        }
        assertFalse(schemas.isEmpty(), "archive must carry the machine output schemas for agents");
        for (String schema : schemas) {
            assertTrue(
                    schema.endsWith(".schema.json"),
                    () -> "only versioned machine schemas ride under schemas/: " + schema);
        }
        assertTrue(
                schemas.contains("schemas/v1/news-summary.schema.json"),
                () -> "the news machine summary schema must ship: " + schemas);
    }

    @Test
    @Timeout(value = 300)
    void unpackedBinaryKeepsItsExecutableModeAndRunsVersion() throws Exception {
        Path archive = releaseArchive();
        Path unpacked = Files.createTempDirectory("brave-search-release-unpack");
        try {
            runCommand("tar", "-xzf", archive.toString(), "-C", unpacked.toString());
            Path topDir = unpacked.resolve(archive.getFileName().toString().replaceAll("\\.tar\\.gz$", ""));
            Path binary = topDir.resolve("bin").resolve("brave-search");
            assertTrue(Files.isRegularFile(binary), () -> "unpacked binary missing: " + binary);
            assertTrue(Files.isExecutable(binary), () -> "executable mode lost in the archive: " + binary);

            CapturedProcess result = runCommand(binary.toString(), "--version");
            assertEquals(0, result.exitCode(), () -> "--version failed: " + result.describe());
            assertTrue(
                    VERSION_LINE.matcher(result.stdout().strip()).matches(),
                    () -> "--version must print one 'brave-search <version>' line: " + result.describe());
            String printedVersion = result.stdout().strip().split(" ")[1];
            assertEquals(
                    archiveVersion(topDir.getFileName().toString()),
                    printedVersion,
                    () -> "the running binary's version must equal the archive's version exactly,"
                            + " like the release verify legs' grep -qx: " + printedVersion + " vs "
                            + topDir.getFileName());

            CapturedProcess bogus = runCommand(binary.toString(), "--definitely-bogus");
            assertFalse(bogus.exitCode() == 0, "an unknown option must fail after unpacking too");
        } finally {
            deleteRecursively(unpacked);
        }
    }

    @Test
    @Timeout(value = 300)
    void checksumsFileCoversEveryReleaseArtifactAndItsDigestMatches() throws Exception {
        Path releaseDir = releaseDir();
        Path sums = releaseDir.resolve("SHA256SUMS");
        assertTrue(Files.isRegularFile(sums), () -> "SHA256SUMS missing from " + releaseDir);

        Set<String> covered = new LinkedHashSet<>();
        for (String line : Files.readAllLines(sums, StandardCharsets.UTF_8)) {
            assertTrue(
                    line.matches("[0-9a-f]{64}  \\S+"),
                    () -> "SHA256SUMS lines must be '<sha256>  <file>': " + line);
            String digest = line.substring(0, 64);
            String name = line.substring(66);
            assertTrue(covered.add(name), () -> "SHA256SUMS lists a file twice: " + name);
            Path artifact = releaseDir.resolve(name);
            assertTrue(Files.isRegularFile(artifact), () -> "SHA256SUMS names a missing artifact: " + name);
            assertEquals(digest, sha256Of(artifact), () -> "digest mismatch for " + name);
        }

        try (Stream<Path> files = Files.list(releaseDir)) {
            Set<String> present =
                    new LinkedHashSet<>(
                            files.map(file -> file.getFileName().toString())
                                    .filter(name -> !name.equals("SHA256SUMS"))
                                    .toList());
            assertEquals(present, covered, "SHA256SUMS must cover exactly every release artifact beside it");
        }
        assertTrue(covered.stream().anyMatch(name -> name.endsWith(".tar.gz")), "the archive itself is covered");
        assertTrue(covered.stream().anyMatch(name -> name.endsWith(".sbom.json")), "the SBOM is covered");
    }

    @Test
    @Timeout(value = 300)
    void sbomListsExactlyTheProductionRuntimeDependencies() throws Exception {
        Path archive = releaseArchive();
        Path unpacked = Files.createTempDirectory("brave-search-release-sbom");
        try {
            runCommand("tar", "-xzf", archive.toString(), "-C", unpacked.toString());
            Path topDir = unpacked.resolve(archive.getFileName().toString().replaceAll("\\.tar\\.gz$", ""));
            Path sbom = topDir.resolve("sbom").resolve("bom.json");
            assertTrue(Files.isRegularFile(sbom), () -> "packaged SBOM missing: " + sbom);

            var root = JsonMapper.builder().build().readTree(sbom.toFile());
            assertFalse(root.has("serialNumber"), "the SBOM is deterministic: no random serial number");
            assertEquals(
                    "application",
                    root.path("metadata").path("component").path("type").asString(),
                    "the SBOM describes the shipped application");

            Set<String> components = new LinkedHashSet<>();
            root.path("components")
                    .forEach(component -> components.add(
                            component.path("group").asString() + ":" + component.path("name").asString()));
            assertTrue(
                    components.contains("info.picocli:picocli"),
                    "picocli is a production runtime dependency and must be listed: " + components);
            assertTrue(
                    components.contains("tools.jackson.core:jackson-databind"),
                    "jackson-databind is a production runtime dependency and must be listed: " + components);
            assertFalse(
                    components.contains("info.picocli:picocli-codegen"),
                    () -> "the annotation processor picocli-codegen shares picocli's group, so only its"
                            + " full coordinates keep it out of the runtime-scoped SBOM: " + components);
            for (String component : components) {
                String group = component.substring(0, component.indexOf(':'));
                assertFalse(
                        FORBIDDEN_SBOM_GROUPS.contains(group),
                        () -> "test-only dependency leaked into the runtime SBOM: " + component);
            }
        } finally {
            deleteRecursively(unpacked);
        }
    }

    @Test
    @Timeout(value = 300)
    void sbomRenderingsInsideAndBesideTheArchiveAreByteIdentical() throws Exception {
        Path archive = releaseArchive();
        Path unpacked = Files.createTempDirectory("brave-search-release-sbom-identity");
        try {
            runCommand("tar", "-xzf", archive.toString(), "-C", unpacked.toString());
            String stem = archive.getFileName().toString().replaceAll("\\.tar\\.gz$", "");
            Path inside = unpacked.resolve(stem).resolve("sbom").resolve("bom.json");
            Path beside = releaseDir().resolve(stem + ".sbom.json");
            assertTrue(Files.isRegularFile(inside), () -> "packaged SBOM missing: " + inside);
            assertTrue(Files.isRegularFile(beside), () -> "staged SBOM missing: " + beside);

            assertTrue(
                    Arrays.equals(Files.readAllBytes(inside), Files.readAllBytes(beside)),
                    "the SBOM inside the archive and the SBOM staged beside it must be byte-identical:"
                            + " both renderings come from one deterministic SBOM build, so any byte drift"
                            + " means the packaging mutated the document");
        } finally {
            deleteRecursively(unpacked);
        }
    }

    private static Path releaseArchive() {
        String archiveProperty = System.getProperty("brave.search.release.archive");
        assertNotNull(archiveProperty, "brave.search.release.archive must be injected by the build");
        Path archive = Path.of(archiveProperty);
        assertTrue(Files.isRegularFile(archive), () -> "release archive missing: " + archive);
        return archive;
    }

    /** The bare {@code <version>} segment of an archive stem, checked against the naming form. */
    private static String archiveVersion(String topDir) {
        java.util.regex.Matcher stem = ARCHIVE_STEM.matcher(topDir);
        assertTrue(stem.matches(), () -> "archive directory must follow the naming form: " + topDir);
        return stem.group(1);
    }

    private static Path releaseDir() {
        String dirProperty = System.getProperty("brave.search.release.dir");
        assertNotNull(dirProperty, "brave.search.release.dir must be injected by the build");
        return Path.of(dirProperty);
    }

    private static List<String> tarListing(Path archive) throws Exception {
        return runCommand("tar", "-tzf", archive.toString()).stdout().lines().toList();
    }

    private static String sha256Of(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private record CapturedProcess(int exitCode, String stdout, String stderr) {
        String describe() {
            return "exitCode=" + exitCode + ", stdout=<" + stdout + ">, stderr=<" + stderr + ">";
        }
    }

    private static CapturedProcess runCommand(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).start();
        try {
            ByteArrayOutputStream stdoutSink = new ByteArrayOutputStream();
            ByteArrayOutputStream stderrSink = new ByteArrayOutputStream();
            Thread stdoutDrainer = drain(process.getInputStream(), stdoutSink);
            Thread stderrDrainer = drain(process.getErrorStream(), stderrSink);
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), () -> "command timed out: " + Arrays.toString(command));
            stdoutDrainer.join(10_000);
            stderrDrainer.join(10_000);
            return new CapturedProcess(
                    process.exitValue(),
                    stdoutSink.toString(StandardCharsets.UTF_8),
                    stderrSink.toString(StandardCharsets.UTF_8));
        } finally {
            process.destroyForcibly();
        }
    }

    private static Thread drain(InputStream stream, ByteArrayOutputStream sink) {
        Thread drainer =
                new Thread(
                        () -> {
                            try (InputStream in = stream) {
                                in.transferTo(sink);
                            } catch (IOException e) {
                                // the child died or closed the pipe; assertions decide success
                            }
                        },
                        "release-verify-drain");
        drainer.setDaemon(true);
        drainer.start();
        return drainer;
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            List<Path> deletion = new ArrayList<>(paths.sorted(java.util.Comparator.reverseOrder()).toList());
            for (Path path : deletion) {
                Files.deleteIfExists(path);
            }
        }
    }
}

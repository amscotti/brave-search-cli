package io.amscotti.bravesearch.release;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Durability gate of the plain test task's independence: the JVM suite runs against the
 * installed launcher alone, so a focused test run never builds the native image, never
 * publishes to the local Maven repository, and never needs a release archive. The packaged
 * release-archive verification keeps its own test task, and {@code check} still runs it.
 */
final class TestTaskIndependenceTest {

    private static final Path BUILD_SCRIPT = Paths.get("build.gradle.kts");

    private static final String TEST_TASK = "tasks.named<Test>(\"test\")";

    private static final String RELEASE_TASK = "tasks.register<Test>(\"releaseArchiveTest\")";

    private static final String VERIFICATION_CLASS = "io.amscotti.bravesearch.release.ReleaseArchiveVerificationTest";

    /** What couples a task to the release chain: the checksum task, the archive, and its paths. */
    private static final List<String> RELEASE_COUPLING =
            List.of("sha256Sums", "releaseArchivePath", "releaseDirectory", "brave.search.release");

    private static final Path VERIFICATION_SOURCE =
            Paths.get("src", "test", "java", "io", "amscotti", "bravesearch", "release",
                    "ReleaseArchiveVerificationTest.java");

    @Test
    void plainTestTaskRunsWithoutTheReleaseChainAndLeavesOutTheReleaseVerification() throws IOException {
        String build = Files.readString(BUILD_SCRIPT, StandardCharsets.UTF_8);
        String configurations = everyConfigurationBlock(build, TEST_TASK);

        assertTrue(!configurations.isBlank(), "the build script configures the plain test task");
        for (String coupling : RELEASE_COUPLING) {
            assertFalse(
                    configurations.contains(coupling),
                    "the plain test task must not reference " + coupling
                            + ": a focused test run must not need the native image, a Maven-local publication,"
                            + " or a release archive");
        }
        assertTrue(
                configurations.contains("excludeTestsMatching(releaseArchiveVerification)"),
                "the plain test task must leave out the release-archive verification, the one class that needs the"
                        + " release artifacts");
    }

    @Test
    void releaseArchiveVerificationOwnsItsTaskAndCheckStillRunsIt() throws IOException {
        String build = Files.readString(BUILD_SCRIPT, StandardCharsets.UTF_8);

        String task = everyConfigurationBlock(build, RELEASE_TASK);
        assertTrue(!task.isBlank(), "the release-archive verification runs in its own test task");
        for (String coupling : RELEASE_COUPLING) {
            assertTrue(
                    task.contains(coupling),
                    "the release-archive task must reference " + coupling
                            + ": it consumes the packaged artifacts the checksum task produces");
        }
        assertTrue(
                task.contains("includeTestsMatching(releaseArchiveVerification)"),
                "the release-archive task runs the verification class and nothing else");
        assertTrue(
                build.contains("val releaseArchiveVerification = \"" + VERIFICATION_CLASS + "\""),
                "the build script names the verification class exactly once, for both filters");
        assertTrue(
                Files.isRegularFile(VERIFICATION_SOURCE),
                "the verification stays a member of the JVM test source set, where the contract index reaches it");
        String check = everyConfigurationBlock(build, "tasks.named(\"check\")");
        assertTrue(
                check.contains("releaseArchiveTest"), "the check task still runs the release-archive verification");
    }

    /**
     * Every brace-balanced configuration block the build script opens with {@code opener},
     * joined; a build script may configure one task in several places.
     */
    private static String everyConfigurationBlock(String build, String opener) {
        StringBuilder joined = new StringBuilder();
        int from = 0;
        while (true) {
            int start = build.indexOf(opener, from);
            if (start < 0) {
                return joined.toString();
            }
            int end = blockEnd(build, start);
            joined.append(build, start, end).append('\n');
            from = end;
        }
    }

    /** The index just past the brace that closes the block opening at {@code start}. */
    private static int blockEnd(String build, int start) {
        int depth = 0;
        for (int index = build.indexOf('{', start); index < build.length(); index++) {
            char current = build.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return index + 1;
            }
        }
        throw new IllegalStateException("the build script opens a block it never closes at index " + start);
    }
}

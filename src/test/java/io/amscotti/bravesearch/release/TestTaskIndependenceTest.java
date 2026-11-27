package io.amscotti.bravesearch.release;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Durability gate of the plain test task's independence: the JVM suite runs against the
 * installed launcher alone, so a focused test run never builds the native image, never
 * publishes to the local Maven repository, and never needs a release archive. The packaged
 * release-archive verification keeps its own test task, and {@code check} still runs it.
 */
final class TestTaskIndependenceTest {

    private static final Path BUILD_SCRIPT = Paths.get("build.gradle.kts");

    static final String TEST_TASK = "tasks.named<Test>(\"test\")";

    private static final List<Pattern> PLAIN_TEST_OPENERS = List.of(
            Pattern.compile("\\btasks\\s*\\.\\s*named\\s*<\\s*Test\\s*>\\s*\\(\\s*\"test\"\\s*\\)\\s*\\{"),
            Pattern.compile("\\btasks\\s*\\.\\s*named\\s*\\(\\s*\"test\"\\s*\\)\\s*\\{"),
            Pattern.compile("\\btasks\\s*\\.\\s*test\\s*\\{"),
            Pattern.compile("\\bnamed\\s*<\\s*JvmTestSuite\\s*>\\s*\\(\\s*\"test\"\\s*\\)\\s*\\{"),
            Pattern.compile("\\btasks\\s*\\.\\s*withType\\s*<\\s*Test\\s*>\\s*\\(\\s*\\)\\s*\\.\\s*configureEach\\s*\\{"),
            Pattern.compile("\\btasks\\s*\\.\\s*withType\\s*<\\s*Test\\s*>\\s*\\{"));

    private static final Pattern RELEASE_TASK_OPENER =
            Pattern.compile("\\btasks\\s*\\.\\s*register\\s*<\\s*Test\\s*>\\s*\\(\\s*\"releaseArchiveTest\"\\s*\\)\\s*\\{");

    private static final List<Pattern> CHECK_TASK_OPENERS = List.of(
            Pattern.compile("\\btasks\\s*\\.\\s*named\\s*\\(\\s*\"check\"\\s*\\)\\s*\\{"),
            Pattern.compile("\\btasks\\s*\\.\\s*check\\s*\\{"));

    private static final String VERIFICATION_CLASS = "io.amscotti.bravesearch.release.ReleaseArchiveVerificationTest";

    /** Release-chain tokens that must never reach the plain test task or shared Test configuration. */
    private static final List<String> FORBIDDEN_IN_PLAIN_TEST = List.of(
            "sha256Sums",
            "releaseArchivePath",
            "releaseDirectory",
            "releaseArchive",
            "releaseSbom",
            "nativeCompile",
            "publishToMavenLocal",
            "publishLibraryPublicationToMavenLocal",
            "brave.search.release");

    /** What couples the release-archive task to the release chain: the checksum task, archive, and paths. */
    private static final List<String> REQUIRED_IN_RELEASE_TASK =
            List.of("sha256Sums", "releaseArchivePath", "releaseDirectory", "brave.search.release");

    private static final Path VERIFICATION_SOURCE =
            Paths.get("src", "test", "java", "io", "amscotti", "bravesearch", "release",
                    "ReleaseArchiveVerificationTest.java");

    @Test
    void plainTestTaskRunsWithoutTheReleaseChainAndLeavesOutTheReleaseVerification() throws IOException {
        String build = stripComments(Files.readString(BUILD_SCRIPT, StandardCharsets.UTF_8));
        String configurations = everyConfigurationBlock(build, PLAIN_TEST_OPENERS);

        assertTrue(!configurations.isBlank(), "the build script configures the plain test task");
        for (String coupling : FORBIDDEN_IN_PLAIN_TEST) {
            assertFalse(
                    containsIdentifier(configurations, coupling),
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
        String build = stripComments(Files.readString(BUILD_SCRIPT, StandardCharsets.UTF_8));

        String task = everyConfigurationBlock(build, List.of(RELEASE_TASK_OPENER));
        assertTrue(!task.isBlank(), "the release-archive verification runs in its own test task");
        for (String coupling : REQUIRED_IN_RELEASE_TASK) {
            assertTrue(
                    containsIdentifier(task, coupling),
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
        String check = everyConfigurationBlock(build, CHECK_TASK_OPENERS);
        assertTrue(
                containsIdentifier(check, "releaseArchiveTest"),
                "the check task still runs the release-archive verification");
    }

    @Test
    void scannerHandlesBracesInsideCommentsAndStringsAndAlternateGradleDslForms() {
        String synthetic = """
                // comment with unmatched brace {
                tasks.test {
                  val note = "string with unmatched closing brace }"
                  /* block comment with { nested /* { */ } braces */
                  dependsOn("nativeCompile")
                }
                tasks.withType<Test>().configureEach {
                  val raw = \"\"\"triple string with } and {\"\"\"
                  dependsOn(tasks.named("sha256Sums"))
                }
                """;
        String stripped = stripComments(synthetic);
        String blocks = everyConfigurationBlock(stripped, PLAIN_TEST_OPENERS);

        assertTrue(containsIdentifier(blocks, "nativeCompile"), "alternate tasks.test DSL block must be inspected");
        assertTrue(containsIdentifier(blocks, "sha256Sums"), "shared tasks.withType<Test> block must be inspected");
        assertFalse(
                containsIdentifier("excludeTestsMatching(releaseArchiveVerification)", "releaseArchive"),
                "identifier matching must not false-match releaseArchiveVerification");
    }

    /**
     * Every brace-balanced configuration block the build script opens with {@code opener},
     * joined; comments are stripped and string literals are skipped so braces inside either
     * never skew matching.
     */
    static String everyConfigurationBlock(String build, String opener) {
        return everyConfigurationBlock(
                stripComments(build), List.of(Pattern.compile(Pattern.quote(opener) + "\\s*\\{")));
    }

    /**
     * Every brace-balanced configuration block opened by any pattern in {@code openers}, joined;
     * a build script may configure one task across multiple blocks and DSL spellings.
     */
    private static String everyConfigurationBlock(String build, List<Pattern> openers) {
        StringBuilder joined = new StringBuilder();
        for (Pattern opener : openers) {
            Matcher matcher = opener.matcher(build);
            int from = 0;
            while (matcher.find(from)) {
                int openBrace = matcher.end() - 1;
                int end = blockEnd(build, openBrace);
                joined.append(build, matcher.start(), end).append('\n');
                from = end;
            }
        }
        return joined.toString();
    }

    /**
     * Strips Kotlin line and nested block comments while leaving string and character literals
     * intact, so braces or task names inside comments never skew matching.
     */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int index = 0;
        int length = source.length();
        while (index < length) {
            char current = source.charAt(index);
            if (current == '/' && index + 1 < length && source.charAt(index + 1) == '/') {
                index += 2;
                while (index < length && source.charAt(index) != '\n') {
                    index++;
                }
            } else if (current == '/' && index + 1 < length && source.charAt(index + 1) == '*') {
                int depth = 1;
                index += 2;
                while (index < length && depth > 0) {
                    if (source.charAt(index) == '/' && index + 1 < length && source.charAt(index + 1) == '*') {
                        depth++;
                        index += 2;
                    } else if (source.charAt(index) == '*' && index + 1 < length && source.charAt(index + 1) == '/') {
                        depth--;
                        index += 2;
                    } else {
                        if (source.charAt(index) == '\n') {
                            out.append('\n');
                        }
                        index++;
                    }
                }
            } else if (current == '"' && index + 2 < length && source.charAt(index + 1) == '"'
                    && source.charAt(index + 2) == '"') {
                int end = source.indexOf("\"\"\"", index + 3);
                int stop = end < 0 ? length : end + 3;
                out.append(source, index, stop);
                index = stop;
            } else if (current == '"' || current == '\'') {
                int stop = quotedLiteralEnd(source, index, current);
                out.append(source, index, stop);
                index = stop;
            } else {
                out.append(current);
                index++;
            }
        }
        return out.toString();
    }

    /** The index just past the brace closing {@code openBrace}, skipping Kotlin string literals. */
    private static int blockEnd(String build, int openBrace) {
        int depth = 0;
        int index = openBrace;
        int length = build.length();
        while (index < length) {
            char current = build.charAt(index);
            if (current == '"' && index + 2 < length && build.charAt(index + 1) == '"'
                    && build.charAt(index + 2) == '"') {
                int end = build.indexOf("\"\"\"", index + 3);
                index = end < 0 ? length : end + 3;
            } else if (current == '"' || current == '\'') {
                index = quotedLiteralEnd(build, index, current);
            } else if (current == '{') {
                depth++;
                index++;
            } else if (current == '}') {
                index++;
                if (--depth == 0) {
                    return index;
                }
            } else {
                index++;
            }
        }
        throw new IllegalStateException("the build script opens a block it never closes at index " + openBrace);
    }

    /** The index just past a single- or double-quoted Kotlin literal starting at {@code start}. */
    private static int quotedLiteralEnd(String source, int start, char quote) {
        int index = start + 1;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (current == '\\') {
                index += 2;
            } else if (current == quote) {
                return index + 1;
            } else {
                index++;
            }
        }
        return source.length();
    }

    /** Matches {@code token} at identifier boundaries (supporting dotted property keys as well). */
    private static boolean containsIdentifier(String text, String token) {
        return Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(token) + "(?![A-Za-z0-9_])")
                .matcher(text)
                .find();
    }
}

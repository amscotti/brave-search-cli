package io.amscotti.bravesearch.hygiene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Behavior tests for the repository phase-hygiene scanner.
 *
 * <p>Violating sample text is assembled at runtime from split literals so this file stays clean
 * under the very gate it verifies.
 */
final class HygieneScannerTest {

    private static final String TASK_NUMBER_REFERENCE = "Task" + " 41";
    private static final String DOD_NUMBER_REFERENCE = "DoD" + "-7";
    private static final String PHASE_NUMBER_REFERENCE = "Phase" + " 2";
    private static final String STAGE_NUMBER_REFERENCE = "Stage" + "-3";
    private static final String STEP_NUMBER_REFERENCE = "Step" + "_4";
    private static final String LOWERCASE_PHASE_NUMBER_REFERENCE = "phase" + " 12";
    private static final String PLAN_STAGE_PHRASE = "per the" + " implementation " + "plan";
    private static final String PLAN_STAGE_PHRASE_SHORT = "per the" + " plan";
    private static final String PLAN_FILE_REFERENCE = "PLA" + "N.md";

    @TempDir
    Path root;

    @Test
    void rejectsTaskNumberReferences() throws IOException {
        Path file =
                Files.writeString(
                        root.resolve("notes.md"),
                        "context line\nsee " + TASK_NUMBER_REFERENCE + " for scope\n");

        List<HygieneScanner.Violation> violations = scan();

        assertEquals(1, violations.size());
        assertEquals(file.getFileName(), violations.get(0).file());
        assertEquals(2, violations.get(0).lineNumber());
        assertEquals(TASK_NUMBER_REFERENCE, violations.get(0).matchedText());
        assertEquals("see " + TASK_NUMBER_REFERENCE + " for scope", violations.get(0).lineText());
    }

    @Test
    void rejectsDodNumberReferences() throws IOException {
        Files.writeString(root.resolve("dodgy.md"), "scope in " + DOD_NUMBER_REFERENCE + "\n");

        List<HygieneScanner.Violation> violations = scan();

        assertEquals(1, violations.size());
        assertEquals(DOD_NUMBER_REFERENCE, violations.get(0).matchedText());
        assertEquals(1, violations.get(0).lineNumber());
    }

    @Test
    void rejectsNumberedPhaseStageAndStepReferences() throws IOException {
        Files.writeString(
                root.resolve("numbered-words.md"),
                PHASE_NUMBER_REFERENCE
                        + "\n"
                        + STAGE_NUMBER_REFERENCE
                        + "\n"
                        + STEP_NUMBER_REFERENCE
                        + "\n"
                        + LOWERCASE_PHASE_NUMBER_REFERENCE
                        + "\n");

        List<HygieneScanner.Violation> violations = scan();

        assertEquals(
                List.of(
                        PHASE_NUMBER_REFERENCE,
                        STAGE_NUMBER_REFERENCE,
                        STEP_NUMBER_REFERENCE,
                        LOWERCASE_PHASE_NUMBER_REFERENCE),
                violations.stream().map(HygieneScanner.Violation::matchedText).toList());
        assertEquals(List.of(1, 2, 3, 4), violations.stream().map(HygieneScanner.Violation::lineNumber).toList());
    }

    @Test
    void rejectsPlanStagePhrases() throws IOException {
        Files.writeString(
                root.resolve("readme.md"),
                "intro\n" + PLAN_STAGE_PHRASE + "\nmiddle\n" + PLAN_STAGE_PHRASE_SHORT + "\n");

        List<HygieneScanner.Violation> violations = scan();

        assertEquals(2, violations.size());
        assertEquals(2, violations.get(0).lineNumber());
        assertEquals(PLAN_STAGE_PHRASE, violations.get(0).matchedText());
        assertEquals(4, violations.get(1).lineNumber());
        assertEquals(PLAN_STAGE_PHRASE_SHORT, violations.get(1).matchedText());
    }

    @Test
    void planPhraseContinuationsSuchAsPlanningStayAllowed() throws IOException {
        Files.writeString(
                root.resolve("clean-plan-phrase.md"),
                "per the planning conventions of this domain\n"
                        + "per the implementation planning notes\n");

        assertEquals(List.of(), scan());
    }

    @Test
    void rejectsPlanFileReferences() throws IOException {
        Files.writeString(
                root.resolve("readme.md"), "details in " + PLAN_FILE_REFERENCE + " section\n");

        List<HygieneScanner.Violation> violations = scan();

        assertEquals(1, violations.size());
        assertEquals(PLAN_FILE_REFERENCE, violations.get(0).matchedText());
    }

    @Test
    void matchingIsCaseInsensitive() throws IOException {
        String upperTask = "TASK" + " 5";
        String lowerTask = "task" + "-9";
        String lowerDod = "dod" + " 3";
        String lowerPlanFile = "plan" + ".md";
        Files.writeString(
                root.resolve("mixed.md"),
                upperTask + "\n" + lowerTask + "\n" + lowerDod + "\n" + lowerPlanFile + "\n");

        List<HygieneScanner.Violation> violations = scan();

        assertEquals(
                List.of(upperTask, lowerTask, lowerDod, lowerPlanFile),
                violations.stream().map(HygieneScanner.Violation::matchedText).toList());
        assertEquals(List.of(1, 2, 3, 4), violations.stream().map(HygieneScanner.Violation::lineNumber).toList());
    }

    @Test
    void reportsEveryOccurrenceOnTheSameLine() throws IOException {
        String first = "Task" + " 1";
        String second = "Task" + " 2";
        Files.writeString(root.resolve("twice.md"), "see " + first + " and " + second + " later\n");

        List<HygieneScanner.Violation> violations = scan();

        assertEquals(
                List.of(first, second),
                violations.stream().map(HygieneScanner.Violation::matchedText).toList());
        assertEquals(List.of(1, 1), violations.stream().map(HygieneScanner.Violation::lineNumber).toList());
    }

    @Test
    void compoundWordsCarryingNumbersPass() throws IOException {
        Files.writeString(
                root.resolve("compound.md"),
                "multitask 4 things\n"
                        + "multi-step 3 items\n"
                        + "multiphase 2 designs\n"
                        + "multistage 1 run\n"
                        + "my_step 5 checklist\n");

        assertEquals(List.of(), scan());
    }

    @Test
    void oversizeDoesNotCountFilesThatWouldNeverBeScanned() throws IOException {
        Files.write(root.resolve("logo.png"), new byte[HygieneScanner.MAX_SCANNABLE_BYTES + 1]);
        Files.writeString(root.resolve(".env"), "s".repeat(HygieneScanner.MAX_SCANNABLE_BYTES + 1));
        Files.writeString(root.resolve("clean.md"), "clean content\n");

        HygieneScanner.ScanReport report = new HygieneScanner().scan(root);

        assertEquals(List.of(), report.violations());
        assertEquals(0, report.skippedOversizedFiles());
    }

    @Test
    void scanFailsClearlyWhenAFileCannotBeRead() throws IOException {
        Path lockedFile = Files.writeString(root.resolve("locked.md"), TASK_NUMBER_REFERENCE + "\n");
        Files.setPosixFilePermissions(lockedFile, PosixFilePermissions.fromString("-w-------"));
        assumeTrue(
                !Files.isReadable(lockedFile),
                "file permissions cannot block this process; nothing to verify");
        try {
            IllegalStateException failure =
                    assertThrows(IllegalStateException.class, () -> new HygieneScanner().scan(root));

            assertTrue(failure.getMessage().contains("locked.md"), failure.getMessage());
            assertTrue(failure.getMessage().contains("AccessDeniedException"), failure.getMessage());
            assertNotNull(failure.getCause(), "the original read failure must be preserved as the cause");
        } finally {
            Files.setPosixFilePermissions(lockedFile, PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void brokenSymlinksAreSkippedSilently() throws IOException {
        assumeTrue(
                !System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("win"),
                "symbolic links are not reliable here");
        Files.createSymbolicLink(root.resolve("dangling.md"), root.resolve("nowhere.txt"));
        Files.writeString(root.resolve("clean.md"), "clean content\n");

        HygieneScanner.ScanReport report = new HygieneScanner().scan(root);

        assertEquals(List.of(), report.violations());
        assertEquals(0, report.skippedOversizedFiles());
    }

    @Test
    void scansContentThroughSymlinksToRegularFiles() throws IOException {
        assumeTrue(
                !System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("win"),
                "symbolic links are not reliable here");
        writeContaining(root.resolve("target.md"));
        Files.createSymbolicLink(root.resolve("linked.md"), root.resolve("target.md"));

        List<HygieneScanner.Violation> violations = scan();

        assertEquals(
                List.of(Path.of("linked.md"), Path.of("target.md")),
                violations.stream().map(HygieneScanner.Violation::file).toList());
    }

    @Test
    void countsSymlinksToOversizedFilesAsSkippedWithoutOpeningThem() throws IOException {
        assumeTrue(
                !System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("win"),
                "symbolic links are not reliable here");
        Files.createDirectories(root.resolve("build"));
        Files.writeString(
                root.resolve("build/huge.md"),
                TASK_NUMBER_REFERENCE + "\n" + "a".repeat(HygieneScanner.MAX_SCANNABLE_BYTES) + "\n");
        Files.createSymbolicLink(root.resolve("linked.md"), root.resolve("build/huge.md"));

        HygieneScanner.ScanReport report = new HygieneScanner().scan(root);

        assertEquals(List.of(), report.violations());
        assertEquals(1, report.skippedOversizedFiles());
    }

    @Test
    void skipsSymlinksToDirectoriesInsteadOfFailingTheScan() throws IOException {
        assumeTrue(
                !System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("win"),
                "symbolic links are not reliable here");
        writeContaining(root.resolve("assets/inner.md"));
        Files.createSymbolicLink(root.resolve("linked.md"), root.resolve("assets"));

        List<HygieneScanner.Violation> violations = scan();

        assertEquals(
                List.of(Path.of("assets", "inner.md")),
                violations.stream().map(HygieneScanner.Violation::file).toList());
    }

    @Test
    void cleanDomainTextPasses() throws IOException {
        Files.writeString(
                root.resolve("clean.md"),
                "Ordinary domain words like phase, stage, step, multitask, and planning ahead stay allowed.\n"
                        + "So does the word plan standing on its own.\n");

        assertEquals(List.of(), scan());
    }

    @Test
    void skipsFilesLargerThanFourMebibytesAndCountsThem() throws IOException {
        Files.writeString(root.resolve("huge.md"), TASK_NUMBER_REFERENCE + "\n" + "a".repeat(4 * 1024 * 1024) + "\n");
        Files.writeString(root.resolve("huge-too.md"), TASK_NUMBER_REFERENCE + "\n" + "b".repeat(4 * 1024 * 1024) + "\n");
        Files.writeString(root.resolve("small.md"), "clean content\n");

        HygieneScanner.ScanReport report = new HygieneScanner().scan(root);

        assertEquals(List.of(), report.violations());
        assertEquals(2, report.skippedOversizedFiles());
    }

    @Test
    void stillScansFilesOfExactlyFourMebibytes() throws IOException {
        String head = TASK_NUMBER_REFERENCE + "\n";
        Files.writeString(root.resolve("limit.md"), head + "a".repeat(4 * 1024 * 1024 - head.length()));

        HygieneScanner.ScanReport report = new HygieneScanner().scan(root);

        assertEquals(1, report.violations().size());
        assertEquals(0, report.skippedOversizedFiles());
    }

    @Test
    void preVisitDirectoryToleratesARootWithoutAFileName() {
        HygieneScanner.ScanVisitor visitor = new HygieneScanner.ScanVisitor(Path.of("/"));

        assertEquals(FileVisitResult.CONTINUE, visitor.preVisitDirectory(Path.of("/"), null));
        assertEquals(FileVisitResult.SKIP_SUBTREE, visitor.preVisitDirectory(Path.of("/build"), null));
    }

    @Test
    void visitFileFailedFailsNamingThePathAndReason() {
        HygieneScanner.ScanVisitor visitor = new HygieneScanner.ScanVisitor(root);

        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> visitor.visitFileFailed(root.resolve("locked.md"), new IOException("permission denied")));

        assertTrue(failure.getMessage().contains("locked.md"), failure.getMessage());
        assertTrue(failure.getMessage().contains("permission denied"), failure.getMessage());
    }

    @Test
    void visitFileFailedNeverRendersANullReason() {
        HygieneScanner.ScanVisitor visitor = new HygieneScanner.ScanVisitor(root);

        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> visitor.visitFileFailed(
                                root.resolve("locked.md"),
                                new FileSystemException(root.resolve("locked.md").toString())));

        assertTrue(failure.getMessage().contains("locked.md"), failure.getMessage());
        assertFalse(failure.getMessage().contains("null"), failure.getMessage());
    }

    @Test
    void scanFailsClearlyWhenAnEntryCannotBeVisited() throws IOException {
        Path lockedDirectory = Files.createDirectories(root.resolve("locked"));
        Files.writeString(lockedDirectory.resolve("notes.md"), TASK_NUMBER_REFERENCE + "\n");
        Files.setPosixFilePermissions(lockedDirectory, PosixFilePermissions.fromString("---------"));
        assumeTrue(
                !Files.isReadable(lockedDirectory),
                "file permissions cannot block this process; nothing to verify");
        try {
            IllegalStateException failure =
                    assertThrows(IllegalStateException.class, () -> new HygieneScanner().scan(root));

            assertTrue(failure.getMessage().contains("locked"), failure.getMessage());
            assertNotNull(failure.getCause(), "the original visit failure must be preserved as the cause");
        } finally {
            Files.setPosixFilePermissions(lockedDirectory, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void skipsExcludedFilesAndDirectories() throws IOException {
        writeContaining(root.resolve(".env"));
        writeContaining(root.resolve(".env.local"));
        writeContaining(root.resolve("build/generated.md"));
        writeContaining(root.resolve("out/ide-output.md"));
        writeContaining(root.resolve(".gradle/cache.md"));
        writeContaining(root.resolve(".git/hooks/description.md"));
        writeContaining(root.resolve(".idea/misc.md"));
        writeContaining(root.resolve("mise.lock"));
        writeContaining(root.resolve("gradle.lockfile"));
        writeContaining(root.resolve("settings-gradle.lockfile"));
        writeContaining(root.resolve("libs/dependency.jar"));
        writeContaining(root.resolve("assets/logo.png"));
        writeContaining(root.resolve("gradle/verification-metadata.xml"));

        assertEquals(List.of(), scan());
    }

    @Test
    void reportsViolationsInARootPlanningDocument() throws IOException {
        writeContaining(root.resolve(PLAN_FILE_REFERENCE));

        List<HygieneScanner.Violation> violations = scan();

        assertEquals(1, violations.size());
        assertEquals(Path.of(PLAN_FILE_REFERENCE), violations.get(0).file());
    }

    @Test
    void skipsTheReviewHarnessScratchDirectoryThatQuotesItsOwnFailureReports() throws IOException {
        writeContaining(root.resolve(".review/state.json"));
        writeContaining(root.resolve(".review/findings/component.txt"));

        assertEquals(List.of(), scan());
    }

    @Test
    void skipsBinaryContentEvenWithoutAKnownExtension() throws IOException {
        Files.write(
                root.resolve("notes.txt"),
                ("binary-looking\0preamble\n" + TASK_NUMBER_REFERENCE + "\n")
                        .getBytes(StandardCharsets.UTF_8));

        assertEquals(List.of(), scan());
    }

    @Test
    void stillScansDotEnvExampleTemplates() throws IOException {
        writeContaining(root.resolve(".env.example"));

        List<HygieneScanner.Violation> violations = scan();

        assertEquals(1, violations.size());
        assertEquals(Path.of(".env.example"), violations.get(0).file());
    }

    @Test
    void scansEvenWhenTheScannedRootItselfIsNamedLikeAnExcludedDirectory() throws IOException {
        Path buildRoot = Files.createDirectories(root.resolve("build"));
        writeContaining(buildRoot.resolve("notes.md"));
        writeContaining(buildRoot.resolve("out/hidden.md"));

        List<HygieneScanner.Violation> violations = new HygieneScanner().scan(buildRoot).violations();

        assertEquals(
                List.of(Path.of("notes.md")),
                violations.stream().map(HygieneScanner.Violation::file).toList());
    }

    @Test
    void detectsViolationsInFilesThatOnlyLookExcluded() throws IOException {
        writeContaining(root.resolve("docs/readme.md"));
        writeContaining(root.resolve("sub/" + PLAN_FILE_REFERENCE));
        writeContaining(root.resolve("nested/gradle/verification-metadata.xml"));

        List<HygieneScanner.Violation> violations = scan();

        assertEquals(
                List.of(
                        Path.of("docs", "readme.md"),
                        Path.of("nested", "gradle", "verification-metadata.xml"),
                        Path.of("sub", PLAN_FILE_REFERENCE)),
                violations.stream().map(HygieneScanner.Violation::file).toList());
    }

    @Test
    void reportsCorrectLineNumberWithinTheFile() throws IOException {
        Files.writeString(
                root.resolve("numbered.md"),
                "alpha\nbeta\ngamma\n" + PLAN_STAGE_PHRASE + "\nepsilon\n");

        List<HygieneScanner.Violation> violations = scan();

        assertEquals(1, violations.size());
        assertEquals(4, violations.get(0).lineNumber());
        assertEquals(PLAN_STAGE_PHRASE, violations.get(0).matchedText());
    }

    private List<HygieneScanner.Violation> scan() throws IOException {
        return new HygieneScanner().scan(root).violations();
    }

    private void writeContaining(Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, TASK_NUMBER_REFERENCE + "\n");
    }
}

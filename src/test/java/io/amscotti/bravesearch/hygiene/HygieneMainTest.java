package io.amscotti.bravesearch.hygiene;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Rendering of the scan report the Gradle gate prints or fails with. */
final class HygieneMainTest {

    private static final String TASK_NUMBER_REFERENCE = "Task" + " 41";

    @Test
    void reportListsViolationsThenTheOversizedSummaryLine() {
        HygieneScanner.Violation violation =
                new HygieneScanner.Violation(
                        Path.of("docs", "notes.md"), 3, TASK_NUMBER_REFERENCE, "see " + TASK_NUMBER_REFERENCE + " for scope");

        String report = HygieneMain.reportText(new HygieneScanner.ScanReport(List.of(violation), 2));

        assertEquals(
                "1 phase-hygiene violation(s) in durable artifacts:"
                        + "\n  docs/notes.md:3: matches \""
                        + TASK_NUMBER_REFERENCE
                        + "\": see "
                        + TASK_NUMBER_REFERENCE
                        + " for scope"
                        + "\nskipped 2 oversized files",
                report);
    }

    @Test
    void reportIsOnlyTheOversizedSummaryLineWhenNothingElseWasFound() {
        assertEquals("\nskipped 3 oversized files", HygieneMain.reportText(new HygieneScanner.ScanReport(List.of(), 3)));
    }

    @Test
    void reportIsEmptyForACleanScanWithNothingSkipped() {
        assertEquals("", HygieneMain.reportText(new HygieneScanner.ScanReport(List.of(), 0)));
    }
}

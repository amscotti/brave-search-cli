package io.amscotti.bravesearch.hygiene;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Command-line entry point for the repository phase-hygiene scan.
 *
 * <p>Takes the repository root as the first argument, scans it with {@link HygieneScanner}, and
 * throws with one report line per violation so the owning Gradle task fails with the full report.
 * Oversized files that were skipped appear as one summary line of the report. A clean repository
 * with nothing skipped returns normally.
 */
public final class HygieneMain {

    private HygieneMain() {}

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        HygieneScanner.ScanReport report = new HygieneScanner().scan(root);
        String reportText = reportText(report);
        if (!report.violations().isEmpty()) {
            throw new IllegalStateException(reportText);
        }
        if (report.skippedOversizedFiles() > 0) {
            System.out.println(reportText.strip());
        }
    }

    /** Renders the full report: one line per violation, then one summary line for skipped files. */
    static String reportText(HygieneScanner.ScanReport report) {
        StringBuilder text = new StringBuilder();
        if (!report.violations().isEmpty()) {
            text.append(report.violations().size()).append(" phase-hygiene violation(s) in durable artifacts:");
            for (HygieneScanner.Violation violation : report.violations()) {
                text.append("\n  ")
                        .append(violation.file())
                        .append(':')
                        .append(violation.lineNumber())
                        .append(": matches \"")
                        .append(violation.matchedText())
                        .append("\": ")
                        .append(violation.lineText().strip());
            }
        }
        if (report.skippedOversizedFiles() > 0) {
            text.append("\nskipped ").append(report.skippedOversizedFiles()).append(" oversized files");
        }
        return text.toString();
    }
}

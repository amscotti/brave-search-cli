package io.amscotti.bravesearch.adapter.cli.command.web;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.testsupport.AnsiScenarios;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Whole-process ANSI-enablement evidence for the web command's human listing against the
 * JVM executable produced by {@code installDist}: the positive proof under a real
 * pseudo-terminal, and the conservative negatives — {@code NO_COLOR} under the terminal, a
 * colorful TERM behind a plain pipe, and a console-attached process whose stdout alone
 * leaves the terminal. The pseudo-terminal scenarios need a python 3 on the path; without
 * one they skip loudly, because no honest smaller substitute proves a real terminal.
 */
final class AnsiEnablementProcessTest {

    private static String python;

    @BeforeAll
    static void python3MustBeAvailableForTheTerminalScenarios() {
        python = io.amscotti.bravesearch.testsupport.PtyHarness.python3OnPath();
        Assumptions.assumeTrue(
                python != null,
                "SKIPPED: no python 3 on the path, so no pseudo-terminal can be allocated for the"
                        + " positive ANSI-enablement proof");
    }

    @Test
    @Timeout(120)
    void ptyRunEmitsTheBoldHeading() throws Exception {
        AnsiScenarios.ptyRunEmitsTheBoldHeading(webCommand(), python);
    }

    @Test
    @Timeout(120)
    void ptyRunStylesTheTitleBoldAndTheUrlDim() throws Exception {
        AnsiScenarios.ptyRunStylesTheTitleBoldAndTheUrlDim(webCommand(), python);
    }

    @Test
    @Timeout(120)
    void pipedRunMatchesThePtyLayoutWithZeroAnsiBytes() throws Exception {
        AnsiScenarios.pipedRunMatchesThePtyLayoutWithZeroAnsiBytes(webCommand(), python);
    }

    @Test
    @Timeout(120)
    void ptyRunWithNoColorStaysPlain() throws Exception {
        AnsiScenarios.ptyRunWithNoColorStaysPlain(webCommand(), python);
    }

    @Test
    @Timeout(120)
    void pipedRunWithColorfulTermStaysPlain() throws Exception {
        AnsiScenarios.pipedRunWithColorfulTermStaysPlain(webCommand());
    }

    @Test
    @Timeout(120)
    void pipedRunsHonorAnExportedSaneColumnsValue() throws Exception {
        AnsiScenarios.pipedRunsHonorAnExportedSaneColumnsValue(webCommand());
    }

    @Test
    @Timeout(120)
    void ptyRunStdoutThroughPipeStaysPlain() throws Exception {
        AnsiScenarios.ptyRunStdoutThroughPipeStaysPlain(webCommand(), python);
    }

    private static List<String> webCommand() {
        String launcherProperty = System.getProperty("brave.search.jvm.launcher");
        assertNotNull(launcherProperty, "brave.search.jvm.launcher must be injected by the build");
        Path launcher = Path.of(launcherProperty);
        assertTrue(Files.isRegularFile(launcher), () -> "installed launcher missing: " + launcher);
        return List.of(launcher.toString(), WebSearchCommand.NAME);
    }
}

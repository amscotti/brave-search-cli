package io.amscotti.bravesearch.adapter.cli.command.web;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.testsupport.AnsiScenarios;
import io.amscotti.bravesearch.testsupport.PtyHarness;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The same ANSI-enablement evidence as the JVM process suite, run against the exact native
 * executable produced by {@code nativeCompile}: the positive bold-heading proof under a
 * real pseudo-terminal and the conservative negatives must agree with the JVM's behavior,
 * because the color decision is a process contract, not a runtime accident. The scenarios
 * that allocate a pseudo-terminal skip loudly without a python 3 on the path; the piped
 * negatives need no terminal, so they run on every host that has the executable.
 */
final class AnsiEnablementNativeSmokeTest {

    private static String python;

    @BeforeAll
    static void locatePython3ForTheTerminalScenarios() {
        python = PtyHarness.python3OnPath();
    }

    /** The skip guard of every scenario that allocates a pseudo-terminal through python. */
    private static void assumePython3ForTheTerminalScenarios() {
        Assumptions.assumeTrue(
                python != null,
                "SKIPPED: no python 3 on the path, so no pseudo-terminal can be allocated for the"
                        + " positive native ANSI-enablement proof");
    }

    @Test
    @Timeout(180)
    void ptyRunEmitsTheBoldHeading() throws Exception {
        assumePython3ForTheTerminalScenarios();
        AnsiScenarios.ptyRunEmitsTheBoldHeading(webCommand(), python);
    }

    @Test
    @Timeout(180)
    void ptyRunStylesTheTitleBoldAndTheUrlDim() throws Exception {
        assumePython3ForTheTerminalScenarios();
        AnsiScenarios.ptyRunStylesTheTitleBoldAndTheUrlDim(webCommand(), python);
    }

    @Test
    @Timeout(180)
    void pipedRunMatchesThePtyLayoutWithZeroAnsiBytes() throws Exception {
        assumePython3ForTheTerminalScenarios();
        AnsiScenarios.pipedRunMatchesThePtyLayoutWithZeroAnsiBytes(webCommand(), python);
    }

    @Test
    @Timeout(180)
    void ptyRunWithNoColorStaysPlain() throws Exception {
        assumePython3ForTheTerminalScenarios();
        AnsiScenarios.ptyRunWithNoColorStaysPlain(webCommand(), python);
    }

    @Test
    @Timeout(180)
    void pipedRunWithColorfulTermStaysPlain() throws Exception {
        AnsiScenarios.pipedRunWithColorfulTermStaysPlain(webCommand());
    }

    @Test
    @Timeout(180)
    void pipedRunsHonorAnExportedSaneColumnsValue() throws Exception {
        AnsiScenarios.pipedRunsHonorAnExportedSaneColumnsValue(webCommand());
    }

    @Test
    @Timeout(180)
    void ptyRunStdoutThroughPipeStaysPlain() throws Exception {
        assumePython3ForTheTerminalScenarios();
        AnsiScenarios.ptyRunStdoutThroughPipeStaysPlain(webCommand(), python);
    }

    private static List<String> webCommand() {
        String binaryProperty = System.getProperty("brave.search.native.binary");
        assertNotNull(binaryProperty, "brave.search.native.binary must be injected by the build");
        Path binary = Path.of(binaryProperty);
        assertTrue(Files.isRegularFile(binary), () -> "native binary missing: " + binary);
        assertTrue(Files.isExecutable(binary), () -> "native binary not executable: " + binary);
        return List.of(binary.toString(), WebSearchCommand.NAME);
    }
}

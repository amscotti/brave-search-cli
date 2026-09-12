package io.amscotti.bravesearch.adapter.cli.command;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.testsupport.StreamProbeScenarios;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Whole-process streaming lifecycle evidence for the JVM executable produced by {@code
 * installDist}: the launcher script, the real signal delivery, and the real stdout pipe, all
 * against a scripted loopback server.
 */
final class StreamProbeProcessTest {

    @Test
    @Timeout(120)
    void sigintExits130Naturally() throws Exception {
        StreamProbeScenarios.sigintExits130Naturally(probeCommand());
    }

    @Test
    @Timeout(120)
    void epipeExitsZeroSilently() throws Exception {
        StreamProbeScenarios.epipeExitsZeroSilently(probeCommand());
    }

    @Test
    @Timeout(120)
    void normalCompletionExitsZero() throws Exception {
        StreamProbeScenarios.normalCompletionExitsZero(probeCommand());
    }

    @Test
    @Timeout(120)
    void idleTimeoutExits6() throws Exception {
        StreamProbeScenarios.idleTimeoutExits6(probeCommand());
    }

    @Test
    @Timeout(120)
    void abruptCloseExits6WithOneDiagnostic() throws Exception {
        StreamProbeScenarios.abruptCloseExits6WithOneDiagnostic(probeCommand());
    }

    @Test
    @Timeout(120)
    void silentHeadersEndWithinTheWallBudgetAs6() throws Exception {
        StreamProbeScenarios.silentHeadersEndWithinTheWallBudgetAs6(probeCommand());
    }

    @Test
    @Timeout(120)
    void zeroOrNegativeDurationIsAUsageErrorBeforeAnyRequest() throws Exception {
        StreamProbeScenarios.zeroOrNegativeDurationIsAUsageErrorBeforeAnyRequest(probeCommand());
    }

    @Test
    @Timeout(120)
    void nonLoopbackUrlRejected() throws Exception {
        StreamProbeScenarios.nonLoopbackUrlRejected(probeCommand());
    }

    private static List<String> probeCommand() {
        String launcherProperty = System.getProperty("brave.search.jvm.launcher");
        assertNotNull(launcherProperty, "brave.search.jvm.launcher must be injected by the build");
        Path launcher = Path.of(launcherProperty);
        assertTrue(Files.isRegularFile(launcher), () -> "installed launcher missing: " + launcher);
        assertTrue(Files.isExecutable(launcher), () -> "installed launcher not executable: " + launcher);
        return List.of(launcher.toString(), StreamProbeCommand.NAME);
    }
}

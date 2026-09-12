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
 * The same whole-process streaming lifecycle evidence as the JVM process suite, run against the
 * exact native executable produced by {@code nativeCompile}: interrupt and broken-pipe behavior
 * must agree with the JVM's — 130 and a silent 0 respectively, and never a 141 SIGPIPE death.
 */
final class StreamProbeNativeSmokeTest {

    @Test
    @Timeout(180)
    void sigintExits130Naturally() throws Exception {
        StreamProbeScenarios.sigintExits130Naturally(probeCommand());
    }

    @Test
    @Timeout(180)
    void epipeExitsZeroSilently() throws Exception {
        StreamProbeScenarios.epipeExitsZeroSilently(probeCommand());
    }

    @Test
    @Timeout(180)
    void normalCompletionExitsZero() throws Exception {
        StreamProbeScenarios.normalCompletionExitsZero(probeCommand());
    }

    @Test
    @Timeout(180)
    void idleTimeoutExits6() throws Exception {
        StreamProbeScenarios.idleTimeoutExits6(probeCommand());
    }

    @Test
    @Timeout(180)
    void abruptCloseExits6WithOneDiagnostic() throws Exception {
        StreamProbeScenarios.abruptCloseExits6WithOneDiagnostic(probeCommand());
    }

    @Test
    @Timeout(180)
    void silentHeadersEndWithinTheWallBudgetAs6() throws Exception {
        StreamProbeScenarios.silentHeadersEndWithinTheWallBudgetAs6(probeCommand());
    }

    @Test
    @Timeout(180)
    void zeroOrNegativeDurationIsAUsageErrorBeforeAnyRequest() throws Exception {
        StreamProbeScenarios.zeroOrNegativeDurationIsAUsageErrorBeforeAnyRequest(probeCommand());
    }

    @Test
    @Timeout(180)
    void nonLoopbackUrlRejected() throws Exception {
        StreamProbeScenarios.nonLoopbackUrlRejected(probeCommand());
    }

    private static List<String> probeCommand() {
        String binaryProperty = System.getProperty("brave.search.native.binary");
        assertNotNull(binaryProperty, "brave.search.native.binary must be injected by the build");
        Path binary = Path.of(binaryProperty);
        assertTrue(Files.isRegularFile(binary), () -> "native binary missing: " + binary);
        assertTrue(Files.isExecutable(binary), () -> "native binary not executable: " + binary);
        return List.of(binary.toString(), StreamProbeCommand.NAME);
    }
}

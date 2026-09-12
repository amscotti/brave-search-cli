package io.amscotti.bravesearch.adapter.cli.command.rich;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.testsupport.RichScenarios;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Whole-process evidence for the rich command against the JVM executable produced by
 * {@code installDist}: the launcher script, the real credential routing, the real stdout
 * pipe, and the real exit statuses, all against a scripted loopback server with an
 * isolated environment.
 */
final class RichProcessTest {

    @Test
    @Timeout(120)
    void humanHappyPathRendersPerVerticalSectionsWithoutAnsi() throws Exception {
        RichScenarios.humanHappyPathRendersPerVerticalSectionsWithoutAnsi(richCommand());
    }

    @Test
    @Timeout(120)
    void jsonEnvelopeIsOneValidatedLfLine() throws Exception {
        RichScenarios.jsonEnvelopeIsOneValidatedLfLine(richCommand());
    }

    @Test
    @Timeout(120)
    void providerAttributionSurvivesHumanRenderingAndMachineLosslessness() throws Exception {
        RichScenarios.providerAttributionSurvivesHumanRenderingAndMachineLosslessness(richCommand());
    }

    @Test
    @Timeout(120)
    void jsonlEmitsOneValidatedRecordPerVerticalThenSummary() throws Exception {
        RichScenarios.jsonlEmitsOneValidatedRecordPerVerticalThenSummary(richCommand());
    }

    @Test
    @Timeout(120)
    void rawOutputIsTheServedBodyBytesExactly() throws Exception {
        RichScenarios.rawOutputIsTheServedBodyBytesExactly(richCommand());
    }

    @Test
    @Timeout(120)
    void authenticationFailureExitsFourInHumanAndJsonModes() throws Exception {
        RichScenarios.authenticationFailureExitsFourInHumanAndJsonModes(richCommand());
    }

    @Test
    @Timeout(120)
    void usageFailureHappensBeforeAnyNetworkDispatch() throws Exception {
        RichScenarios.usageFailureHappensBeforeAnyNetworkDispatch(richCommand());
    }

    @Test
    @Timeout(120)
    void loopbackRunSendsTheCallbackKeyOnTheCallbackKeyWireNameNeverQueryQ() throws Exception {
        RichScenarios.loopbackRunSendsTheCallbackKeyOnTheCallbackKeyWireNameNeverQueryQ(richCommand());
    }

    @Test
    @Timeout(120)
    void missingLoopbackTestKeyKeepsTheLocalConfigurationExit() throws Exception {
        RichScenarios.missingLoopbackTestKeyKeepsTheLocalConfigurationExit(richCommand());
    }

    @Test
    @Timeout(120)
    void apiVersionPinReachesTheWireAndImpossibleDatesFailUsage() throws Exception {
        RichScenarios.apiVersionPinReachesTheWireAndImpossibleDatesFailUsage(richCommand());
    }

    @Test
    @Timeout(120)
    void downstreamPipeCloseIsSilentZero() throws Exception {
        RichScenarios.downstreamPipeCloseIsSilentZero(richCommand());
    }

    @Test
    @Timeout(120)
    void nonObjectElementsAndEmptyKnownVerticalsStayCountedAndRenderTolerantly() throws Exception {
        RichScenarios.nonObjectElementsAndEmptyKnownVerticalsStayCountedAndRenderTolerantly(richCommand());
    }

    private static List<String> richCommand() {
        String launcherProperty = System.getProperty("brave.search.jvm.launcher");
        assertNotNull(launcherProperty, "brave.search.jvm.launcher must be injected by the build");
        Path launcher = Path.of(launcherProperty);
        assertTrue(Files.isRegularFile(launcher), () -> "installed launcher missing: " + launcher);
        assertTrue(Files.isExecutable(launcher), () -> "installed launcher not executable: " + launcher);
        return List.of(launcher.toString(), RichCommand.NAME);
    }
}

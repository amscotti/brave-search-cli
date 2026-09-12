package io.amscotti.bravesearch.adapter.cli.command.places;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.testsupport.PlacesScenarios;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Whole-process evidence for the places search command against the JVM executable
 * produced by {@code installDist}: the launcher script, the real credential routing,
 * the real stdout pipe, and the real exit statuses, all against a scripted loopback
 * server with an isolated environment.
 */
final class PlacesProcessTest {

    @Test
    @Timeout(120)
    void humanHappyPathRendersNumberedLfLinesWithoutAnsi() throws Exception {
        PlacesScenarios.humanHappyPathRendersNumberedLfLinesWithoutAnsi(placesCommand());
    }

    @Test
    @Timeout(120)
    void jsonEnvelopeIsOneValidatedLfLine() throws Exception {
        PlacesScenarios.jsonSuccessEnvelopeIsOneValidatedLfLine(placesCommand());
    }

    @Test
    @Timeout(120)
    void jsonlEmitsValidatedResultRecordsThenSummary() throws Exception {
        PlacesScenarios.jsonlEmitsValidatedResultRecordsThenSummary(placesCommand());
    }

    @Test
    @Timeout(120)
    void rawOutputIsTheServedBodyBytesExactly() throws Exception {
        PlacesScenarios.rawOutputIsTheServedBodyBytesExactly(placesCommand());
    }

    @Test
    @Timeout(120)
    void authenticationFailureExitsFourInHumanAndJsonModes() throws Exception {
        PlacesScenarios.authenticationFailureExitsFourInHumanAndJsonModes(placesCommand());
    }

    @Test
    @Timeout(120)
    void usageFailureHappensBeforeAnyNetworkDispatch() throws Exception {
        PlacesScenarios.usageFailureHappensBeforeAnyNetworkDispatch(placesCommand());
    }

    @Test
    @Timeout(120)
    void exploreModeDispatchesTheAnchorWithoutAnyQuery() throws Exception {
        PlacesScenarios.exploreModeDispatchesTheAnchorWithoutAnyQuery(placesCommand());
    }

    @Test
    @Timeout(120)
    void broadGlobalModeDispatchesWithoutQueryOrAnchor() throws Exception {
        PlacesScenarios.broadGlobalModeDispatchesWithoutQueryOrAnchor(placesCommand());
    }

    @Test
    @Timeout(120)
    void loopbackRunsSendTheTestKeyWithTheExactAnchorWireForm() throws Exception {
        PlacesScenarios.loopbackRunSendsTheTestKeyWithTheExactAnchorWireForm(placesCommand());
    }

    @Test
    @Timeout(120)
    void missingLoopbackTestKeyKeepsTheLocalConfigurationExit() throws Exception {
        PlacesScenarios.missingLoopbackTestKeyKeepsTheLocalConfigurationExit(placesCommand());
    }

    @Test
    @Timeout(120)
    void requestedRadiusAppendsTheRankingBiasNote() throws Exception {
        PlacesScenarios.requestedRadiusAppendsTheRankingBiasNote(placesCommand());
    }

    @Test
    @Timeout(120)
    void downstreamPipeCloseIsSilentZero() throws Exception {
        PlacesScenarios.downstreamPipeCloseIsSilentZero(placesCommand());
    }

    @Test
    @Timeout(120)
    void apiVersionPinReachesTheWireAndImpossibleDatesFailUsage() throws Exception {
        PlacesScenarios.apiVersionPinReachesTheWireAndImpossibleDatesFailUsage(placesCommand());
    }

    private static List<String> placesCommand() {
        String launcherProperty = System.getProperty("brave.search.jvm.launcher");
        assertNotNull(launcherProperty, "brave.search.jvm.launcher must be injected by the build");
        Path launcher = Path.of(launcherProperty);
        assertTrue(Files.isRegularFile(launcher), () -> "installed launcher missing: " + launcher);
        assertTrue(Files.isExecutable(launcher), () -> "installed launcher not executable: " + launcher);
        return List.of(launcher.toString(), PlacesCommand.NAME, PlacesSearchCommand.NAME);
    }
}

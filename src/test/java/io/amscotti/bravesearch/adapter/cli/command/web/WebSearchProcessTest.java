package io.amscotti.bravesearch.adapter.cli.command.web;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.testsupport.WebScenarios;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Whole-process evidence for the web command against the JVM executable produced by {@code
 * installDist}: the launcher script, the real credential routing, the real stdout pipe, and
 * the real exit statuses, all against a scripted loopback server with an isolated environment.
 */
final class WebSearchProcessTest {

    @Test
    @Timeout(120)
    void humanHappyPathRendersNumberedLfLinesWithoutAnsi() throws Exception {
        WebScenarios.humanHappyPathRendersNumberedLfLinesWithoutAnsi(webCommand());
    }

    @Test
    @Timeout(120)
    void jsonSuccessEnvelopeIsOneValidatedLfLine() throws Exception {
        WebScenarios.jsonSuccessEnvelopeIsOneValidatedLfLine(webCommand());
    }

    @Test
    @Timeout(120)
    void jsonlEmitsValidatedResultRecordsThenSummary() throws Exception {
        WebScenarios.jsonlEmitsValidatedResultRecordsThenSummary(webCommand());
    }

    @Test
    @Timeout(120)
    void rawOutputIsTheServedBodyBytesExactly() throws Exception {
        WebScenarios.rawOutputIsTheServedBodyBytesExactly(webCommand());
    }

    @Test
    @Timeout(120)
    void authenticationFailureExitsFourInHumanAndJsonModes() throws Exception {
        WebScenarios.authenticationFailureExitsFourInHumanAndJsonModes(webCommand());
    }

    @Test
    @Timeout(120)
    void rateLimitedJsonlErrorCarriesTheObservedWindows() throws Exception {
        WebScenarios.rateLimitedJsonlErrorCarriesTheObservedWindows(webCommand());
    }

    @Test
    @Timeout(120)
    void usageFailureHappensBeforeAnyNetworkDispatch() throws Exception {
        WebScenarios.usageFailureHappensBeforeAnyNetworkDispatch(webCommand());
    }

    @Test
    @Timeout(120)
    void loopbackRunsSendTheTestKeyAndNeverStoredCredentials() throws Exception {
        WebScenarios.loopbackRunsSendTheTestKeyAndNeverStoredCredentials(webCommand());
    }

    @Test
    @Timeout(120)
    void missingLoopbackTestKeyKeepsTheLocalConfigurationExit() throws Exception {
        WebScenarios.missingLoopbackTestKeyKeepsTheLocalConfigurationExit(webCommand());
    }

    @Test
    @Timeout(120)
    void apiVersionPinReachesTheWireAndImpossibleDatesFailUsage() throws Exception {
        WebScenarios.apiVersionPinReachesTheWireAndImpossibleDatesFailUsage(webCommand());
    }

    @Test
    @Timeout(120)
    void downstreamPipeCloseIsSilentZero() throws Exception {
        WebScenarios.downstreamPipeCloseIsSilentZero(webCommand());
    }

    @Test
    @Timeout(120)
    void pagedJsonlKeepsPageOneRecordsBeforeTheLaterPageError() throws Exception {
        WebScenarios.pagedJsonlKeepsPageOneRecordsBeforeTheLaterPageError(webCommand());
    }

    @Test
    @Timeout(120)
    void pagedJsonlBrokenPipeMidWalkIsSilentZero() throws Exception {
        WebScenarios.pagedJsonlBrokenPipeMidWalkIsSilentZero(webCommand());
    }

    @Test
    @Timeout(120)
    void sigintDuringPacedWalkExits130() throws Exception {
        WebScenarios.sigintDuringPacedWalkExits130(webCommand());
    }

    @Test
    @Timeout(120)
    void sigtermDuringPacedWalkExits143() throws Exception {
        WebScenarios.sigtermDuringPacedWalkExits143(webCommand());
    }

    @Test
    @Timeout(120)
    void sigintDuringBlockedBodyExits130() throws Exception {
        WebScenarios.sigintDuringBlockedBodyExits130(webCommand());
    }

    @Test
    @Timeout(120)
    void sigtermDuringBlockedBodyExits143() throws Exception {
        WebScenarios.sigtermDuringBlockedBodyExits143(webCommand());
    }

    @Test
    @Timeout(120)
    void upstreamTerminalControlsNeverReachHumanOutput() throws Exception {
        WebScenarios.upstreamTerminalControlsNeverReachHumanOutput(webCommand());
    }

    @Test
    @Timeout(120)
    void signalAfterCompletionKeepsZero() throws Exception {
        WebScenarios.signalAfterCompletionKeepsZero(webCommand());
    }

    private static List<String> webCommand() {
        String launcherProperty = System.getProperty("brave.search.jvm.launcher");
        assertNotNull(launcherProperty, "brave.search.jvm.launcher must be injected by the build");
        Path launcher = Path.of(launcherProperty);
        assertTrue(Files.isRegularFile(launcher), () -> "installed launcher missing: " + launcher);
        assertTrue(Files.isExecutable(launcher), () -> "installed launcher not executable: " + launcher);
        return List.of(launcher.toString(), WebSearchCommand.NAME);
    }
}

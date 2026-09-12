package io.amscotti.bravesearch.adapter.cli.command.suggest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.testsupport.SuggestScenarios;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Whole-process evidence for the suggest command against the JVM executable produced by
 * {@code installDist}: the launcher script, the real credential routing, the real stdout
 * pipe, and the real exit statuses, all against a scripted loopback server with an
 * isolated environment.
 */
final class SuggestProcessTest {

    @Test
    @Timeout(120)
    void humanHappyPathRendersNumberedLfLinesWithoutAnsi() throws Exception {
        SuggestScenarios.humanHappyPathRendersNumberedLfLinesWithoutAnsi(suggestCommand());
    }

    @Test
    @Timeout(120)
    void jsonEnvelopeIsOneValidatedLfLine() throws Exception {
        SuggestScenarios.jsonEnvelopeIsOneValidatedLfLine(suggestCommand());
    }

    @Test
    @Timeout(120)
    void jsonlEmitsValidatedResultRecordsThenSummary() throws Exception {
        SuggestScenarios.jsonlEmitsValidatedResultRecordsThenSummary(suggestCommand());
    }

    @Test
    @Timeout(120)
    void rawOutputIsTheServedBodyBytesExactly() throws Exception {
        SuggestScenarios.rawOutputIsTheServedBodyBytesExactly(suggestCommand());
    }

    @Test
    @Timeout(120)
    void authenticationFailureExitsFourInHumanAndJsonModes() throws Exception {
        SuggestScenarios.authenticationFailureExitsFourInHumanAndJsonModes(suggestCommand());
    }

    @Test
    @Timeout(120)
    void usageFailureHappensBeforeAnyNetworkDispatch() throws Exception {
        SuggestScenarios.usageFailureHappensBeforeAnyNetworkDispatch(suggestCommand());
    }

    @Test
    @Timeout(120)
    void loopbackRunsSendTheTestKeyWithTheLanguageAliasOnTheSuggestWireName() throws Exception {
        SuggestScenarios.loopbackRunSendsTheTestKeyWithTheLanguageAliasOnTheSuggestWireName(suggestCommand());
    }

    @Test
    @Timeout(120)
    void missingLoopbackTestKeyKeepsTheLocalConfigurationExit() throws Exception {
        SuggestScenarios.missingLoopbackTestKeyKeepsTheLocalConfigurationExit(suggestCommand());
    }

    @Test
    @Timeout(120)
    void apiVersionPinReachesTheWireAndImpossibleDatesFailUsage() throws Exception {
        SuggestScenarios.apiVersionPinReachesTheWireAndImpossibleDatesFailUsage(suggestCommand());
    }

    @Test
    @Timeout(120)
    void downstreamPipeCloseIsSilentZero() throws Exception {
        SuggestScenarios.downstreamPipeCloseIsSilentZero(suggestCommand());
    }

    @Test
    @Timeout(120)
    void garbageSuccessBodyExitsEight() throws Exception {
        SuggestScenarios.garbageSuccessBodyExitsEight(suggestCommand());
    }

    private static List<String> suggestCommand() {
        String launcherProperty = System.getProperty("brave.search.jvm.launcher");
        assertNotNull(launcherProperty, "brave.search.jvm.launcher must be injected by the build");
        Path launcher = Path.of(launcherProperty);
        assertTrue(Files.isRegularFile(launcher), () -> "installed launcher missing: " + launcher);
        assertTrue(Files.isExecutable(launcher), () -> "installed launcher not executable: " + launcher);
        return List.of(launcher.toString(), SuggestCommand.NAME);
    }
}

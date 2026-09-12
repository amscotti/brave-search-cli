package io.amscotti.bravesearch.adapter.cli.command.spellcheck;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.testsupport.SpellcheckScenarios;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Whole-process evidence for the spellcheck command against the JVM executable produced
 * by {@code installDist}: the launcher script, the real credential routing, the real
 * stdout pipe, and the real exit statuses, all against a scripted loopback server with
 * an isolated environment.
 */
final class SpellcheckProcessTest {

    @Test
    @Timeout(120)
    void humanHappyPathRendersNumberedLfLinesWithoutAnsi() throws Exception {
        SpellcheckScenarios.humanHappyPathRendersNumberedLfLinesWithoutAnsi(spellcheckCommand());
    }

    @Test
    @Timeout(120)
    void anEmptyCorrectionListRendersTheSingleNoCorrectionsLine() throws Exception {
        SpellcheckScenarios.anEmptyCorrectionListRendersTheSingleNoCorrectionsLine(spellcheckCommand());
    }

    @Test
    @Timeout(120)
    void jsonEnvelopeIsOneValidatedLfLine() throws Exception {
        SpellcheckScenarios.jsonEnvelopeIsOneValidatedLfLine(spellcheckCommand());
    }

    @Test
    @Timeout(120)
    void jsonlEmitsValidatedResultRecordsThenSummary() throws Exception {
        SpellcheckScenarios.jsonlEmitsValidatedResultRecordsThenSummary(spellcheckCommand());
    }

    @Test
    @Timeout(120)
    void rawOutputIsTheServedBodyBytesExactly() throws Exception {
        SpellcheckScenarios.rawOutputIsTheServedBodyBytesExactly(spellcheckCommand());
    }

    @Test
    @Timeout(120)
    void authenticationFailureExitsFourInHumanAndJsonModes() throws Exception {
        SpellcheckScenarios.authenticationFailureExitsFourInHumanAndJsonModes(spellcheckCommand());
    }

    @Test
    @Timeout(120)
    void usageFailureHappensBeforeAnyNetworkDispatch() throws Exception {
        SpellcheckScenarios.usageFailureHappensBeforeAnyNetworkDispatch(spellcheckCommand());
    }

    @Test
    @Timeout(120)
    void loopbackRunsSendTheTestKeyWithTheLanguageAliasOnTheSpellcheckWireName() throws Exception {
        SpellcheckScenarios.loopbackRunSendsTheTestKeyWithTheLanguageAliasOnTheSpellcheckWireName(spellcheckCommand());
    }

    @Test
    @Timeout(120)
    void missingLoopbackTestKeyKeepsTheLocalConfigurationExit() throws Exception {
        SpellcheckScenarios.missingLoopbackTestKeyKeepsTheLocalConfigurationExit(spellcheckCommand());
    }

    @Test
    @Timeout(120)
    void apiVersionPinReachesTheWireAndImpossibleDatesFailUsage() throws Exception {
        SpellcheckScenarios.apiVersionPinReachesTheWireAndImpossibleDatesFailUsage(spellcheckCommand());
    }

    @Test
    @Timeout(120)
    void downstreamPipeCloseIsSilentZero() throws Exception {
        SpellcheckScenarios.downstreamPipeCloseIsSilentZero(spellcheckCommand());
    }

    @Test
    @Timeout(120)
    void garbageSuccessBodyExitsEight() throws Exception {
        SpellcheckScenarios.garbageSuccessBodyExitsEight(spellcheckCommand());
    }

    private static List<String> spellcheckCommand() {
        String launcherProperty = System.getProperty("brave.search.jvm.launcher");
        assertNotNull(launcherProperty, "brave.search.jvm.launcher must be injected by the build");
        Path launcher = Path.of(launcherProperty);
        assertTrue(Files.isRegularFile(launcher), () -> "installed launcher missing: " + launcher);
        assertTrue(Files.isExecutable(launcher), () -> "installed launcher not executable: " + launcher);
        return List.of(launcher.toString(), SpellcheckCommand.NAME);
    }
}

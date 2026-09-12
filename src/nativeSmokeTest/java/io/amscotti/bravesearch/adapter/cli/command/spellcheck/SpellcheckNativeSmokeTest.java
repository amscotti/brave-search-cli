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
 * The same whole-process spellcheck command evidence as the JVM process suite, run
 * against the exact native executable produced by {@code nativeCompile}: every success
 * shape including the empty case's {@code No corrections.} line, the failure and usage
 * contracts — the spellcheck-specific ones included: the undocumented {@code --count},
 * the suggest-only {@code --rich}, the {@code --lang} spelling, and the language alias
 * riding the spellcheck wire name — the credential routing under a decoy environment,
 * and the Api-Version pin must all agree with the JVM's behavior — the native smoke
 * suite is the loopback-agreeing subset of the JVM-pinned scenarios, bounded by its
 * source set, not a narrower contract.
 */
final class SpellcheckNativeSmokeTest {

    @Test
    @Timeout(180)
    void humanHappyPathRendersNumberedLfLinesWithoutAnsi() throws Exception {
        SpellcheckScenarios.humanHappyPathRendersNumberedLfLinesWithoutAnsi(spellcheckCommand());
    }

    @Test
    @Timeout(180)
    void anEmptyCorrectionListRendersTheSingleNoCorrectionsLine() throws Exception {
        SpellcheckScenarios.anEmptyCorrectionListRendersTheSingleNoCorrectionsLine(spellcheckCommand());
    }

    @Test
    @Timeout(180)
    void jsonEnvelopeIsOneValidatedLfLine() throws Exception {
        SpellcheckScenarios.jsonEnvelopeIsOneValidatedLfLine(spellcheckCommand());
    }

    @Test
    @Timeout(180)
    void jsonlEmitsValidatedResultRecordsThenSummary() throws Exception {
        SpellcheckScenarios.jsonlEmitsValidatedResultRecordsThenSummary(spellcheckCommand());
    }

    @Test
    @Timeout(180)
    void rawOutputIsTheServedBodyBytesExactly() throws Exception {
        SpellcheckScenarios.rawOutputIsTheServedBodyBytesExactly(spellcheckCommand());
    }

    @Test
    @Timeout(180)
    void authenticationFailureExitsFourInHumanAndJsonModes() throws Exception {
        SpellcheckScenarios.authenticationFailureExitsFourInHumanAndJsonModes(spellcheckCommand());
    }

    @Test
    @Timeout(180)
    void usageFailureHappensBeforeAnyNetworkDispatch() throws Exception {
        SpellcheckScenarios.usageFailureHappensBeforeAnyNetworkDispatch(spellcheckCommand());
    }

    @Test
    @Timeout(180)
    void loopbackRunsSendTheTestKeyWithTheLanguageAliasOnTheSpellcheckWireName() throws Exception {
        SpellcheckScenarios.loopbackRunSendsTheTestKeyWithTheLanguageAliasOnTheSpellcheckWireName(spellcheckCommand());
    }

    @Test
    @Timeout(180)
    void missingLoopbackTestKeyKeepsTheLocalConfigurationExit() throws Exception {
        SpellcheckScenarios.missingLoopbackTestKeyKeepsTheLocalConfigurationExit(spellcheckCommand());
    }

    @Test
    @Timeout(180)
    void apiVersionPinReachesTheWireAndImpossibleDatesFailUsage() throws Exception {
        SpellcheckScenarios.apiVersionPinReachesTheWireAndImpossibleDatesFailUsage(spellcheckCommand());
    }

    @Test
    @Timeout(180)
    void downstreamPipeCloseIsSilentZero() throws Exception {
        SpellcheckScenarios.downstreamPipeCloseIsSilentZero(spellcheckCommand());
    }

    @Test
    @Timeout(180)
    void garbageSuccessBodyExitsEight() throws Exception {
        SpellcheckScenarios.garbageSuccessBodyExitsEight(spellcheckCommand());
    }

    private static List<String> spellcheckCommand() {
        String binaryProperty = System.getProperty("brave.search.native.binary");
        assertNotNull(binaryProperty, "brave.search.native.binary must be injected by the build");
        Path binary = Path.of(binaryProperty);
        assertTrue(Files.isRegularFile(binary), () -> "native binary missing: " + binary);
        assertTrue(Files.isExecutable(binary), () -> "native binary not executable: " + binary);
        return List.of(binary.toString(), SpellcheckCommand.NAME);
    }
}

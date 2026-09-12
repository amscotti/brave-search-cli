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
 * The same whole-process suggest command evidence as the JVM process suite, run against
 * the exact native executable produced by {@code nativeCompile}: every success shape,
 * the failure and usage contracts — the suggest-specific ones included: the count bound
 * of 20, the {@code --lang} spelling, and the language alias riding the suggest wire
 * name — the credential routing under a decoy environment, and the Api-Version pin must
 * all agree with the JVM's behavior — the native smoke suite is the loopback-agreeing
 * subset of the JVM-pinned scenarios, bounded by its source set, not a narrower
 * contract.
 */
final class SuggestNativeSmokeTest {

    @Test
    @Timeout(180)
    void humanHappyPathRendersNumberedLfLinesWithoutAnsi() throws Exception {
        SuggestScenarios.humanHappyPathRendersNumberedLfLinesWithoutAnsi(suggestCommand());
    }

    @Test
    @Timeout(180)
    void jsonEnvelopeIsOneValidatedLfLine() throws Exception {
        SuggestScenarios.jsonEnvelopeIsOneValidatedLfLine(suggestCommand());
    }

    @Test
    @Timeout(180)
    void jsonlEmitsValidatedResultRecordsThenSummary() throws Exception {
        SuggestScenarios.jsonlEmitsValidatedResultRecordsThenSummary(suggestCommand());
    }

    @Test
    @Timeout(180)
    void rawOutputIsTheServedBodyBytesExactly() throws Exception {
        SuggestScenarios.rawOutputIsTheServedBodyBytesExactly(suggestCommand());
    }

    @Test
    @Timeout(180)
    void authenticationFailureExitsFourInHumanAndJsonModes() throws Exception {
        SuggestScenarios.authenticationFailureExitsFourInHumanAndJsonModes(suggestCommand());
    }

    @Test
    @Timeout(180)
    void usageFailureHappensBeforeAnyNetworkDispatch() throws Exception {
        SuggestScenarios.usageFailureHappensBeforeAnyNetworkDispatch(suggestCommand());
    }

    @Test
    @Timeout(180)
    void loopbackRunsSendTheTestKeyWithTheLanguageAliasOnTheSuggestWireName() throws Exception {
        SuggestScenarios.loopbackRunSendsTheTestKeyWithTheLanguageAliasOnTheSuggestWireName(suggestCommand());
    }

    @Test
    @Timeout(180)
    void missingLoopbackTestKeyKeepsTheLocalConfigurationExit() throws Exception {
        SuggestScenarios.missingLoopbackTestKeyKeepsTheLocalConfigurationExit(suggestCommand());
    }

    @Test
    @Timeout(180)
    void apiVersionPinReachesTheWireAndImpossibleDatesFailUsage() throws Exception {
        SuggestScenarios.apiVersionPinReachesTheWireAndImpossibleDatesFailUsage(suggestCommand());
    }

    @Test
    @Timeout(180)
    void downstreamPipeCloseIsSilentZero() throws Exception {
        SuggestScenarios.downstreamPipeCloseIsSilentZero(suggestCommand());
    }

    @Test
    @Timeout(180)
    void garbageSuccessBodyExitsEight() throws Exception {
        SuggestScenarios.garbageSuccessBodyExitsEight(suggestCommand());
    }

    private static List<String> suggestCommand() {
        String binaryProperty = System.getProperty("brave.search.native.binary");
        assertNotNull(binaryProperty, "brave.search.native.binary must be injected by the build");
        Path binary = Path.of(binaryProperty);
        assertTrue(Files.isRegularFile(binary), () -> "native binary missing: " + binary);
        assertTrue(Files.isExecutable(binary), () -> "native binary not executable: " + binary);
        return List.of(binary.toString(), SuggestCommand.NAME);
    }
}

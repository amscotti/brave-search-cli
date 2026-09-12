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
 * The same whole-process web command evidence as the JVM process suite, run against the exact
 * native executable produced by {@code nativeCompile}: every success shape, the failure and
 * usage contracts, the credential routing under a decoy environment, the Api-Version pin,
 * the downstream pipe close, and the paged walk's later-page partial failure, mid-walk
 * broken pipe, and paced-walk interrupt must all agree with the JVM's behavior — the native
 * smoke suite is the loopback-agreeing subset of the JVM-pinned scenarios, bounded by its
 * source set, not a narrower contract.
 */
final class WebSearchNativeSmokeTest {

    @Test
    @Timeout(180)
    void humanHappyPathRendersNumberedLfLinesWithoutAnsi() throws Exception {
        WebScenarios.humanHappyPathRendersNumberedLfLinesWithoutAnsi(webCommand());
    }

    @Test
    @Timeout(180)
    void jsonSuccessEnvelopeIsOneValidatedLfLine() throws Exception {
        WebScenarios.jsonSuccessEnvelopeIsOneValidatedLfLine(webCommand());
    }

    @Test
    @Timeout(180)
    void jsonlEmitsValidatedResultRecordsThenSummary() throws Exception {
        WebScenarios.jsonlEmitsValidatedResultRecordsThenSummary(webCommand());
    }

    @Test
    @Timeout(180)
    void rawOutputIsTheServedBodyBytesExactly() throws Exception {
        WebScenarios.rawOutputIsTheServedBodyBytesExactly(webCommand());
    }

    @Test
    @Timeout(180)
    void authenticationFailureExitsFourInHumanAndJsonModes() throws Exception {
        WebScenarios.authenticationFailureExitsFourInHumanAndJsonModes(webCommand());
    }

    @Test
    @Timeout(180)
    void rateLimitedJsonlErrorCarriesTheObservedWindows() throws Exception {
        WebScenarios.rateLimitedJsonlErrorCarriesTheObservedWindows(webCommand());
    }

    @Test
    @Timeout(180)
    void upstreamTerminalControlsNeverReachHumanOutput() throws Exception {
        WebScenarios.upstreamTerminalControlsNeverReachHumanOutput(webCommand());
    }

    @Test
    @Timeout(180)
    void usageFailureHappensBeforeAnyNetworkDispatch() throws Exception {
        WebScenarios.usageFailureHappensBeforeAnyNetworkDispatch(webCommand());
    }

    @Test
    @Timeout(180)
    void loopbackRunsSendTheTestKeyAndNeverStoredCredentials() throws Exception {
        WebScenarios.loopbackRunsSendTheTestKeyAndNeverStoredCredentials(webCommand());
    }

    @Test
    @Timeout(180)
    void missingLoopbackTestKeyKeepsTheLocalConfigurationExit() throws Exception {
        WebScenarios.missingLoopbackTestKeyKeepsTheLocalConfigurationExit(webCommand());
    }

    @Test
    @Timeout(180)
    void apiVersionPinReachesTheWireAndImpossibleDatesFailUsage() throws Exception {
        WebScenarios.apiVersionPinReachesTheWireAndImpossibleDatesFailUsage(webCommand());
    }

    @Test
    @Timeout(180)
    void downstreamPipeCloseIsSilentZero() throws Exception {
        WebScenarios.downstreamPipeCloseIsSilentZero(webCommand());
    }

    @Test
    @Timeout(180)
    void pagedJsonlKeepsPageOneRecordsBeforeTheLaterPageError() throws Exception {
        WebScenarios.pagedJsonlKeepsPageOneRecordsBeforeTheLaterPageError(webCommand());
    }

    @Test
    @Timeout(180)
    void pagedJsonlBrokenPipeMidWalkIsSilentZero() throws Exception {
        WebScenarios.pagedJsonlBrokenPipeMidWalkIsSilentZero(webCommand());
    }

    @Test
    @Timeout(180)
    void sigintDuringPacedWalkExits130() throws Exception {
        WebScenarios.sigintDuringPacedWalkExits130(webCommand());
    }

    @Test
    @Timeout(180)
    void sigtermDuringPacedWalkExits143() throws Exception {
        WebScenarios.sigtermDuringPacedWalkExits143(webCommand());
    }

    @Test
    @Timeout(180)
    void sigintDuringBlockedBodyExits130() throws Exception {
        WebScenarios.sigintDuringBlockedBodyExits130(webCommand());
    }

    @Test
    @Timeout(180)
    void sigtermDuringBlockedBodyExits143() throws Exception {
        WebScenarios.sigtermDuringBlockedBodyExits143(webCommand());
    }

    @Test
    @Timeout(180)
    void signalAfterCompletionKeepsZero() throws Exception {
        WebScenarios.signalAfterCompletionKeepsZero(webCommand());
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

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
 * The same whole-process rich command evidence as the JVM process suite, run against
 * the exact native executable produced by {@code nativeCompile}: every success shape,
 * the failure and usage contracts — the callback-key wire name included — the
 * credential routing under a decoy environment, the Api-Version pin, and the silent
 * early termination must all agree with the JVM's behavior — the native smoke suite is
 * the loopback-agreeing subset of the JVM-pinned scenarios, bounded by its source set,
 * not a narrower contract.
 */
final class RichNativeSmokeTest {

    @Test
    @Timeout(180)
    void humanHappyPathRendersPerVerticalSectionsWithoutAnsi() throws Exception {
        RichScenarios.humanHappyPathRendersPerVerticalSectionsWithoutAnsi(richCommand());
    }

    @Test
    @Timeout(180)
    void jsonEnvelopeIsOneValidatedLfLine() throws Exception {
        RichScenarios.jsonEnvelopeIsOneValidatedLfLine(richCommand());
    }

    @Test
    @Timeout(180)
    void providerAttributionSurvivesHumanRenderingAndMachineLosslessness() throws Exception {
        RichScenarios.providerAttributionSurvivesHumanRenderingAndMachineLosslessness(richCommand());
    }

    @Test
    @Timeout(180)
    void jsonlEmitsOneValidatedRecordPerVerticalThenSummary() throws Exception {
        RichScenarios.jsonlEmitsOneValidatedRecordPerVerticalThenSummary(richCommand());
    }

    @Test
    @Timeout(180)
    void rawOutputIsTheServedBodyBytesExactly() throws Exception {
        RichScenarios.rawOutputIsTheServedBodyBytesExactly(richCommand());
    }

    @Test
    @Timeout(180)
    void authenticationFailureExitsFourInHumanAndJsonModes() throws Exception {
        RichScenarios.authenticationFailureExitsFourInHumanAndJsonModes(richCommand());
    }

    @Test
    @Timeout(180)
    void usageFailureHappensBeforeAnyNetworkDispatch() throws Exception {
        RichScenarios.usageFailureHappensBeforeAnyNetworkDispatch(richCommand());
    }

    @Test
    @Timeout(180)
    void loopbackRunSendsTheCallbackKeyOnTheCallbackKeyWireNameNeverQueryQ() throws Exception {
        RichScenarios.loopbackRunSendsTheCallbackKeyOnTheCallbackKeyWireNameNeverQueryQ(richCommand());
    }

    @Test
    @Timeout(180)
    void missingLoopbackTestKeyKeepsTheLocalConfigurationExit() throws Exception {
        RichScenarios.missingLoopbackTestKeyKeepsTheLocalConfigurationExit(richCommand());
    }

    @Test
    @Timeout(180)
    void apiVersionPinReachesTheWireAndImpossibleDatesFailUsage() throws Exception {
        RichScenarios.apiVersionPinReachesTheWireAndImpossibleDatesFailUsage(richCommand());
    }

    @Test
    @Timeout(180)
    void downstreamPipeCloseIsSilentZero() throws Exception {
        RichScenarios.downstreamPipeCloseIsSilentZero(richCommand());
    }

    @Test
    @Timeout(180)
    void nonObjectElementsAndEmptyKnownVerticalsStayCountedAndRenderTolerantly() throws Exception {
        RichScenarios.nonObjectElementsAndEmptyKnownVerticalsStayCountedAndRenderTolerantly(richCommand());
    }

    private static List<String> richCommand() {
        String binaryProperty = System.getProperty("brave.search.native.binary");
        assertNotNull(binaryProperty, "brave.search.native.binary must be injected by the build");
        Path binary = Path.of(binaryProperty);
        assertTrue(Files.isRegularFile(binary), () -> "native binary missing: " + binary);
        assertTrue(Files.isExecutable(binary), () -> "native binary not executable: " + binary);
        return List.of(binary.toString(), RichCommand.NAME);
    }
}

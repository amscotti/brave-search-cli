package io.amscotti.bravesearch.adapter.cli.command.images;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.testsupport.ImageScenarios;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The same whole-process images command evidence as the JVM process suite, run against
 * the exact native executable produced by {@code nativeCompile}: every success shape, the
 * failure and usage contracts — the images-specific ones included: the count bound of
 * 200, the moderate SafeSearch rejection, and every page spelling — the credential
 * routing under a decoy environment, and the Api-Version pin must all agree with the
 * JVM's behavior — the native smoke suite is the loopback-agreeing subset of the
 * JVM-pinned scenarios, bounded by its source set, not a narrower contract.
 */
final class ImageSearchNativeSmokeTest {

    @Test
    @Timeout(180)
    void humanHappyPathRendersNumberedLfLinesWithoutAnsi() throws Exception {
        ImageScenarios.humanHappyPathRendersNumberedLfLinesWithoutAnsi(imagesCommand());
    }

    @Test
    @Timeout(180)
    void jsonSuccessEnvelopeIsOneValidatedLfLine() throws Exception {
        ImageScenarios.jsonSuccessEnvelopeIsOneValidatedLfLine(imagesCommand());
    }

    @Test
    @Timeout(180)
    void jsonlEmitsValidatedResultRecordsThenSummary() throws Exception {
        ImageScenarios.jsonlEmitsValidatedResultRecordsThenSummary(imagesCommand());
    }

    @Test
    @Timeout(180)
    void rawOutputIsTheServedBodyBytesExactly() throws Exception {
        ImageScenarios.rawOutputIsTheServedBodyBytesExactly(imagesCommand());
    }

    @Test
    @Timeout(180)
    void authenticationFailureExitsFourInHumanAndJsonModes() throws Exception {
        ImageScenarios.authenticationFailureExitsFourInHumanAndJsonModes(imagesCommand());
    }

    @Test
    @Timeout(180)
    void usageFailureHappensBeforeAnyNetworkDispatch() throws Exception {
        ImageScenarios.usageFailureHappensBeforeAnyNetworkDispatch(imagesCommand());
    }

    @Test
    @Timeout(180)
    void loopbackRunsSendTheTestKeyAndExactlyOneRequest() throws Exception {
        ImageScenarios.loopbackRunSendsTheTestKeyAndExactlyOneRequest(imagesCommand());
    }

    @Test
    @Timeout(180)
    void missingLoopbackTestKeyKeepsTheLocalConfigurationExit() throws Exception {
        ImageScenarios.missingLoopbackTestKeyKeepsTheLocalConfigurationExit(imagesCommand());
    }

    @Test
    @Timeout(180)
    void apiVersionPinReachesTheWireAndImpossibleDatesFailUsage() throws Exception {
        ImageScenarios.apiVersionPinReachesTheWireAndImpossibleDatesFailUsage(imagesCommand());
    }

    @Test
    @Timeout(180)
    void downstreamPipeCloseIsSilentZero() throws Exception {
        ImageScenarios.downstreamPipeCloseIsSilentZero(imagesCommand());
    }

    private static List<String> imagesCommand() {
        String binaryProperty = System.getProperty("brave.search.native.binary");
        assertNotNull(binaryProperty, "brave.search.native.binary must be injected by the build");
        Path binary = Path.of(binaryProperty);
        assertTrue(Files.isRegularFile(binary), () -> "native binary missing: " + binary);
        assertTrue(Files.isExecutable(binary), () -> "native binary not executable: " + binary);
        return List.of(binary.toString(), ImageSearchCommand.NAME);
    }
}

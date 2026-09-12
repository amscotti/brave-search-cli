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
 * Whole-process evidence for the images command against the JVM executable produced by
 * {@code installDist}: the launcher script, the real credential routing, the real stdout
 * pipe, and the real exit statuses, all against a scripted loopback server with an
 * isolated environment.
 */
final class ImageSearchProcessTest {

    @Test
    @Timeout(120)
    void humanHappyPathRendersNumberedLfLinesWithoutAnsi() throws Exception {
        ImageScenarios.humanHappyPathRendersNumberedLfLinesWithoutAnsi(imagesCommand());
    }

    @Test
    @Timeout(120)
    void jsonSuccessEnvelopeIsOneValidatedLfLine() throws Exception {
        ImageScenarios.jsonSuccessEnvelopeIsOneValidatedLfLine(imagesCommand());
    }

    @Test
    @Timeout(120)
    void jsonlEmitsValidatedResultRecordsThenSummary() throws Exception {
        ImageScenarios.jsonlEmitsValidatedResultRecordsThenSummary(imagesCommand());
    }

    @Test
    @Timeout(120)
    void rawOutputIsTheServedBodyBytesExactly() throws Exception {
        ImageScenarios.rawOutputIsTheServedBodyBytesExactly(imagesCommand());
    }

    @Test
    @Timeout(120)
    void authenticationFailureExitsFourInHumanAndJsonModes() throws Exception {
        ImageScenarios.authenticationFailureExitsFourInHumanAndJsonModes(imagesCommand());
    }

    @Test
    @Timeout(120)
    void usageFailureHappensBeforeAnyNetworkDispatch() throws Exception {
        ImageScenarios.usageFailureHappensBeforeAnyNetworkDispatch(imagesCommand());
    }

    @Test
    @Timeout(120)
    void loopbackRunsSendTheTestKeyAndExactlyOneRequest() throws Exception {
        ImageScenarios.loopbackRunSendsTheTestKeyAndExactlyOneRequest(imagesCommand());
    }

    @Test
    @Timeout(120)
    void missingLoopbackTestKeyKeepsTheLocalConfigurationExit() throws Exception {
        ImageScenarios.missingLoopbackTestKeyKeepsTheLocalConfigurationExit(imagesCommand());
    }

    @Test
    @Timeout(120)
    void apiVersionPinReachesTheWireAndImpossibleDatesFailUsage() throws Exception {
        ImageScenarios.apiVersionPinReachesTheWireAndImpossibleDatesFailUsage(imagesCommand());
    }

    @Test
    @Timeout(120)
    void downstreamPipeCloseIsSilentZero() throws Exception {
        ImageScenarios.downstreamPipeCloseIsSilentZero(imagesCommand());
    }

    private static List<String> imagesCommand() {
        String launcherProperty = System.getProperty("brave.search.jvm.launcher");
        assertNotNull(launcherProperty, "brave.search.jvm.launcher must be injected by the build");
        Path launcher = Path.of(launcherProperty);
        assertTrue(Files.isRegularFile(launcher), () -> "installed launcher missing: " + launcher);
        assertTrue(Files.isExecutable(launcher), () -> "installed launcher not executable: " + launcher);
        return List.of(launcher.toString(), ImageSearchCommand.NAME);
    }
}

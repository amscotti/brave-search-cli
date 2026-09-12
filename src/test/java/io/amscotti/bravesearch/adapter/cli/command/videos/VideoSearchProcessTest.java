package io.amscotti.bravesearch.adapter.cli.command.videos;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.testsupport.VideoScenarios;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Whole-process evidence for the videos command against the JVM executable produced by
 * {@code installDist}: the launcher script, the real credential routing, the real stdout
 * pipe, and the real exit statuses, all against a scripted loopback server with an
 * isolated environment.
 */
final class VideoSearchProcessTest {

    @Test
    @Timeout(120)
    void humanHappyPathRendersNumberedLfLinesWithoutAnsi() throws Exception {
        VideoScenarios.humanHappyPathRendersNumberedLfLinesWithoutAnsi(videosCommand());
    }

    @Test
    @Timeout(120)
    void jsonSuccessEnvelopeIsOneValidatedLfLine() throws Exception {
        VideoScenarios.jsonSuccessEnvelopeIsOneValidatedLfLine(videosCommand());
    }

    @Test
    @Timeout(120)
    void jsonlEmitsValidatedResultRecordsThenSummary() throws Exception {
        VideoScenarios.jsonlEmitsValidatedResultRecordsThenSummary(videosCommand());
    }

    @Test
    @Timeout(120)
    void rawOutputIsTheServedBodyBytesExactly() throws Exception {
        VideoScenarios.rawOutputIsTheServedBodyBytesExactly(videosCommand());
    }

    @Test
    @Timeout(120)
    void authenticationFailureExitsFourInHumanAndJsonModes() throws Exception {
        VideoScenarios.authenticationFailureExitsFourInHumanAndJsonModes(videosCommand());
    }

    @Test
    @Timeout(120)
    void usageFailureHappensBeforeAnyNetworkDispatch() throws Exception {
        VideoScenarios.usageFailureHappensBeforeAnyNetworkDispatch(videosCommand());
    }

    @Test
    @Timeout(120)
    void loopbackRunsSendTheTestKeyAndNeverStoredCredentials() throws Exception {
        VideoScenarios.loopbackRunsSendTheTestKeyAndNeverStoredCredentials(videosCommand());
    }

    @Test
    @Timeout(120)
    void missingLoopbackTestKeyKeepsTheLocalConfigurationExit() throws Exception {
        VideoScenarios.missingLoopbackTestKeyKeepsTheLocalConfigurationExit(videosCommand());
    }

    @Test
    @Timeout(120)
    void allPagesWalksExactlyTheTenDocumentedPages() throws Exception {
        VideoScenarios.allPagesWalksExactlyTheTenDocumentedPages(videosCommand());
    }

    @Test
    @Timeout(120)
    void maxPagesBoundsTheWalkToItsBudget() throws Exception {
        VideoScenarios.maxPagesBoundsTheWalkToItsBudget(videosCommand());
    }

    @Test
    @Timeout(120)
    void pagedWalkDeduplicatesOverlappingResultsAcrossPages() throws Exception {
        VideoScenarios.pagedWalkDeduplicatesOverlappingResultsAcrossPages(videosCommand());
    }

    @Test
    @Timeout(120)
    void pagedJsonlKeepsPageOneRecordsBeforeTheLaterPageError() throws Exception {
        VideoScenarios.pagedJsonlKeepsPageOneRecordsBeforeTheLaterPageError(videosCommand());
    }

    @Test
    @Timeout(120)
    void pagedJsonlBrokenPipeMidWalkIsSilentZero() throws Exception {
        VideoScenarios.pagedJsonlBrokenPipeMidWalkIsSilentZero(videosCommand());
    }

    @Test
    @Timeout(120)
    void sigintDuringPacedWalkExits130() throws Exception {
        VideoScenarios.sigintDuringPacedWalkExits130(videosCommand());
    }

    @Test
    @Timeout(120)
    void pacedWalkInterruptWaitsForTheCurrentInvocationToStart() throws Exception {
        List<String> delayedCommand = new ArrayList<>(List.of(
                "/bin/sh", "-c", "trap 'exit 130' INT; sleep 2; exec \"$@\"", "delayed-search"));
        delayedCommand.addAll(videosCommand());

        VideoScenarios.sigintDuringPacedWalkExits130(delayedCommand);
    }

    @Test
    @Timeout(120)
    void apiVersionPinReachesTheWireAndImpossibleDatesFailUsage() throws Exception {
        VideoScenarios.apiVersionPinReachesTheWireAndImpossibleDatesFailUsage(videosCommand());
    }

    @Test
    @Timeout(180)
    void downstreamPipeCloseIsSilentZero() throws Exception {
        VideoScenarios.downstreamPipeCloseIsSilentZero(videosCommand());
    }

    private static List<String> videosCommand() {
        String launcherProperty = System.getProperty("brave.search.jvm.launcher");
        assertNotNull(launcherProperty, "brave.search.jvm.launcher must be injected by the build");
        Path launcher = Path.of(launcherProperty);
        assertTrue(Files.isRegularFile(launcher), () -> "installed launcher missing: " + launcher);
        assertTrue(Files.isExecutable(launcher), () -> "installed launcher not executable: " + launcher);
        return List.of(launcher.toString(), VideoSearchCommand.NAME);
    }
}

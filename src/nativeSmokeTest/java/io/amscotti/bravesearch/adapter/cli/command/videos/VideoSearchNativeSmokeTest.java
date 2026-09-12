package io.amscotti.bravesearch.adapter.cli.command.videos;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.testsupport.VideoScenarios;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The same whole-process videos command evidence as the JVM process suite, run against the
 * exact native executable produced by {@code nativeCompile}: every success shape, the
 * failure and usage contracts, the credential routing under a decoy environment, the
 * Api-Version pin, the ten-page walk with no continuation field, the overlap
 * deduplication, the later-page partial failure, the mid-walk broken pipe, and the
 * paced-walk interrupt must all agree with the JVM's behavior — the native smoke suite is
 * the loopback-agreeing subset of the JVM-pinned scenarios, bounded by its source set, not
 * a narrower contract.
 */
final class VideoSearchNativeSmokeTest {

    @Test
    @Timeout(180)
    void humanHappyPathRendersNumberedLfLinesWithoutAnsi() throws Exception {
        VideoScenarios.humanHappyPathRendersNumberedLfLinesWithoutAnsi(videosCommand());
    }

    @Test
    @Timeout(180)
    void jsonSuccessEnvelopeIsOneValidatedLfLine() throws Exception {
        VideoScenarios.jsonSuccessEnvelopeIsOneValidatedLfLine(videosCommand());
    }

    @Test
    @Timeout(180)
    void jsonlEmitsValidatedResultRecordsThenSummary() throws Exception {
        VideoScenarios.jsonlEmitsValidatedResultRecordsThenSummary(videosCommand());
    }

    @Test
    @Timeout(180)
    void rawOutputIsTheServedBodyBytesExactly() throws Exception {
        VideoScenarios.rawOutputIsTheServedBodyBytesExactly(videosCommand());
    }

    @Test
    @Timeout(180)
    void authenticationFailureExitsFourInHumanAndJsonModes() throws Exception {
        VideoScenarios.authenticationFailureExitsFourInHumanAndJsonModes(videosCommand());
    }

    @Test
    @Timeout(180)
    void usageFailureHappensBeforeAnyNetworkDispatch() throws Exception {
        VideoScenarios.usageFailureHappensBeforeAnyNetworkDispatch(videosCommand());
    }

    @Test
    @Timeout(180)
    void loopbackRunsSendTheTestKeyAndNeverStoredCredentials() throws Exception {
        VideoScenarios.loopbackRunsSendTheTestKeyAndNeverStoredCredentials(videosCommand());
    }

    @Test
    @Timeout(180)
    void missingLoopbackTestKeyKeepsTheLocalConfigurationExit() throws Exception {
        VideoScenarios.missingLoopbackTestKeyKeepsTheLocalConfigurationExit(videosCommand());
    }

    @Test
    @Timeout(180)
    void allPagesWalksExactlyTheTenDocumentedPages() throws Exception {
        VideoScenarios.allPagesWalksExactlyTheTenDocumentedPages(videosCommand());
    }

    @Test
    @Timeout(180)
    void maxPagesBoundsTheWalkToItsBudget() throws Exception {
        VideoScenarios.maxPagesBoundsTheWalkToItsBudget(videosCommand());
    }

    @Test
    @Timeout(180)
    void pagedWalkDeduplicatesOverlappingResultsAcrossPages() throws Exception {
        VideoScenarios.pagedWalkDeduplicatesOverlappingResultsAcrossPages(videosCommand());
    }

    @Test
    @Timeout(180)
    void pagedJsonlKeepsPageOneRecordsBeforeTheLaterPageError() throws Exception {
        VideoScenarios.pagedJsonlKeepsPageOneRecordsBeforeTheLaterPageError(videosCommand());
    }

    @Test
    @Timeout(180)
    void pagedJsonlBrokenPipeMidWalkIsSilentZero() throws Exception {
        VideoScenarios.pagedJsonlBrokenPipeMidWalkIsSilentZero(videosCommand());
    }

    @Test
    @Timeout(180)
    void sigintDuringPacedWalkExits130() throws Exception {
        VideoScenarios.sigintDuringPacedWalkExits130(videosCommand());
    }

    @Test
    @Timeout(180)
    void apiVersionPinReachesTheWireAndImpossibleDatesFailUsage() throws Exception {
        VideoScenarios.apiVersionPinReachesTheWireAndImpossibleDatesFailUsage(videosCommand());
    }

    @Test
    @Timeout(180)
    void downstreamPipeCloseIsSilentZero() throws Exception {
        VideoScenarios.downstreamPipeCloseIsSilentZero(videosCommand());
    }

    private static List<String> videosCommand() {
        String binaryProperty = System.getProperty("brave.search.native.binary");
        assertNotNull(binaryProperty, "brave.search.native.binary must be injected by the build");
        Path binary = Path.of(binaryProperty);
        assertTrue(Files.isRegularFile(binary), () -> "native binary missing: " + binary);
        assertTrue(Files.isExecutable(binary), () -> "native binary not executable: " + binary);
        return List.of(binary.toString(), VideoSearchCommand.NAME);
    }
}

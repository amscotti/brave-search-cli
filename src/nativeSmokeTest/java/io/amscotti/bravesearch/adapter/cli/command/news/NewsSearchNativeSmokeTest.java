package io.amscotti.bravesearch.adapter.cli.command.news;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.testsupport.NewsScenarios;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The same whole-process news command evidence as the JVM process suite, run against the exact
 * native executable produced by {@code nativeCompile}: every success shape, the failure and
 * usage contracts, the credential routing under a decoy environment, the Api-Version pin, the
 * ten-page walk with no continuation field, the overlap deduplication, the later-page partial
 * failure, the mid-walk broken pipe, and the paced-walk interrupt must all agree with the
 * JVM's behavior — the native smoke suite is the loopback-agreeing subset of the JVM-pinned
 * scenarios, bounded by its source set, not a narrower contract.
 */
final class NewsSearchNativeSmokeTest {

    @Test
    @Timeout(180)
    void humanHappyPathRendersNumberedLfLinesWithoutAnsi() throws Exception {
        NewsScenarios.humanHappyPathRendersNumberedLfLinesWithoutAnsi(newsCommand());
    }

    @Test
    @Timeout(180)
    void jsonSuccessEnvelopeIsOneValidatedLfLine() throws Exception {
        NewsScenarios.jsonSuccessEnvelopeIsOneValidatedLfLine(newsCommand());
    }

    @Test
    @Timeout(180)
    void jsonlEmitsValidatedResultRecordsThenSummary() throws Exception {
        NewsScenarios.jsonlEmitsValidatedResultRecordsThenSummary(newsCommand());
    }

    @Test
    @Timeout(180)
    void rawOutputIsTheServedBodyBytesExactly() throws Exception {
        NewsScenarios.rawOutputIsTheServedBodyBytesExactly(newsCommand());
    }

    @Test
    @Timeout(180)
    void authenticationFailureExitsFourInHumanAndJsonModes() throws Exception {
        NewsScenarios.authenticationFailureExitsFourInHumanAndJsonModes(newsCommand());
    }

    @Test
    @Timeout(180)
    void usageFailureHappensBeforeAnyNetworkDispatch() throws Exception {
        NewsScenarios.usageFailureHappensBeforeAnyNetworkDispatch(newsCommand());
    }

    @Test
    @Timeout(180)
    void loopbackRunsSendTheTestKeyAndNeverStoredCredentials() throws Exception {
        NewsScenarios.loopbackRunsSendTheTestKeyAndNeverStoredCredentials(newsCommand());
    }

    @Test
    @Timeout(180)
    void missingLoopbackTestKeyKeepsTheLocalConfigurationExit() throws Exception {
        NewsScenarios.missingLoopbackTestKeyKeepsTheLocalConfigurationExit(newsCommand());
    }

    @Test
    @Timeout(180)
    void allPagesWalksExactlyTheTenDocumentedPages() throws Exception {
        NewsScenarios.allPagesWalksExactlyTheTenDocumentedPages(newsCommand());
    }

    @Test
    @Timeout(180)
    void maxPagesBoundsTheWalkToItsBudget() throws Exception {
        NewsScenarios.maxPagesBoundsTheWalkToItsBudget(newsCommand());
    }

    @Test
    @Timeout(180)
    void pagedWalkDeduplicatesOverlappingResultsAcrossPages() throws Exception {
        NewsScenarios.pagedWalkDeduplicatesOverlappingResultsAcrossPages(newsCommand());
    }

    @Test
    @Timeout(180)
    void pagedJsonlKeepsPageOneRecordsBeforeTheLaterPageError() throws Exception {
        NewsScenarios.pagedJsonlKeepsPageOneRecordsBeforeTheLaterPageError(newsCommand());
    }

    @Test
    @Timeout(180)
    void pagedJsonlBrokenPipeMidWalkIsSilentZero() throws Exception {
        NewsScenarios.pagedJsonlBrokenPipeMidWalkIsSilentZero(newsCommand());
    }

    @Test
    @Timeout(180)
    void sigintDuringPacedWalkExits130() throws Exception {
        NewsScenarios.sigintDuringPacedWalkExits130(newsCommand());
    }

    @Test
    @Timeout(180)
    void apiVersionPinReachesTheWireAndImpossibleDatesFailUsage() throws Exception {
        NewsScenarios.apiVersionPinReachesTheWireAndImpossibleDatesFailUsage(newsCommand());
    }

    private static List<String> newsCommand() {
        String binaryProperty = System.getProperty("brave.search.native.binary");
        assertNotNull(binaryProperty, "brave.search.native.binary must be injected by the build");
        Path binary = Path.of(binaryProperty);
        assertTrue(Files.isRegularFile(binary), () -> "native binary missing: " + binary);
        assertTrue(Files.isExecutable(binary), () -> "native binary not executable: " + binary);
        return List.of(binary.toString(), NewsSearchCommand.NAME);
    }
}

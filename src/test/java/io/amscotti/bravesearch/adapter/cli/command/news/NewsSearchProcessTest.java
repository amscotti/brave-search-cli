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
 * Whole-process evidence for the news command against the JVM executable produced by {@code
 * installDist}: the launcher script, the real credential routing, the real stdout pipe, and
 * the real exit statuses, all against a scripted loopback server with an isolated environment.
 */
final class NewsSearchProcessTest {

    @Test
    @Timeout(120)
    void humanHappyPathRendersNumberedLfLinesWithoutAnsi() throws Exception {
        NewsScenarios.humanHappyPathRendersNumberedLfLinesWithoutAnsi(newsCommand());
    }

    @Test
    @Timeout(120)
    void jsonSuccessEnvelopeIsOneValidatedLfLine() throws Exception {
        NewsScenarios.jsonSuccessEnvelopeIsOneValidatedLfLine(newsCommand());
    }

    @Test
    @Timeout(120)
    void jsonlEmitsValidatedResultRecordsThenSummary() throws Exception {
        NewsScenarios.jsonlEmitsValidatedResultRecordsThenSummary(newsCommand());
    }

    @Test
    @Timeout(120)
    void rawOutputIsTheServedBodyBytesExactly() throws Exception {
        NewsScenarios.rawOutputIsTheServedBodyBytesExactly(newsCommand());
    }

    @Test
    @Timeout(120)
    void authenticationFailureExitsFourInHumanAndJsonModes() throws Exception {
        NewsScenarios.authenticationFailureExitsFourInHumanAndJsonModes(newsCommand());
    }

    @Test
    @Timeout(120)
    void usageFailureHappensBeforeAnyNetworkDispatch() throws Exception {
        NewsScenarios.usageFailureHappensBeforeAnyNetworkDispatch(newsCommand());
    }

    @Test
    @Timeout(120)
    void loopbackRunsSendTheTestKeyAndNeverStoredCredentials() throws Exception {
        NewsScenarios.loopbackRunsSendTheTestKeyAndNeverStoredCredentials(newsCommand());
    }

    @Test
    @Timeout(120)
    void missingLoopbackTestKeyKeepsTheLocalConfigurationExit() throws Exception {
        NewsScenarios.missingLoopbackTestKeyKeepsTheLocalConfigurationExit(newsCommand());
    }

    @Test
    @Timeout(120)
    void allPagesWalksExactlyTheTenDocumentedPages() throws Exception {
        NewsScenarios.allPagesWalksExactlyTheTenDocumentedPages(newsCommand());
    }

    @Test
    @Timeout(120)
    void maxPagesBoundsTheWalkToItsBudget() throws Exception {
        NewsScenarios.maxPagesBoundsTheWalkToItsBudget(newsCommand());
    }

    @Test
    @Timeout(120)
    void pagedWalkDeduplicatesOverlappingResultsAcrossPages() throws Exception {
        NewsScenarios.pagedWalkDeduplicatesOverlappingResultsAcrossPages(newsCommand());
    }

    @Test
    @Timeout(120)
    void pagedJsonlKeepsPageOneRecordsBeforeTheLaterPageError() throws Exception {
        NewsScenarios.pagedJsonlKeepsPageOneRecordsBeforeTheLaterPageError(newsCommand());
    }

    @Test
    @Timeout(120)
    void pagedJsonlBrokenPipeMidWalkIsSilentZero() throws Exception {
        NewsScenarios.pagedJsonlBrokenPipeMidWalkIsSilentZero(newsCommand());
    }

    @Test
    @Timeout(120)
    void sigintDuringPacedWalkExits130() throws Exception {
        NewsScenarios.sigintDuringPacedWalkExits130(newsCommand());
    }

    @Test
    @Timeout(120)
    void apiVersionPinReachesTheWireAndImpossibleDatesFailUsage() throws Exception {
        NewsScenarios.apiVersionPinReachesTheWireAndImpossibleDatesFailUsage(newsCommand());
    }

    private static List<String> newsCommand() {
        String launcherProperty = System.getProperty("brave.search.jvm.launcher");
        assertNotNull(launcherProperty, "brave.search.jvm.launcher must be injected by the build");
        Path launcher = Path.of(launcherProperty);
        assertTrue(Files.isRegularFile(launcher), () -> "installed launcher missing: " + launcher);
        assertTrue(Files.isExecutable(launcher), () -> "installed launcher not executable: " + launcher);
        return List.of(launcher.toString(), NewsSearchCommand.NAME);
    }
}

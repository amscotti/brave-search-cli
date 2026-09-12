package io.amscotti.bravesearch.adapter.cli.command.answers;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.testsupport.AnswersScenarios;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Whole-process evidence for the answers command against the JVM executable produced by
 * {@code installDist}: the launcher script, the real credential routing, the real stdout
 * pipe, and the real exit statuses, all against a scripted loopback server with an
 * isolated environment.
 */
final class AnswersProcessTest {

    @Test
    @Timeout(120)
    void humanHappyPathRendersTheAnswerAndUsageLineWithoutAnsi() throws Exception {
        AnswersScenarios.humanHappyPathRendersTheAnswerAndUsageLineWithoutAnsi(answersCommand());
    }

    @Test
    @Timeout(120)
    void jsonEnvelopeIsOneValidatedLfLine() throws Exception {
        AnswersScenarios.jsonEnvelopeIsOneValidatedLfLine(answersCommand());
    }

    @Test
    @Timeout(120)
    void blockingCitationsRenderInTheHumanDocument() throws Exception {
        AnswersScenarios.blockingCitationsRenderInTheHumanDocument(answersCommand());
    }

    @Test
    @Timeout(120)
    void rawOutputIsTheServedBodyBytesExactly() throws Exception {
        AnswersScenarios.rawOutputIsTheServedBodyBytesExactly(answersCommand());
    }

    @Test
    @Timeout(120)
    void theBlockingRequestBodyTravelsNestedAndExact() throws Exception {
        AnswersScenarios.theBlockingRequestBodyTravelsNestedAndExact(answersCommand());
    }

    @Test
    @Timeout(120)
    void authenticationFailureExitsFourInHumanAndJsonModes() throws Exception {
        AnswersScenarios.authenticationFailureExitsFourInHumanAndJsonModes(answersCommand());
    }

    @Test
    @Timeout(120)
    void usageFailuresHappenBeforeAnyNetworkDispatch() throws Exception {
        AnswersScenarios.usageFailuresHappenBeforeAnyNetworkDispatch(answersCommand());
    }


    @Test
    @Timeout(120)
    void streamedOutputPrecedesServerCompletionInEveryMode() throws Exception {
        AnswersScenarios.streamedOutputPrecedesServerCompletionInEveryMode(answersCommand());
    }

    @Test
    @Timeout(120)
    void theFullHappyStreamMatchesEveryModeContract() throws Exception {
        AnswersScenarios.theFullHappyStreamMatchesEveryModeContract(answersCommand());
    }

    @Test
    @Timeout(120)
    void upstreamEventsPreserveTheSseEnvelopeMetadata() throws Exception {
        AnswersScenarios.upstreamEventsPreserveTheSseEnvelopeMetadata(answersCommand());
    }

    @Test
    @Timeout(120)
    void bufferedJsonStreamsOneEnvelopeWithTheAdvisory() throws Exception {
        AnswersScenarios.bufferedJsonStreamsOneEnvelopeWithTheAdvisory(answersCommand());
    }

    @Test
    @Timeout(120)
    void researchStreamsProgressRecordsAndNestedWireOptions() throws Exception {
        AnswersScenarios.researchStreamsProgressRecordsAndNestedWireOptions(answersCommand());
    }

    @Test
    @Timeout(120)
    void interruptingMidStreamExitsOneThirtyWithPartialCounts() throws Exception {
        AnswersScenarios.interruptingMidStreamExitsOneThirtyWithPartialCounts(answersCommand());
    }

    @Test
    @Timeout(120)
    void terminatingMidStreamExitsOneFortyThreeWithTheTerminalRecord() throws Exception {
        AnswersScenarios.terminatingMidStreamExitsOneFortyThreeWithTheTerminalRecord(answersCommand());
    }

    @Test
    @Timeout(120)
    void aConsumerClosingBeforeTheFirstReadExitsZeroSilently() throws Exception {
        AnswersScenarios.aConsumerClosingBeforeTheFirstReadExitsZeroSilently(answersCommand());
    }

    @Test
    @Timeout(120)
    void closingTheDownstreamPipeMidStreamIsSilentZero() throws Exception {
        AnswersScenarios.closingTheDownstreamPipeMidStreamIsSilentZero(answersCommand());
    }

    @Test
    @Timeout(120)
    void anIdleStreamIsCutByItsDeadline() throws Exception {
        AnswersScenarios.anIdleStreamIsCutByItsDeadline(answersCommand());
    }

    @Test
    @Timeout(120)
    void aWallDeadlineCutsAHeartbeatingStream() throws Exception {
        AnswersScenarios.aWallDeadlineCutsAHeartbeatingStream(answersCommand());
    }

    @Test
    @Timeout(120)
    void anAbruptEndWithoutUsageIsATransportFailureWithUnknownCost() throws Exception {
        AnswersScenarios.anAbruptEndWithoutUsageIsATransportFailureWithUnknownCost(answersCommand());
    }

    @Test
    @Timeout(120)
    void authenticationFailureAtStreamOpenExitsFour() throws Exception {
        AnswersScenarios.authenticationFailureAtStreamOpenExitsFour(answersCommand());
    }

    @Test
    @Timeout(120)
    void malformedDecodeFailuresKeepExitEight() throws Exception {
        AnswersScenarios.malformedDecodeFailuresKeepExitEight(answersCommand());
    }

    @Test
    @Timeout(120)
    void theBlockingTotalDeadlineNeverCutsAStream() throws Exception {
        AnswersScenarios.theBlockingTotalDeadlineNeverCutsAStream(answersCommand());
    }

    @Test
    @Timeout(120)
    void aSlowConsumerStillReceivesTheWholeStream() throws Exception {
        AnswersScenarios.aSlowConsumerStillReceivesTheWholeStream(answersCommand());
    }

    @Test
    @Timeout(120)
    void loopbackRunsSendTheTestKeyOnTheBlockingPost() throws Exception {
        AnswersScenarios.loopbackRunsSendTheTestKeyOnTheBlockingPost(answersCommand());
    }

    @Test
    @Timeout(120)
    void apiVersionPinReachesTheWireAndImpossibleDatesFailUsage() throws Exception {
        AnswersScenarios.apiVersionPinReachesTheWireAndImpossibleDatesFailUsage(answersCommand());
    }

    @Test
    @Timeout(120)
    void downstreamPipeCloseIsSilentZero() throws Exception {
        AnswersScenarios.downstreamPipeCloseIsSilentZero(answersCommand());
    }

    private static List<String> answersCommand() {
        String launcherProperty = System.getProperty("brave.search.jvm.launcher");
        assertNotNull(launcherProperty, "brave.search.jvm.launcher must be injected by the build");
        Path launcher = Path.of(launcherProperty);
        assertTrue(Files.isRegularFile(launcher), () -> "installed launcher missing: " + launcher);
        assertTrue(Files.isExecutable(launcher), () -> "installed launcher not executable: " + launcher);
        return List.of(launcher.toString(), AnswersCommand.NAME);
    }
}

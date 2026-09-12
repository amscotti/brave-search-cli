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
 * The same whole-process answers command evidence as the JVM process suite, run against
 * the exact native executable produced by {@code nativeCompile}: every success shape, the
 * exact nested POST body bytes, the failure and usage contracts, the credential routing
 * under a decoy environment, the Api-Version pin, and the silent early termination must
 * all agree with the JVM's behavior, and the streaming family must agree everywhere:
 * output before server completion, every per-mode contract, the buffered envelope, the
 * nested research wire form, the interrupt exit, the pipe-close zero, both deadlines,
 * the abrupt-end cost-unknown rule, and the malformed-decode exits — the native smoke
 * suite is the loopback-agreeing subset of the JVM-pinned scenarios, bounded by its
 * source set, not a narrower contract.
 */
final class AnswersNativeSmokeTest {

    @Test
    @Timeout(180)
    void humanHappyPathRendersTheAnswerAndUsageLineWithoutAnsi() throws Exception {
        AnswersScenarios.humanHappyPathRendersTheAnswerAndUsageLineWithoutAnsi(answersCommand());
    }

    @Test
    @Timeout(180)
    void jsonEnvelopeIsOneValidatedLfLine() throws Exception {
        AnswersScenarios.jsonEnvelopeIsOneValidatedLfLine(answersCommand());
    }

    @Test
    @Timeout(180)
    void blockingCitationsRenderInTheHumanDocument() throws Exception {
        AnswersScenarios.blockingCitationsRenderInTheHumanDocument(answersCommand());
    }

    @Test
    @Timeout(180)
    void rawOutputIsTheServedBodyBytesExactly() throws Exception {
        AnswersScenarios.rawOutputIsTheServedBodyBytesExactly(answersCommand());
    }

    @Test
    @Timeout(180)
    void theBlockingRequestBodyTravelsNestedAndExact() throws Exception {
        AnswersScenarios.theBlockingRequestBodyTravelsNestedAndExact(answersCommand());
    }

    @Test
    @Timeout(180)
    void authenticationFailureExitsFourInHumanAndJsonModes() throws Exception {
        AnswersScenarios.authenticationFailureExitsFourInHumanAndJsonModes(answersCommand());
    }

    @Test
    @Timeout(180)
    void usageFailuresHappenBeforeAnyNetworkDispatch() throws Exception {
        AnswersScenarios.usageFailuresHappenBeforeAnyNetworkDispatch(answersCommand());
    }


    @Test
    @Timeout(180)
    void streamedOutputPrecedesServerCompletionInEveryMode() throws Exception {
        AnswersScenarios.streamedOutputPrecedesServerCompletionInEveryMode(answersCommand());
    }

    @Test
    @Timeout(180)
    void theFullHappyStreamMatchesEveryModeContract() throws Exception {
        AnswersScenarios.theFullHappyStreamMatchesEveryModeContract(answersCommand());
    }

    @Test
    @Timeout(180)
    void upstreamEventsPreserveTheSseEnvelopeMetadata() throws Exception {
        AnswersScenarios.upstreamEventsPreserveTheSseEnvelopeMetadata(answersCommand());
    }

    @Test
    @Timeout(180)
    void bufferedJsonStreamsOneEnvelopeWithTheAdvisory() throws Exception {
        AnswersScenarios.bufferedJsonStreamsOneEnvelopeWithTheAdvisory(answersCommand());
    }

    @Test
    @Timeout(180)
    void researchStreamsProgressRecordsAndNestedWireOptions() throws Exception {
        AnswersScenarios.researchStreamsProgressRecordsAndNestedWireOptions(answersCommand());
    }

    @Test
    @Timeout(180)
    void interruptingMidStreamExitsOneThirtyWithPartialCounts() throws Exception {
        AnswersScenarios.interruptingMidStreamExitsOneThirtyWithPartialCounts(answersCommand());
    }

    @Test
    @Timeout(180)
    void terminatingMidStreamExitsOneFortyThreeWithTheTerminalRecord() throws Exception {
        AnswersScenarios.terminatingMidStreamExitsOneFortyThreeWithTheTerminalRecord(answersCommand());
    }

    @Test
    @Timeout(180)
    void aConsumerClosingBeforeTheFirstReadExitsZeroSilently() throws Exception {
        AnswersScenarios.aConsumerClosingBeforeTheFirstReadExitsZeroSilently(answersCommand());
    }

    @Test
    @Timeout(180)
    void closingTheDownstreamPipeMidStreamIsSilentZero() throws Exception {
        AnswersScenarios.closingTheDownstreamPipeMidStreamIsSilentZero(answersCommand());
    }

    @Test
    @Timeout(180)
    void anIdleStreamIsCutByItsDeadline() throws Exception {
        AnswersScenarios.anIdleStreamIsCutByItsDeadline(answersCommand());
    }

    @Test
    @Timeout(180)
    void aWallDeadlineCutsAHeartbeatingStream() throws Exception {
        AnswersScenarios.aWallDeadlineCutsAHeartbeatingStream(answersCommand());
    }

    @Test
    @Timeout(180)
    void anAbruptEndWithoutUsageIsATransportFailureWithUnknownCost() throws Exception {
        AnswersScenarios.anAbruptEndWithoutUsageIsATransportFailureWithUnknownCost(answersCommand());
    }

    @Test
    @Timeout(180)
    void authenticationFailureAtStreamOpenExitsFour() throws Exception {
        AnswersScenarios.authenticationFailureAtStreamOpenExitsFour(answersCommand());
    }

    @Test
    @Timeout(180)
    void malformedDecodeFailuresKeepExitEight() throws Exception {
        AnswersScenarios.malformedDecodeFailuresKeepExitEight(answersCommand());
    }

    @Test
    @Timeout(180)
    void theBlockingTotalDeadlineNeverCutsAStream() throws Exception {
        AnswersScenarios.theBlockingTotalDeadlineNeverCutsAStream(answersCommand());
    }

    @Test
    @Timeout(180)
    void aSlowConsumerStillReceivesTheWholeStream() throws Exception {
        AnswersScenarios.aSlowConsumerStillReceivesTheWholeStream(answersCommand());
    }

    @Test
    @Timeout(180)
    void loopbackRunsSendTheTestKeyOnTheBlockingPost() throws Exception {
        AnswersScenarios.loopbackRunsSendTheTestKeyOnTheBlockingPost(answersCommand());
    }

    @Test
    @Timeout(180)
    void apiVersionPinReachesTheWireAndImpossibleDatesFailUsage() throws Exception {
        AnswersScenarios.apiVersionPinReachesTheWireAndImpossibleDatesFailUsage(answersCommand());
    }

    @Test
    @Timeout(180)
    void downstreamPipeCloseIsSilentZero() throws Exception {
        AnswersScenarios.downstreamPipeCloseIsSilentZero(answersCommand());
    }

    private static List<String> answersCommand() {
        String binaryProperty = System.getProperty("brave.search.native.binary");
        assertNotNull(binaryProperty, "brave.search.native.binary must be injected by the build");
        Path binary = Path.of(binaryProperty);
        assertTrue(Files.isRegularFile(binary), () -> "native binary missing: " + binary);
        assertTrue(Files.isExecutable(binary), () -> "native binary not executable: " + binary);
        return List.of(binary.toString(), AnswersCommand.NAME);
    }
}

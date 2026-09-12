package io.amscotti.bravesearch.adapter.cli.command.context;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.testsupport.ContextScenarios;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The same whole-process context command evidence as the JVM process suite, run against the
 * exact native executable produced by {@code nativeCompile} and mirroring every JVM-pinned
 * scenario, not a narrower contract: the human context document, the schema-valid machine
 * envelopes and records, raw byte passthrough, the authentication failure and usage
 * contracts, the full option set on the wire, and the loopback credential routing under a
 * decoy environment must all agree with the JVM's behavior.
 */
final class ContextNativeSmokeTest {

    @Test
    @Timeout(180)
    void humanHappyPathRendersTheContextDocumentLfOnlyWithoutAnsi() throws Exception {
        ContextScenarios.humanHappyPathRendersTheContextDocumentLfOnlyWithoutAnsi(contextCommand());
    }

    @Test
    @Timeout(180)
    void jsonEnvelopeIsOneValidatedLfLineWithCountsAndLosslessUpstream() throws Exception {
        ContextScenarios.jsonEnvelopeIsOneValidatedLfLineWithCountsAndLosslessUpstream(contextCommand());
    }

    @Test
    @Timeout(180)
    void jsonlEmitsOneContextRecordThenTheSummary() throws Exception {
        ContextScenarios.jsonlEmitsOneContextRecordThenTheSummary(contextCommand());
    }

    @Test
    @Timeout(180)
    void rawOutputIsTheServedBodyBytesExactly() throws Exception {
        ContextScenarios.rawOutputIsTheServedBodyBytesExactly(contextCommand());
    }

    @Test
    @Timeout(180)
    void authenticationFailureExitsFourInHumanAndJsonModes() throws Exception {
        ContextScenarios.authenticationFailureExitsFourInHumanAndJsonModes(contextCommand());
    }

    @Test
    @Timeout(180)
    void usageFailureHappensBeforeAnyNetworkDispatch() throws Exception {
        ContextScenarios.usageFailureHappensBeforeAnyNetworkDispatch(contextCommand());
    }

    @Test
    @Timeout(180)
    void loopbackRunSendsTheTestKeyAndExactlyOneRequest() throws Exception {
        ContextScenarios.loopbackRunSendsTheTestKeyAndExactlyOneRequest(contextCommand());
    }

    @Test
    @Timeout(180)
    void fullOptionsReachTheWireUnderTheirDocumentedNames() throws Exception {
        ContextScenarios.fullOptionsReachTheWireUnderTheirDocumentedNames(contextCommand());
    }

    @Test
    @Timeout(180)
    void sigintDuringBlockedBodyExits130() throws Exception {
        ContextScenarios.sigintDuringBlockedBodyExits130(contextCommand());
    }

    @Test
    @Timeout(180)
    void sigtermDuringBlockedBodyExits143() throws Exception {
        ContextScenarios.sigtermDuringBlockedBodyExits143(contextCommand());
    }

    @Test
    @Timeout(180)
    void signalAfterCompletionKeepsZero() throws Exception {
        ContextScenarios.signalAfterCompletionKeepsZero(contextCommand());
    }

    @Test
    @Timeout(180)
    void downstreamPipeCloseIsSilentZero() throws Exception {
        ContextScenarios.downstreamPipeCloseIsSilentZero(contextCommand());
    }

    @Test
    @Timeout(180)
    void garbageSuccessBodyExitsEight() throws Exception {
        ContextScenarios.garbageSuccessBodyExitsEight(contextCommand());
    }

    private static List<String> contextCommand() {
        String binaryProperty = System.getProperty("brave.search.native.binary");
        assertNotNull(binaryProperty, "brave.search.native.binary must be injected by the build");
        Path binary = Path.of(binaryProperty);
        assertTrue(Files.isRegularFile(binary), () -> "native binary missing: " + binary);
        assertTrue(Files.isExecutable(binary), () -> "native binary not executable: " + binary);
        return List.of(binary.toString(), ContextCommand.NAME);
    }
}

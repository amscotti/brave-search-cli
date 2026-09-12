package io.amscotti.bravesearch.adapter.cli.command.context;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.testsupport.ContextScenarios;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Whole-process evidence for the context command against the JVM executable produced by
 * {@code installDist}: the launcher script, the real credential routing, the real stdout
 * pipe, and the real exit statuses, all against a scripted loopback server with an isolated
 * environment.
 */
final class ContextProcessTest {

    @Test
    @Timeout(120)
    void humanHappyPathRendersTheContextDocumentLfOnlyWithoutAnsi() throws Exception {
        ContextScenarios.humanHappyPathRendersTheContextDocumentLfOnlyWithoutAnsi(contextCommand());
    }

    @Test
    @Timeout(120)
    void jsonEnvelopeIsOneValidatedLfLineWithCountsAndLosslessUpstream() throws Exception {
        ContextScenarios.jsonEnvelopeIsOneValidatedLfLineWithCountsAndLosslessUpstream(contextCommand());
    }

    @Test
    @Timeout(120)
    void jsonlEmitsOneContextRecordThenTheSummary() throws Exception {
        ContextScenarios.jsonlEmitsOneContextRecordThenTheSummary(contextCommand());
    }

    @Test
    @Timeout(120)
    void rawOutputIsTheServedBodyBytesExactly() throws Exception {
        ContextScenarios.rawOutputIsTheServedBodyBytesExactly(contextCommand());
    }

    @Test
    @Timeout(120)
    void authenticationFailureExitsFourInHumanAndJsonModes() throws Exception {
        ContextScenarios.authenticationFailureExitsFourInHumanAndJsonModes(contextCommand());
    }

    @Test
    @Timeout(120)
    void usageFailureHappensBeforeAnyNetworkDispatch() throws Exception {
        ContextScenarios.usageFailureHappensBeforeAnyNetworkDispatch(contextCommand());
    }

    @Test
    @Timeout(120)
    void loopbackRunSendsTheTestKeyAndExactlyOneRequest() throws Exception {
        ContextScenarios.loopbackRunSendsTheTestKeyAndExactlyOneRequest(contextCommand());
    }

    @Test
    @Timeout(120)
    void fullOptionsReachTheWireUnderTheirDocumentedNames() throws Exception {
        ContextScenarios.fullOptionsReachTheWireUnderTheirDocumentedNames(contextCommand());
    }

    @Test
    @Timeout(120)
    void sigintDuringBlockedBodyExits130() throws Exception {
        ContextScenarios.sigintDuringBlockedBodyExits130(contextCommand());
    }

    @Test
    @Timeout(120)
    void sigtermDuringBlockedBodyExits143() throws Exception {
        ContextScenarios.sigtermDuringBlockedBodyExits143(contextCommand());
    }

    @Test
    @Timeout(120)
    void blockedBodyInterruptWaitsForTheCurrentInvocationToStart() throws Exception {
        List<String> delayedCommand = new ArrayList<>(List.of(
                "/bin/sh", "-c", "trap 'exit 130' INT; sleep 2; exec \"$@\"", "delayed-search"));
        delayedCommand.addAll(contextCommand());

        ContextScenarios.sigintDuringBlockedBodyExits130(delayedCommand);
    }

    @Test
    @Timeout(120)
    void signalAfterCompletionKeepsZero() throws Exception {
        ContextScenarios.signalAfterCompletionKeepsZero(contextCommand());
    }

    @Test
    @Timeout(120)
    void downstreamPipeCloseIsSilentZero() throws Exception {
        ContextScenarios.downstreamPipeCloseIsSilentZero(contextCommand());
    }

    @Test
    @Timeout(120)
    void garbageSuccessBodyExitsEight() throws Exception {
        ContextScenarios.garbageSuccessBodyExitsEight(contextCommand());
    }

    private static List<String> contextCommand() {
        String launcherProperty = System.getProperty("brave.search.jvm.launcher");
        assertNotNull(launcherProperty, "brave.search.jvm.launcher must be injected by the build");
        Path launcher = Path.of(launcherProperty);
        assertTrue(Files.isRegularFile(launcher), () -> "installed launcher missing: " + launcher);
        assertTrue(Files.isExecutable(launcher), () -> "installed launcher not executable: " + launcher);
        return List.of(launcher.toString(), ContextCommand.NAME);
    }
}

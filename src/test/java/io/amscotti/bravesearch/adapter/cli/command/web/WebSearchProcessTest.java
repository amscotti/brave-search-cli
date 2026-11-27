package io.amscotti.bravesearch.adapter.cli.command.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.testsupport.ProcessHarness;
import io.amscotti.bravesearch.testsupport.ScriptedSseServer;
import io.amscotti.bravesearch.testsupport.WebScenarios;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Whole-process evidence for the web command against the JVM executable produced by {@code
 * installDist}: the launcher script, the real credential routing, the real stdout pipe, and
 * the real exit statuses, all against a scripted loopback server with an isolated environment.
 */
final class WebSearchProcessTest {

    private static final Set<String> SCRUBBED = Set.of(
            "BRAVE_API_KEY",
            "BRAVE_SEARCH_API_KEY",
            "BRAVE_SEARCH_TEST_KEY",
            "BRAVE_SEARCH_DEBUG",
            "HTTPS_PROXY",
            "https_proxy",
            "NO_PROXY",
            "no_proxy",
            "XDG_CONFIG_HOME",
            "JAVA_OPTS",
            "NO_COLOR",
            "CLICOLOR",
            "CLICOLOR_FORCE",
            "COLUMNS",
            "TERM");

    @TempDir
    Path sandbox;

    @Test
    @Timeout(120)
    void humanHappyPathRendersNumberedLfLinesWithoutAnsi() throws Exception {
        WebScenarios.humanHappyPathRendersNumberedLfLinesWithoutAnsi(webCommand());
    }

    @Test
    @Timeout(120)
    void jsonSuccessEnvelopeIsOneValidatedLfLine() throws Exception {
        WebScenarios.jsonSuccessEnvelopeIsOneValidatedLfLine(webCommand());
    }

    @Test
    @Timeout(120)
    void jsonlEmitsValidatedResultRecordsThenSummary() throws Exception {
        WebScenarios.jsonlEmitsValidatedResultRecordsThenSummary(webCommand());
    }

    @Test
    @Timeout(120)
    void rawOutputIsTheServedBodyBytesExactly() throws Exception {
        WebScenarios.rawOutputIsTheServedBodyBytesExactly(webCommand());
    }

    @Test
    @Timeout(120)
    void authenticationFailureExitsFourInHumanAndJsonModes() throws Exception {
        WebScenarios.authenticationFailureExitsFourInHumanAndJsonModes(webCommand());
    }

    @Test
    @Timeout(120)
    void aRejectedSubscriptionTokenExitsFourInHumanAndJsonModes() throws Exception {
        WebScenarios.aRejectedSubscriptionTokenExitsFourInHumanAndJsonModes(webCommand());
    }

    @Test
    @Timeout(120)
    void rateLimitedJsonlErrorCarriesTheObservedWindows() throws Exception {
        WebScenarios.rateLimitedJsonlErrorCarriesTheObservedWindows(webCommand());
    }

    @Test
    @Timeout(120)
    void usageFailureHappensBeforeAnyNetworkDispatch() throws Exception {
        WebScenarios.usageFailureHappensBeforeAnyNetworkDispatch(webCommand());
    }

    @Test
    @Timeout(120)
    void usageFailureWithOutputFlagHappensBeforeRenderingOrDispatch() throws Exception {
        String testKey = "test-" + UUID.randomUUID().toString().replace("-", "");
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .writeBytes("{}".getBytes(UTF_8))
                .start()) {
            List<List<String>> invalidInvocations = List.of(
                    List.of("--output", "json", "--count", "21", "three word query"),
                    List.of("--output", "jsonl", "--count", "21", "three word query"),
                    List.of("--output", "JSON", "three word query"));
            for (List<String> args : invalidInvocations) {
                List<String> full = new ArrayList<>(webCommand());
                full.add("--base-url");
                full.add(server.baseUrl().toString());
                full.addAll(args);

                ProcessHarness.ProcessResult result = new ProcessHarness()
                        .launch(full, childEnvironment(sandbox, testKey), new byte[0], SCRUBBED);

                assertEquals(2, result.exitStatus(), () -> "expected usage exit for " + args);
                assertEquals(
                        "",
                        new String(result.stdout(), UTF_8),
                        () -> "parse-time failures emit no machine document on stdout for " + args);
                String stderr = new String(result.stderr(), UTF_8);
                assertTrue(stderr.contains("Usage:"), () -> "usage text must appear on stderr for " + args + ": " + stderr);
                assertFalse(stderr.contains(testKey), "test key must never appear on stderr");
            }
            assertEquals(0, server.requests().size(), "no parse-failing invocation may dispatch a request");
        }
    }

    @Test
    @Timeout(120)
    void loopbackRunsSendTheTestKeyAndNeverStoredCredentials() throws Exception {
        WebScenarios.loopbackRunsSendTheTestKeyAndNeverStoredCredentials(webCommand());
    }

    @Test
    @Timeout(120)
    void missingLoopbackTestKeyKeepsTheLocalConfigurationExit() throws Exception {
        WebScenarios.missingLoopbackTestKeyKeepsTheLocalConfigurationExit(webCommand());
    }

    @Test
    @Timeout(120)
    void apiVersionPinReachesTheWireAndImpossibleDatesFailUsage() throws Exception {
        WebScenarios.apiVersionPinReachesTheWireAndImpossibleDatesFailUsage(webCommand());
    }

    @Test
    @Timeout(120)
    void downstreamPipeCloseIsSilentZero() throws Exception {
        WebScenarios.downstreamPipeCloseIsSilentZero(webCommand());
    }

    @Test
    @Timeout(120)
    void pagedJsonlKeepsPageOneRecordsBeforeTheLaterPageError() throws Exception {
        WebScenarios.pagedJsonlKeepsPageOneRecordsBeforeTheLaterPageError(webCommand());
    }

    @Test
    @Timeout(120)
    void pagedJsonlBrokenPipeMidWalkIsSilentZero() throws Exception {
        WebScenarios.pagedJsonlBrokenPipeMidWalkIsSilentZero(webCommand());
    }

    @Test
    @Timeout(120)
    void sigintDuringPacedWalkExits130() throws Exception {
        WebScenarios.sigintDuringPacedWalkExits130(webCommand());
    }

    @Test
    @Timeout(120)
    void sigtermDuringPacedWalkExits143() throws Exception {
        WebScenarios.sigtermDuringPacedWalkExits143(webCommand());
    }

    @Test
    @Timeout(120)
    void sigintDuringBlockedBodyExits130() throws Exception {
        WebScenarios.sigintDuringBlockedBodyExits130(webCommand());
    }

    @Test
    @Timeout(120)
    void sigtermDuringBlockedBodyExits143() throws Exception {
        WebScenarios.sigtermDuringBlockedBodyExits143(webCommand());
    }

    @Test
    @Timeout(120)
    void upstreamTerminalControlsNeverReachHumanOutput() throws Exception {
        WebScenarios.upstreamTerminalControlsNeverReachHumanOutput(webCommand());
    }

    @Test
    @Timeout(120)
    void signalAfterCompletionKeepsZero() throws Exception {
        WebScenarios.signalAfterCompletionKeepsZero(webCommand());
    }

    @Test
    @Timeout(120)
    void repeatedSigintAndSigtermTerminateImmediatelyWithSignalExitStatus() throws Exception {
        assertRepeatedSignalTerminatesImmediately("INT", 130);
        assertRepeatedSignalTerminatesImmediately("TERM", 143);
    }

    private void assertRepeatedSignalTerminatesImmediately(String signal, int expectedExit) throws Exception {
        String testKey = "test-" + UUID.randomUUID().toString().replace("-", "");
        // 2 MiB far exceeds the OS stdout pipe buffer, so once the child writes its first byte
        // and the parent stops draining stdout, the child blocks inside write(1, ...) while its
        // exchange cancellation context is still registered — letting the first signal latch
        // without exiting and proving the second signal terminates immediately.
        byte[] largeBody = ("{\"data\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}").getBytes(UTF_8);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .writeBytes(largeBody)
                .start()) {
            List<String> full = new ArrayList<>(webCommand());
            full.add("--base-url");
            full.add(server.baseUrl().toString());
            full.addAll(List.of("--output", "raw", "three word query"));
            try (ProcessHarness.Session session =
                    new ProcessHarness().start(full, childEnvironment(sandbox, testKey), SCRUBBED)) {
                int firstByte = session.stdout().read();
                assertTrue(firstByte >= 0, "the child must begin writing the raw body before the first signal");

                session.signal(signal);
                assertFalse(
                        session.awaitExit(Duration.ofMillis(500)),
                        "the first " + signal + " latches the live exchange rather than terminating immediately");

                session.signal(signal);
                assertTrue(
                        session.awaitExit(Duration.ofSeconds(10)),
                        "the repeated " + signal + " must terminate the child immediately");
                assertEquals(
                        expectedExit,
                        session.exitValue(),
                        () -> "the repeated " + signal + " keeps its conventional exit status, stderr=<"
                                + new String(session.stderr(), UTF_8) + ">");
            }
        }
    }

    private static Map<String, String> childEnvironment(Path home, String testKey) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("HOME", home.toString());
        environment.put("XDG_CONFIG_HOME", home.resolve("xdg").toString());
        environment.put("BRAVE_SEARCH_TEST_KEY", testKey);
        return environment;
    }

    private static List<String> webCommand() {
        String launcherProperty = System.getProperty("brave.search.jvm.launcher");
        assertNotNull(launcherProperty, "brave.search.jvm.launcher must be injected by the build");
        Path launcher = Path.of(launcherProperty);
        assertTrue(Files.isRegularFile(launcher), () -> "installed launcher missing: " + launcher);
        assertTrue(Files.isExecutable(launcher), () -> "installed launcher not executable: " + launcher);
        return List.of(launcher.toString(), WebSearchCommand.NAME);
    }
}

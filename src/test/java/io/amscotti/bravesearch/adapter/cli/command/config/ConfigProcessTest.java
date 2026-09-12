package io.amscotti.bravesearch.adapter.cli.command.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.testsupport.ConfigScenarios;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Whole-process evidence for the config commands against the installed JVM launcher with an
 * isolated home and scrubbed credential environment: the one-line stdin contract stores an
 * owner-only file that show reports without ever carrying token material, a missing console
 * and stdin keeps the configuration exit status, garbage input leaves the prior file
 * byte-intact, and the environment sources shadow the file as documented. The scenario
 * bodies are the shared {@link ConfigScenarios}, so the native smoke suite runs the same
 * evidence against the ahead-of-time executable; the misconfigured-XDG scenarios stay
 * JVM-pinned here because they force the Linux classification through the launcher's
 * {@code JAVA_OPTS} flags, a lever only the JVM honors — a Linux-classified native image
 * proves them from its baked operating-system name and the real environment.
 */
final class ConfigProcessTest {

    @TempDir
    Path home;

    @Test
    @Timeout(120)
    void setKeyStdinWritesAnOwnerOnlyFileAndShowReportsTheFileSource() throws IOException {
        ConfigScenarios.setKeyStdinWritesAnOwnerOnlyFileAndShowReportsTheFileSource(
                launcherCommand(), isolatedEnvironment(), expectedConfigPath());
    }

    @Test
    @Timeout(120)
    void showOutputJsonEmitsOneMachineRecordAndRejectsTheLineChannels() throws IOException {
        ConfigScenarios.showOutputJsonEmitsOneMachineRecordAndRejectsTheLineChannels(
                launcherCommand(), isolatedEnvironment(), expectedConfigPath());
    }

    @Test
    @Timeout(120)
    void setKeyWithoutAConsoleAndWithoutStdinExitsThree() throws IOException {
        ConfigScenarios.setKeyWithoutAConsoleAndWithoutStdinExitsThree(
                launcherCommand(), isolatedEnvironment(), expectedConfigPath());
    }

    @Test
    @Timeout(120)
    void setKeyStdinGarbageFailsAndLeavesThePriorConfigByteIntact() throws IOException {
        ConfigScenarios.setKeyStdinGarbageFailsAndLeavesThePriorConfigByteIntact(
                launcherCommand(), isolatedEnvironment(), expectedConfigPath());
    }

    @Test
    @Timeout(120)
    void environmentSourcesShadowTheStoredFile() throws IOException {
        ConfigScenarios.environmentSourcesShadowTheStoredFile(
                launcherCommand(), isolatedEnvironment(), expectedConfigPath());
    }

    @Test
    @Timeout(120)
    void misconfiguredXdgExitsThreeWithOneCleanStderrLineAndNoStackTrace() throws IOException {
        ConfigScenarios.misconfiguredXdgExitsThreeWithOneCleanStderrLineAndNoStackTrace(
                launcherCommand(), misconfiguredLinuxEnvironment());
    }

    @Test
    @Timeout(120)
    void versionStillRunsUnderAMisconfiguredCredentialEnvironment() throws IOException {
        ConfigScenarios.versionStillRunsUnderAMisconfiguredCredentialEnvironment(
                launcherCommand(), misconfiguredLinuxEnvironment());
    }

    /** The installed launcher tokens every scenario appends its subcommand to. */
    private static List<String> launcherCommand() {
        String launcherProperty = System.getProperty("brave.search.jvm.launcher");
        assertNotNull(launcherProperty, "brave.search.jvm.launcher must be injected by the build");
        Path launcher = Path.of(launcherProperty);
        assertTrue(Files.isRegularFile(launcher), () -> "installed launcher missing: " + launcher);
        return List.of(launcher.toString());
    }

    /**
     * An isolated child environment: no inherited credential variables, and both {@code HOME}
     * and the JVM's {@code user.home} pointed at this test's temporary home, so the platform
     * path rules resolve inside the sandbox the test observes.
     */
    private Map<String, String> isolatedEnvironment() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("HOME", home.toString());
        environment.put("JAVA_OPTS", "-Duser.home=" + home);
        return environment;
    }

    /**
     * A Linux-classified child whose {@code XDG_CONFIG_HOME} points at a relative path: the
     * credential-file location is unusable, and nothing else about the process is broken.
     */
    private Map<String, String> misconfiguredLinuxEnvironment() {
        Map<String, String> environment = isolatedEnvironment();
        environment.put("JAVA_OPTS", "-Duser.home=" + home + " -Dos.name=Linux");
        environment.put("XDG_CONFIG_HOME", "relative-config-home");
        return environment;
    }

    /** The platform credential-file path the child resolves for this test's home. */
    private Path expectedConfigPath() {
        String platform = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (platform.startsWith("mac")) {
            return home.resolve("Library/Application Support/brave-search/config.json");
        }
        return home.resolve(".config/brave-search/config.json");
    }
}

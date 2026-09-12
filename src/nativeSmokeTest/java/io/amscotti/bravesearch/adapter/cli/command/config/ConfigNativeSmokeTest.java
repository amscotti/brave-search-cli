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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The same whole-process config evidence as the JVM process suite, run against the exact
 * native executable produced by {@code nativeCompile}, within the one boundary the substrate
 * draws: the image runtime resolves the user's home from the operating-system account — a
 * redirected {@code HOME} is ignored, and the baked operating-system name fixes the platform
 * classification — so the store's scenarios run only where an environment lever points the
 * credential file at a test-owned path. A Linux-classified image (built on a Linux host,
 * which is also the host that runs this suite) offers that lever through an absolute {@code
 * XDG_CONFIG_HOME}, so every JVM-pinned scenario is proven there: the owner-only credential
 * file with its POSIX permissions, the machine record, the refused no-console/no-stdin run,
 * the byte-intact prior file under garbage input, the environment sources shadowing the
 * file, the clean single-line misconfigured-XDG failure, and the version contract under the
 * broken environment. A mac-classified image ignores {@code XDG_CONFIG_HOME} by contract and
 * pins the credential path to the OS account's home, so those scenarios skip loudly rather
 * than write the operator's real credential file; the substrate-independent half of the
 * stdin contract — no console and no {@code --stdin} keeps the configuration exit status —
 * still runs everywhere, because it touches no path at all.
 */
final class ConfigNativeSmokeTest {

    @TempDir
    Path configHome;

    /** The image's baked classification matches the host that built and runs it. */
    private static boolean linuxClassifiedImage() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux");
    }

    @Test
    @Timeout(120)
    void setKeyStdinWritesAnOwnerOnlyFileAndShowReportsTheFileSource() throws IOException {
        assumeLinuxClassifiedImage();
        ConfigScenarios.setKeyStdinWritesAnOwnerOnlyFileAndShowReportsTheFileSource(
                binaryCommand(), redirectedEnvironment(), redirectedConfigPath());
    }

    @Test
    @Timeout(120)
    void showOutputJsonEmitsOneMachineRecordAndRejectsTheLineChannels() throws IOException {
        assumeLinuxClassifiedImage();
        ConfigScenarios.showOutputJsonEmitsOneMachineRecordAndRejectsTheLineChannels(
                binaryCommand(), redirectedEnvironment(), redirectedConfigPath());
    }

    @Test
    @Timeout(120)
    void setKeyWithoutAConsoleAndWithoutStdinExitsThree() throws IOException {
        if (linuxClassifiedImage()) {
            ConfigScenarios.setKeyWithoutAConsoleAndWithoutStdinExitsThree(
                    binaryCommand(), redirectedEnvironment(), redirectedConfigPath());
            return;
        }
        ConfigScenarios.setKeyWithoutAConsoleAndWithoutStdinExitsThree(binaryCommand(), redirectedEnvironment());
    }

    @Test
    @Timeout(120)
    void setKeyStdinGarbageFailsAndLeavesThePriorConfigByteIntact() throws IOException {
        assumeLinuxClassifiedImage();
        ConfigScenarios.setKeyStdinGarbageFailsAndLeavesThePriorConfigByteIntact(
                binaryCommand(), redirectedEnvironment(), redirectedConfigPath());
    }

    @Test
    @Timeout(120)
    void environmentSourcesShadowTheStoredFile() throws IOException {
        assumeLinuxClassifiedImage();
        ConfigScenarios.environmentSourcesShadowTheStoredFile(
                binaryCommand(), redirectedEnvironment(), redirectedConfigPath());
    }

    @Test
    @Timeout(120)
    void misconfiguredXdgExitsThreeWithOneCleanStderrLineAndNoStackTrace() throws IOException {
        assumeLinuxClassifiedImage();
        ConfigScenarios.misconfiguredXdgExitsThreeWithOneCleanStderrLineAndNoStackTrace(
                binaryCommand(), misconfiguredEnvironment());
    }

    @Test
    @Timeout(120)
    void versionStillRunsUnderAMisconfiguredCredentialEnvironment() throws IOException {
        assumeLinuxClassifiedImage();
        ConfigScenarios.versionStillRunsUnderAMisconfiguredCredentialEnvironment(
                binaryCommand(), misconfiguredEnvironment());
    }

    /**
     * The store's scenarios need a test-owned credential file, which only a Linux-classified
     * image can be pointed at; skipping loudly keeps the mac leg honest about what it cannot
     * prove rather than silently narrowing the contract.
     */
    private static void assumeLinuxClassifiedImage() {
        Assumptions.assumeTrue(
                linuxClassifiedImage(),
                "SKIPPED: this mac-classified image pins the credential-file path to the OS account's"
                        + " home with no environment lever, so the store's scenarios cannot run against a"
                        + " test-owned credential file");
    }

    /**
     * The child environment of every redirected scenario: an absolute {@code XDG_CONFIG_HOME}
     * points the credential file inside this test's temporary home, the lever a
     * Linux-classified image honors.
     */
    private Map<String, String> redirectedEnvironment() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("HOME", configHome.toString());
        environment.put("XDG_CONFIG_HOME", configHome.toString());
        return environment;
    }

    /**
     * A Linux-classified child whose {@code XDG_CONFIG_HOME} points at a relative path: the
     * credential-file location is unusable, and nothing else about the process is broken.
     */
    private Map<String, String> misconfiguredEnvironment() {
        Map<String, String> environment = redirectedEnvironment();
        environment.put("XDG_CONFIG_HOME", "relative-config-home");
        return environment;
    }

    /** The credential-file path the redirected environment resolves to. */
    private Path redirectedConfigPath() {
        return configHome.resolve("brave-search/config.json");
    }

    /** The native executable tokens every scenario appends its subcommand to. */
    private static List<String> binaryCommand() {
        String binaryProperty = System.getProperty("brave.search.native.binary");
        assertNotNull(binaryProperty, "brave.search.native.binary must be injected by the build");
        Path binary = Path.of(binaryProperty);
        assertTrue(Files.isRegularFile(binary), () -> "native binary missing: " + binary);
        assertTrue(Files.isExecutable(binary), () -> "native binary not executable: " + binary);
        return List.of(binary.toString());
    }
}

package io.amscotti.bravesearch.adapter.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.domain.config.LocalConfigException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Platform resolution of the v1 credential-file location: the native macOS path ignoring XDG
 * entirely, the absolute-XDG or dot-config default on Linux, and typed failures for a set-but
 * relative/blank XDG value, an unusable home directory, and unsupported platforms. Every input
 * is injected, so no test reads the process environment.
 */
final class ConfigPathsTest {

    private static final String HOME = "/home/tester";

    @Test
    void macOsResolvesTheNativeApplicationSupportPathAndIgnoresXdg() {
        ConfigPaths paths = new ConfigPaths(
                () -> "Mac OS X",
                () -> HOME,
                Map.of("XDG_CONFIG_HOME", "/tmp/someone-elses-config")::get);
        assertEquals(
                Path.of(HOME, "Library/Application Support/brave-search/config.json"),
                paths.credentialFilePath());
    }

    @Test
    void macOsNeverConsultsTheEnvironment() {
        List<String> queried = new ArrayList<>();
        ConfigPaths paths =
                new ConfigPaths(() -> "Mac OS X", () -> HOME, name -> {
                    queried.add(name);
                    return null;
                });
        paths.credentialFilePath();
        assertTrue(queried.isEmpty(), "macOS resolution must not read any environment variable: " + queried);
    }

    @Test
    void linuxHonorsAnAbsoluteXdgConfigHomeEvenWithoutAHomeDirectory() {
        ConfigPaths paths = new ConfigPaths(
                () -> "Linux", () -> null, Map.of("XDG_CONFIG_HOME", "/xdg/config")::get);
        assertEquals(Path.of("/xdg/config/brave-search/config.json"), paths.credentialFilePath());
    }

    @Test
    void linuxDefaultsToTheDotConfigDirectoryWithoutXdg() {
        ConfigPaths paths = new ConfigPaths(
                () -> "Linux", () -> HOME, new HashMap<String, String>()::get);
        assertEquals(Path.of(HOME, ".config/brave-search/config.json"), paths.credentialFilePath());
    }

    @Test
    void linuxRejectsARelativeXdgConfigHomeAsALocalConfigurationError() {
        ConfigPaths paths = new ConfigPaths(
                () -> "Linux", () -> HOME, Map.of("XDG_CONFIG_HOME", "relative/config")::get);
        LocalConfigException failure = assertThrows(LocalConfigException.class, paths::credentialFilePath);
        assertEquals(LocalConfigException.Reason.MISCONFIGURED_PATH, failure.reason());
        assertTrue(failure.getMessage().contains("XDG_CONFIG_HOME"), () -> failure.getMessage());
    }

    @Test
    void linuxRejectsABlankXdgConfigHomeAsALocalConfigurationError() {
        ConfigPaths blank = new ConfigPaths(
                () -> "Linux", () -> HOME, Map.of("XDG_CONFIG_HOME", "")::get);
        LocalConfigException blankFailure = assertThrows(LocalConfigException.class, blank::credentialFilePath);
        assertEquals(LocalConfigException.Reason.MISCONFIGURED_PATH, blankFailure.reason());

        ConfigPaths whitespace = new ConfigPaths(
                () -> "Linux", () -> HOME, Map.of("XDG_CONFIG_HOME", "   ")::get);
        assertEquals(
                LocalConfigException.Reason.MISCONFIGURED_PATH,
                assertThrows(LocalConfigException.class, whitespace::credentialFilePath).reason());
    }

    @Test
    void anUnsupportedPlatformIsALocalConfigurationError() {
        ConfigPaths paths = new ConfigPaths(() -> "Windows 11", () -> HOME, name -> null);
        LocalConfigException failure = assertThrows(LocalConfigException.class, paths::credentialFilePath);
        assertEquals(LocalConfigException.Reason.UNSUPPORTED_PLATFORM, failure.reason());
    }

    @Test
    void macOsRejectsAMissingHomeDirectoryAsALocalConfigurationError() {
        ConfigPaths missing = new ConfigPaths(() -> "Mac OS X", () -> null, name -> null);
        assertEquals(
                LocalConfigException.Reason.MISCONFIGURED_PATH,
                assertThrows(LocalConfigException.class, missing::credentialFilePath).reason());

        ConfigPaths blank = new ConfigPaths(() -> "Mac OS X", () -> "  ", name -> null);
        assertEquals(
                LocalConfigException.Reason.MISCONFIGURED_PATH,
                assertThrows(LocalConfigException.class, blank::credentialFilePath).reason());
    }

    @Test
    void macOsRejectsARelativeHomeDirectoryAsALocalConfigurationError() {
        ConfigPaths paths = new ConfigPaths(() -> "Mac OS X", () -> "relative/home", name -> null);
        LocalConfigException failure = assertThrows(LocalConfigException.class, paths::credentialFilePath);
        assertEquals(LocalConfigException.Reason.MISCONFIGURED_PATH, failure.reason());
    }

    @Test
    void hostNamesOutsideTheSupportedSetAreALocalConfigurationError() {
        for (String unsupported : new String[] {"Windows 11", "SunOS", ""}) {
            ConfigPaths paths = new ConfigPaths(() -> unsupported, () -> HOME, name -> null);
            assertEquals(
                    LocalConfigException.Reason.UNSUPPORTED_PLATFORM,
                    assertThrows(LocalConfigException.class, paths::credentialFilePath).reason(),
                    "name must be unsupported: " + unsupported);
        }
        ConfigPaths missing = new ConfigPaths(() -> null, () -> HOME, name -> null);
        assertEquals(
                LocalConfigException.Reason.UNSUPPORTED_PLATFORM,
                assertThrows(LocalConfigException.class, missing::credentialFilePath).reason());
    }

    @Test
    void macOsHostNamesClassifyCaseInsensitively() {
        for (String macName : new String[] {"Mac OS X", "macOS", "MACINTOSH"}) {
            ConfigPaths paths = new ConfigPaths(() -> macName, () -> HOME, name -> null);
            assertEquals(
                    Path.of(HOME, "Library/Application Support/brave-search/config.json"),
                    paths.credentialFilePath(),
                    "name must resolve to the native macOS path: " + macName);
        }
    }
}

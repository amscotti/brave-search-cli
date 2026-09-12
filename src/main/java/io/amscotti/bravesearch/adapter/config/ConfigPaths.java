package io.amscotti.bravesearch.adapter.config;

import io.amscotti.bravesearch.domain.config.LocalConfigException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Resolves the v1 credential-file location per platform.
 *
 * <p>macOS always uses the native {@code ${user.home}/Library/Application
 * Support/brave-search/config.json} path and ignores {@code XDG_CONFIG_HOME} even when it is
 * set. Linux uses {@code ${XDG_CONFIG_HOME}/brave-search/config.json} when the variable is set,
 * nonblank, and absolute, and otherwise defaults to {@code
 * ${user.home}/.config/brave-search/config.json}. A set-but-relative or blank {@code
 * XDG_CONFIG_HOME} is a typed configuration error rather than a silently ignored value: an
 * operator who pointed the variable somewhere expects that somewhere to be honored or reported,
 * never quietly bypassed.
 *
 * <p>The operating-system name, the home directory, and the environment lookup are injected so
 * tests stay hermetic; at a composition root the process wiring is
 * {@code System.getProperty("os.name")}, {@code System.getProperty("user.home")}, and
 * {@link EnvironmentCredentialSource#processEnvironmentLookup()} — the single
 * process-environment touch of this package. Host names outside the supported platform set
 * classify as unsupported rather than being guessed: a {@code null} or blank name never falls
 * back to a default platform.
 */
public final class ConfigPaths {

    private static final String XDG_CONFIG_HOME = "XDG_CONFIG_HOME";

    private static final String MACOS_RELATIVE_CONFIG = "Library/Application Support/brave-search/config.json";

    private static final String LINUX_DEFAULT_RELATIVE_CONFIG = ".config/brave-search/config.json";

    private static final String RELATIVE_CONFIG = "brave-search/config.json";

    private final Supplier<String> operatingSystemName;

    private final Supplier<String> homeDirectory;

    private final Function<String, String> environmentLookup;

    public ConfigPaths(
            Supplier<String> operatingSystemName,
            Supplier<String> homeDirectory,
            Function<String, String> environmentLookup) {
        this.operatingSystemName = Objects.requireNonNull(operatingSystemName, "operatingSystemName");
        this.homeDirectory = Objects.requireNonNull(homeDirectory, "homeDirectory");
        this.environmentLookup = Objects.requireNonNull(environmentLookup, "environmentLookup");
    }

    /**
     * The platform v1 credential-file path.
     *
     * @throws LocalConfigException on an unsupported platform or an unusable home/XDG value; the
     *     failure never carries environment values beyond the offending path text itself
     */
    public Path credentialFilePath() {
        String osName = operatingSystemName.get();
        String platform = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (platform.startsWith("mac")) {
            return homeDirectoryPath().resolve(MACOS_RELATIVE_CONFIG);
        }
        if (platform.startsWith("linux")) {
            return linuxPath();
        }
        throw LocalConfigException.unsupportedPlatform();
    }

    private Path linuxPath() {
        String xdgConfigHome = environmentLookup.apply(XDG_CONFIG_HOME);
        if (xdgConfigHome == null) {
            return homeDirectoryPath().resolve(LINUX_DEFAULT_RELATIVE_CONFIG);
        }
        if (xdgConfigHome.isBlank()) {
            throw LocalConfigException.misconfiguredPath(
                    XDG_CONFIG_HOME + " is set but blank; unset it or set it to an absolute path");
        }
        Path base = Path.of(xdgConfigHome);
        if (!base.isAbsolute()) {
            throw LocalConfigException.misconfiguredPath(XDG_CONFIG_HOME + " must be an absolute path when set");
        }
        return base.resolve(RELATIVE_CONFIG);
    }

    private Path homeDirectoryPath() {
        String home = homeDirectory.get();
        if (home == null || home.isBlank()) {
            throw LocalConfigException.misconfiguredPath(
                    "the user home directory is unavailable, so the credential-file path cannot be resolved");
        }
        Path homePath = Path.of(home);
        if (!homePath.isAbsolute()) {
            throw LocalConfigException.misconfiguredPath("the user home directory must be absolute: " + home);
        }
        return homePath;
    }
}

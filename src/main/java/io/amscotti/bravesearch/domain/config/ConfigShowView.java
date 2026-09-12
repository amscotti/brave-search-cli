package io.amscotti.bravesearch.domain.config;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The redaction-safe projection of the credential configuration that {@code config show}
 * renders: the winning source's name, the resolved config-file path with how the file
 * presents, and which lower-precedence sources are shadowed. Presence and provenance only —
 * never a value, a fingerprint, or a length of the token — so any rendering channel is safe.
 *
 * <p>The source names are the ones {@code CredentialProvider} provenance uses, plus {@code
 * missing} for the no-source-anywhere case.
 *
 * @param sourceName the winning source's name, or {@code missing}
 * @param configPath the platform-resolved credential-file path
 * @param fileSight how the config file presents at that path
 * @param aliasShadowed whether the canonical environment variable shadowed a set alias
 * @param fileShadowed whether an environment source shadowed a stored file key
 */
public record ConfigShowView(
        String sourceName, Path configPath, ConfigFileSight fileSight, boolean aliasShadowed, boolean fileShadowed) {

    /** The config-file source name used by credential provenance. */
    public static final String FILE_SOURCE = "config file";

    /** The source name of the missing-everywhere case. */
    public static final String MISSING_SOURCE = "missing";

    public ConfigShowView {
        Objects.requireNonNull(sourceName, "sourceName");
        Objects.requireNonNull(configPath, "configPath");
        Objects.requireNonNull(fileSight, "fileSight");
    }

    /** The view of the missing-credential case: no winner, no shadowing. */
    public static ConfigShowView missing(Path configPath, ConfigFileSight fileSight) {
        return new ConfigShowView(MISSING_SOURCE, configPath, fileSight, false, false);
    }

    /** Whether the winning source is an environment variable. */
    public boolean sourceIsEnvironment() {
        return !FILE_SOURCE.equals(sourceName) && !MISSING_SOURCE.equals(sourceName);
    }
}

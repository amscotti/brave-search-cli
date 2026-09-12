package io.amscotti.bravesearch.domain.config;

import java.util.Objects;

/**
 * A local-configuration failure: the credential file cannot be resolved, read, or replaced
 * safely. Factories receive only redacted detail — messages may name paths, modes, and
 * identities, but never token material or document content — so the failure can surface on any
 * diagnostic channel; the CLI maps it to the local-configuration exit status.
 */
public final class LocalConfigException extends RuntimeException {

    /** Why the local configuration failed. */
    public enum Reason {

        /** The host platform has no supported v1 credential-file location. */
        UNSUPPORTED_PLATFORM,

        /** The credential-file location is unusable, for example a set-but-relative XDG_CONFIG_HOME. */
        MISCONFIGURED_PATH,

        /** The file declares a schema version this build does not understand. */
        UNSUPPORTED_SCHEMA,

        /** The file is not a strict v1 document (bounded valid UTF-8, one object, no duplicates). */
        MALFORMED_FILE,

        /** The file or its directory violates the owner-only security contract. */
        INSECURE_FILE,

        /** The filesystem cannot support the safe-access contract, for example no atomic replacement. */
        UNSAFE_FILESYSTEM,

        /** A secret could not be read: no console exists, or the input violates the one-line contract. */
        SECRET_INPUT
    }

    private final Reason reason;

    private LocalConfigException(Reason reason, String detail) {
        super("local configuration error: " + Objects.requireNonNull(detail, "detail"));
        this.reason = reason;
    }

    /** The host runs an operating system outside the supported v1 platform set. */
    public static LocalConfigException unsupportedPlatform() {
        return new LocalConfigException(
                Reason.UNSUPPORTED_PLATFORM,
                "the v1 credential file has no supported location on this platform (supported: macOS, Linux)");
    }

    /** The credential-file location cannot be used; the detail names the misconfiguration. */
    public static LocalConfigException misconfiguredPath(String detail) {
        return new LocalConfigException(Reason.MISCONFIGURED_PATH, detail);
    }

    /** The file declares a schema version other than the supported one; the found value is never echoed. */
    public static LocalConfigException unsupportedSchemaVersion(String expectedVersion) {
        return new LocalConfigException(
                Reason.UNSUPPORTED_SCHEMA, "unsupported config schema version: expected \"" + expectedVersion + "\"");
    }

    /** The file is not a strict v1 document; the detail never carries document content. */
    public static LocalConfigException malformedFile(String detail) {
        return new LocalConfigException(Reason.MALFORMED_FILE, detail);
    }

    /** The file or its directory fails the owner-only security contract. */
    public static LocalConfigException insecureFile(String detail) {
        return new LocalConfigException(Reason.INSECURE_FILE, detail);
    }

    /** The filesystem cannot provide the safe-access contract. */
    public static LocalConfigException unsafeFilesystem(String detail) {
        return new LocalConfigException(Reason.UNSAFE_FILESYSTEM, detail);
    }

    /**
     * A secret could not be read through the no-echo console or the exact one-line stdin
     * contract; the detail names the violated rule and never token material.
     */
    public static LocalConfigException secretInput(String detail) {
        return new LocalConfigException(Reason.SECRET_INPUT, detail);
    }

    /** Why the local configuration failed. */
    public Reason reason() {
        return reason;
    }
}

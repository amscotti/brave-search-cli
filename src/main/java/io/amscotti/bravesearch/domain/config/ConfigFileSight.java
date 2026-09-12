package io.amscotti.bravesearch.domain.config;

/**
 * How the credential file presents at its resolved path, as far as reporting needs to know:
 * absent; holding a stored api key (including one that violates the token contract — the
 * value exists even when it is rejected); present without a key; or present but not safely
 * readable, which no report may inspect further.
 */
public enum ConfigFileSight {
    /** No file exists at the resolved path. */
    ABSENT,

    /** A file exists and holds an api key; the key itself may still be invalid. */
    KEY_STORED,

    /** A file exists but stores no api key. */
    NO_KEY_STORED,

    /** A file occupies the path but cannot be read safely, so its contents are unknown. */
    UNREADABLE
}

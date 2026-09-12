package io.amscotti.bravesearch.domain.config;

import java.util.Objects;
import java.util.Optional;

/**
 * The result of reading the credential file: the stored credential when one is present, together
 * with the file's identity — including the absent case, which carries an empty credential and a
 * summary whose present flag is false.
 *
 * @param credential the stored credential; empty when the file holds none
 * @param summary identity of the file that was read
 */
public record ConfigState(Optional<Credential> credential, ConfigFileSummary summary) {

    public ConfigState {
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(summary, "summary");
    }
}

package io.amscotti.bravesearch.application.port.out;

import io.amscotti.bravesearch.domain.config.ConfigFileSight;
import io.amscotti.bravesearch.domain.config.ConfigState;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.config.PermissionRepair;

/**
 * Outbound port for the credential file: reading its state, describing its presence without
 * materializing the credential, replacing its key with a secure atomic write, removing it, and
 * restoring its on-disk permission contract. The CLI config subcommands consume only this
 * port, never the config adapter behind it.
 */
public interface ConfigStore {

    /** The file's current state, including the absent case. */
    ConfigState load();

    /**
     * How the file presents — absent, key stored, no key stored — under the same structural
     * validation a load applies, but without building the stored credential: an invalid stored
     * key still reads as stored, and a file that fails the safe-access contract surfaces as its
     * typed configuration error for the caller to degrade.
     */
    ConfigFileSight peekSummary();

    /** Atomically replaces the stored key. */
    void store(Credential credential);

    /** Removes the stored file. */
    void clear();

    /**
     * Restores the owner-only permission contract of the file and its directory, tightening
     * modes only, and reports what actually changed.
     */
    PermissionRepair repairPermissions();
}

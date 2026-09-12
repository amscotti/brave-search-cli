package io.amscotti.bravesearch.application.port.out;

import io.amscotti.bravesearch.domain.config.Credential;

/** Outbound port supplying the credential that every remote exchange authenticates with. */
public interface CredentialProvider {

    /**
     * Resolves the effective credential.
     *
     * @throws CredentialResolutionException when no source provides a credential, or a present
     *     source provides one that violates the token contract; the failure names the offending
     *     source but never carries token material
     */
    Credential resolve() throws CredentialResolutionException;

    /**
     * Resolves the credential together with render-safe provenance for {@code config show} and
     * the verbose alias-shadowed notice: the winning source's name and whether the canonical
     * environment variable shadowed a set alias — presence only, never a value.
     *
     * @throws CredentialResolutionException under the same conditions as {@link #resolve()}
     */
    ResolvedCredential resolveWithProvenance() throws CredentialResolutionException;
}

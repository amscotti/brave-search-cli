package io.amscotti.bravesearch.application.port.out;

import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.domain.config.Credential;
import java.util.Objects;

/** Outbound port supplying the credential that every remote exchange authenticates with. */
public interface CredentialProvider {

    /**
     * A provider of one credential a preflight already resolved: every resolution answers that
     * same value, the shape an exchange takes when the credential was resolved once for the
     * whole run.
     *
     * @throws NullPointerException when {@code credential} is null
     */
    static CredentialProvider of(Credential credential) {
        Objects.requireNonNull(credential, "credential");
        return new CredentialProvider() {

            @Override
            public Credential resolve() {
                return credential;
            }

            @Override
            public ResolvedCredential resolveWithProvenance() {
                return new ResolvedCredential(credential, "an already-resolved credential", false);
            }
        };
    }

    /**
     * Routes between the stored production credential provider and the loopback test-token
     * provider by the origin's own rule: the stored credential reaches only the exact
     * production origin, while every loopback origin draws its token from the test-token
     * provider instead, so a test or proxy override can never capture stored credential
     * material. The routed provider is one of the two given ones, so a resolution failure
     * surfaces as the provider's own checked {@link CredentialResolutionException} — no
     * wrapper carries it. The composition injects both providers; this only applies the rule.
     *
     * @throws NullPointerException when any argument is null
     */
    static CredentialProvider routedTo(
            BraveApiOrigin origin, CredentialProvider storedCredentials, CredentialProvider loopbackTestToken) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(storedCredentials, "storedCredentials");
        Objects.requireNonNull(loopbackTestToken, "loopbackTestToken");
        return origin.credentialsAllowed() ? storedCredentials : loopbackTestToken;
    }

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

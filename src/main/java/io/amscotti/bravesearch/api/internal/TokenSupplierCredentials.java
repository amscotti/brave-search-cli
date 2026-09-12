package io.amscotti.bravesearch.api.internal;

import io.amscotti.bravesearch.application.exchange.BraveApiRequest;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.CredentialResolutionException;
import io.amscotti.bravesearch.application.port.out.ResolvedCredential;
import io.amscotti.bravesearch.domain.config.Credential;
import java.util.function.Supplier;

/**
 * The builder's explicit token supplier as the credential provider the gateways' library
 * wiring consumes: resolved fresh on every call, with a supplier that yields nothing or
 * throws — or yields a token whose characters cannot travel in the subscription-token
 * header value — turned into the typed resolution failure that becomes the
 * local-configuration outcome.
 */
final class TokenSupplierCredentials implements CredentialProvider {

    /** The failure-diagnostic name of the one credential source the library path has. */
    static final String SOURCE_NAME = "the builder token supplier";

    private final Supplier<Credential> supplier;

    TokenSupplierCredentials(Supplier<Credential> supplier) {
        this.supplier = supplier;
    }

    @Override
    public Credential resolve() throws CredentialResolutionException {
        Credential credential;
        try {
            credential = supplier.get();
        } catch (RuntimeException failure) {
            // the supplier is the caller's own code; its failure message names their cause
            throw CredentialResolutionException.invalid(SOURCE_NAME, String.valueOf(failure.getMessage()));
        }
        if (credential == null) {
            throw CredentialResolutionException.missing(SOURCE_NAME);
        }
        try {
            // a token outside the header alphabet can never travel; rejecting it here keeps
            // the local-configuration outcome the supplier failures already carry
            BraveApiRequest.requireWireableToken(credential);
        } catch (RuntimeException unwirable) {
            throw CredentialResolutionException.invalid(SOURCE_NAME, String.valueOf(unwirable.getMessage()));
        }
        return credential;
    }

    @Override
    public ResolvedCredential resolveWithProvenance() throws CredentialResolutionException {
        return new ResolvedCredential(resolve(), SOURCE_NAME, false);
    }
}

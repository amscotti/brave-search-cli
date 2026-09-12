package io.amscotti.bravesearch.adapter.config;

import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.CredentialResolutionException;
import io.amscotti.bravesearch.application.port.out.ResolvedCredential;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.config.InvalidCredentialException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Function;

/**
 * The loopback test-key source: the credential a {@code --base-url} loopback run
 * authenticates with, read from {@value #TEST_KEY_VARIABLE} through an injectable
 * environment lookup so this class stays the only environment touch beside the stored
 * credential source. A missing variable is the variable's own not-set failure naming
 * exactly what the operator must provide; a present value is validated through the
 * credential contract — a proxy placeholder token passes, because the token is opaque — and
 * an invalid value names the variable without echoing it.
 */
public final class LoopbackTestKeyCredentials implements CredentialProvider {

    /** The one environment variable a loopback run draws its test token from. */
    public static final String TEST_KEY_VARIABLE = "BRAVE_SEARCH_TEST_KEY";

    private static final String SOURCE_NAME = "environment " + TEST_KEY_VARIABLE;

    private final Function<String, String> environmentLookup;

    public LoopbackTestKeyCredentials(Function<String, String> environmentLookup) {
        this.environmentLookup = Objects.requireNonNull(environmentLookup, "environmentLookup");
    }

    @Override
    public Credential resolve() throws CredentialResolutionException {
        String rawValue = environmentLookup.apply(TEST_KEY_VARIABLE);
        if (rawValue == null) {
            throw CredentialResolutionException.notSet(TEST_KEY_VARIABLE);
        }
        try {
            return Credential.of(rawValue.getBytes(StandardCharsets.UTF_8));
        } catch (InvalidCredentialException invalid) {
            throw CredentialResolutionException.invalid(SOURCE_NAME, invalid.reason());
        }
    }

    @Override
    public ResolvedCredential resolveWithProvenance() throws CredentialResolutionException {
        return new ResolvedCredential(resolve(), SOURCE_NAME, false);
    }
}

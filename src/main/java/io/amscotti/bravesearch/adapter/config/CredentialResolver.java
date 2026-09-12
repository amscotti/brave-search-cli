package io.amscotti.bravesearch.adapter.config;

import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.CredentialResolutionException;
import io.amscotti.bravesearch.application.port.out.ResolvedCredential;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.config.InvalidCredentialException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Applies the credential precedence: the canonical environment variable, then its compatibility
 * alias, then the config file, then the missing-credential failure.
 *
 * <p>A source that is present but invalid ends resolution immediately: falling through to a
 * lower source would silently mask the operator's explicit-but-broken setting, so the failure
 * names that source and the search stops. Provenance travels as a source name plus the boolean
 * alias-shadowed flag — never a value — so the presentation layer can render the verbose
 * shadowed notice and {@code config show} output without ever touching the token.
 */
public final class CredentialResolver implements CredentialProvider {

    private static final String CANONICAL_SOURCE = "environment " + EnvironmentCredentialSource.CANONICAL_VARIABLE;

    private static final String ALIAS_SOURCE = "environment " + EnvironmentCredentialSource.ALIAS_VARIABLE;

    private static final String FILE_SOURCE = "config file";

    private static final String SEARCHED_SOURCES = CANONICAL_SOURCE + ", " + ALIAS_SOURCE + ", or the " + FILE_SOURCE;

    private final EnvironmentCredentialSource environment;

    private final Supplier<Optional<Credential>> fileCredential;

    public CredentialResolver(EnvironmentCredentialSource environment, Supplier<Optional<Credential>> fileCredential) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.fileCredential = Objects.requireNonNull(fileCredential, "fileCredential");
    }

    @Override
    public Credential resolve() throws CredentialResolutionException {
        return resolveWithProvenance().credential();
    }

    /**
     * Resolves the credential together with render-safe provenance: the winning source's name
     * and whether the canonical variable shadowed a set alias.
     *
     * @throws CredentialResolutionException when every source is absent or the highest present
     *     source is invalid; the failure names the source but never the token
     */
    @Override
    public ResolvedCredential resolveWithProvenance() throws CredentialResolutionException {
        Optional<String> canonical = environment.canonical();
        boolean aliasShadowed = environment.aliasShadowed();
        if (canonical.isPresent()) {
            return new ResolvedCredential(accepted(CANONICAL_SOURCE, canonical.orElseThrow()), CANONICAL_SOURCE, aliasShadowed);
        }
        Optional<String> alias = environment.alias();
        if (alias.isPresent()) {
            return new ResolvedCredential(accepted(ALIAS_SOURCE, alias.orElseThrow()), ALIAS_SOURCE, false);
        }
        Optional<Credential> fromFile = fileCredential();
        if (fromFile.isPresent()) {
            return new ResolvedCredential(fromFile.orElseThrow(), FILE_SOURCE, false);
        }
        throw CredentialResolutionException.missing(SEARCHED_SOURCES);
    }

    private Optional<Credential> fileCredential() throws CredentialResolutionException {
        try {
            return fileCredential.get();
        } catch (InvalidCredentialException invalid) {
            throw CredentialResolutionException.invalid(FILE_SOURCE, invalid.reason());
        }
    }

    private static Credential accepted(String sourceName, String rawValue) throws CredentialResolutionException {
        try {
            return Credential.of(rawValue.getBytes(StandardCharsets.UTF_8));
        } catch (InvalidCredentialException invalid) {
            throw CredentialResolutionException.invalid(sourceName, invalid.reason());
        }
    }
}

package io.amscotti.bravesearch.application.port.out;

import java.util.Optional;

/**
 * Failure of {@link CredentialProvider#resolve()}: either no source provided a credential at
 * all, or a present source offered one that violates the token contract.
 *
 * <p>Checked by design. Credential resolution sits on the startup path of every command, so the
 * compiler must force each caller to turn this failure into its own presentation instead of
 * letting it surface as an unhandled internal error — the CLI maps it to the
 * local-configuration exit status with the already-redacted message. This keeps it distinct
 * from the {@code Outcome} expected-failure shape, which models upstream exchange failures
 * rather than local preconditions. The message names the offending source and the violated
 * invariant; it never carries token material or any reusable fingerprint of it.
 */
public final class CredentialResolutionException extends Exception {

    /** Why resolution failed. */
    public enum Category {

        /** No source provided a credential. */
        MISSING,

        /** A present source provided a credential that violates the token contract. */
        INVALID
    }

    private final Category category;

    private final Optional<String> sourceName;

    private CredentialResolutionException(Category category, Optional<String> sourceName, String message) {
        super(message);
        this.category = category;
        this.sourceName = sourceName;
    }

    /**
     * The missing-everywhere failure; {@code searchedSources} describes the sources that were
     * consulted, in precedence order, without any values.
     */
    public static CredentialResolutionException missing(String searchedSources) {
        return new CredentialResolutionException(
                Category.MISSING, Optional.empty(), "missing credential: none of " + searchedSources + " provided one");
    }

    /**
     * The named-variable-absent failure: a source whose single environment variable was never
     * set, phrased as the variable's own not-set line so a loopback run names exactly the
     * variable the operator must provide.
     */
    public static CredentialResolutionException notSet(String variableName) {
        return new CredentialResolutionException(Category.MISSING, Optional.empty(), variableName + " is not set");
    }

    /** The present-but-invalid failure naming the source at fault and the violated invariant. */
    public static CredentialResolutionException invalid(String sourceName, String reason) {
        return new CredentialResolutionException(
                Category.INVALID, Optional.of(sourceName), "invalid credential from " + sourceName + ": " + reason);
    }

    /** Why resolution failed. */
    public Category category() {
        return category;
    }

    /** The source at fault; empty when no single source is at fault. */
    public Optional<String> sourceName() {
        return sourceName;
    }
}

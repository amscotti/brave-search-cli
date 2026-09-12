package io.amscotti.bravesearch.adapter.config;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Reads the two credential variables through an injectable environment lookup, so tests stay
 * hermetic and the process environment is touched by exactly one member of this package:
 * {@link #processEnvironmentLookup()}. Adapters never construct themselves, so a composition
 * root passes that lookup into the constructor. Raw values leave this class untouched —
 * validation, precedence, and redaction belong to the resolver.
 */
public final class EnvironmentCredentialSource {

    /** The Brave-documented credential variable; highest precedence. */
    public static final String CANONICAL_VARIABLE = "BRAVE_API_KEY";

    /** The compatibility alias below the canonical variable. */
    public static final String ALIAS_VARIABLE = "BRAVE_SEARCH_API_KEY";

    private final Function<String, String> lookup;

    public EnvironmentCredentialSource(Function<String, String> lookup) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
    }

    /**
     * The real process-environment lookup: the only {@code System.getenv} touch outside the
     * terminal detector, which owns the process-wide color and width probes.
     */
    public static Function<String, String> processEnvironmentLookup() {
        return System::getenv;
    }

    /** The canonical variable's raw value when set. */
    public Optional<String> canonical() {
        return Optional.ofNullable(lookup.apply(CANONICAL_VARIABLE));
    }

    /** The alias variable's raw value when set. */
    public Optional<String> alias() {
        return Optional.ofNullable(lookup.apply(ALIAS_VARIABLE));
    }

    /** Whether the canonical variable shadows a set alias; carries no values. */
    public boolean aliasShadowed() {
        return canonical().isPresent() && alias().isPresent();
    }
}

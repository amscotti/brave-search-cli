package io.amscotti.bravesearch.adapterimpostor;

/**
 * Rule 4 counterpart: constructs {@link PrefixSiblingType} outside the composition roots.
 * Because the target's package only shares a prefix with {@code adapter..}, the
 * composition-root rule must stay silent — a prefix match would flag wiring no rule owns.
 * Never executed.
 */
public final class WiresPrefixSiblingType {

    private final PrefixSiblingType wired = new PrefixSiblingType("fixture");

    public PrefixSiblingType wired() {
        return wired;
    }
}

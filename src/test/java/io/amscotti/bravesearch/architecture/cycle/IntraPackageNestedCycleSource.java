package io.amscotti.bravesearch.architecture.cycle;

/**
 * Class-level cycle fixture whose outgoing leg names a nested type: the cycle it closes with
 * {@link IntraPackageNestedCyclePartner} stays invisible to any analysis that drops or skips
 * binary names holding {@code $} instead of attributing them to their top-level owner. Never
 * used at runtime.
 */
public final class IntraPackageNestedCycleSource {

    public String send(IntraPackageNestedCyclePartner.Builder partner) {
        return partner.getClass().getName();
    }
}

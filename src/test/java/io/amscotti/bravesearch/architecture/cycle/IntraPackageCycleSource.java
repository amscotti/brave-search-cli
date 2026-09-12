package io.amscotti.bravesearch.architecture.cycle;

/**
 * Class-level cycle fixture: one of two top-level classes of this one package that depend on
 * each other, so no package-level slice rule can see the cycle they close. Never used at
 * runtime.
 */
public final class IntraPackageCycleSource {

    public String send(IntraPackageCyclePartner partner) {
        return partner.back(this);
    }
}

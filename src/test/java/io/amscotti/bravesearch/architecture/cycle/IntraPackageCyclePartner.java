package io.amscotti.bravesearch.architecture.cycle;

/**
 * Class-level cycle fixture: depends back on {@link IntraPackageCycleSource} so the two
 * top-level classes of this package close a cycle no package-level slice rule can detect.
 * Never used at runtime.
 */
public final class IntraPackageCyclePartner {

    public String back(IntraPackageCycleSource source) {
        return source.getClass().getName();
    }
}

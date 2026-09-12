package io.amscotti.bravesearch.architecture.cycle;

/**
 * Class-level cycle fixture: its nested {@link Builder} is the leg that depends back on
 * {@link IntraPackageNestedCycleSource}, so the two top-level classes of this package close a
 * cycle one nesting level down. Never used at runtime.
 */
public final class IntraPackageNestedCyclePartner {

    /** The nested leg of the fixture cycle; its binary name holds {@code $}. */
    public static final class Builder {

        public String back(IntraPackageNestedCycleSource source) {
            return source.getClass().getName();
        }
    }
}

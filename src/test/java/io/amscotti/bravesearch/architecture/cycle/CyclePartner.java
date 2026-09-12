package io.amscotti.bravesearch.architecture.cycle;

import io.amscotti.bravesearch.architecture.ArchFixtures;

/**
 * Rule 10 fixture: depends back on {@link ArchFixtures.CycleSource} so the two packages close a
 * cycle. Never used at runtime.
 */
public final class CyclePartner {

    public String back(ArchFixtures.CycleSource source) {
        return source.getClass().getName();
    }
}

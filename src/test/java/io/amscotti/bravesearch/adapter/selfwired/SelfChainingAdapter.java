package io.amscotti.bravesearch.adapter.selfwired;

/**
 * Precision fixture of the composition-root rule: a concrete adapter-like class whose second
 * constructor delegates to the first. Constructor chaining is a type's own implementation,
 * not composition from outside, so the rule must leave it alone; the fixture is never
 * executed.
 */
public final class SelfChainingAdapter {

    private final String name;

    private final int count;

    public SelfChainingAdapter(String name, int count) {
        this.name = name;
        this.count = count;
    }

    public SelfChainingAdapter(String name) {
        this(name, 1);
    }
}

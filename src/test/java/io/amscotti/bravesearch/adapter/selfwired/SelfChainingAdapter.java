package io.amscotti.bravesearch.adapter.selfwired;

import java.util.function.Function;

/**
 * Precision fixture of the composition-root rule: a concrete adapter-like class exercising
 * every same-top-level-type construction form — constructor chaining, a nested builder
 * constructing its enclosing owner, and a self-referential constructor reference. All three
 * are a type's own implementation rather than composition from outside, so both the call
 * rule and its constructor-reference companion must leave them alone; the fixture is never
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

    public static Function<String, SelfChainingAdapter> factory() {
        return SelfChainingAdapter::new;
    }

    public static final class Builder {

        private final String name;

        private int count = 1;

        public Builder(String name) {
            this.name = name;
        }

        public Builder count(int count) {
            this.count = count;
            return this;
        }

        public SelfChainingAdapter build() {
            return new SelfChainingAdapter(name, count);
        }
    }
}

package io.amscotti.bravesearch.adapterimpostor;

/**
 * Rule 4 counterpart: a class whose package name merely starts with {@code .adapter} is not a
 * concrete adapter, so wiring it anywhere must not trip the composition-root rule. A plain
 * value holder with no adapter role; never executed.
 */
public final class PrefixSiblingType {

    private final String value;

    public PrefixSiblingType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}

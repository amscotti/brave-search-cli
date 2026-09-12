package io.amscotti.bravesearch.adapter.configlookalike;

/**
 * Rule 8/8B fixture: lives in a package whose name merely starts with {@code adapter.config},
 * so a prefix test would hand it the configuration seam it never earned. Reading the
 * environment and the properties here must fail both seam rules; the class is never executed.
 */
public final class ViolatesConfigSiblingPrefix {

    public String home() {
        return System.getenv("HOME");
    }

    public String name() {
        return System.getProperty("os.name");
    }
}

package io.amscotti.bravesearch.application.exchange;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The product User-Agent line, {@code brave-search/<version>}, with the version read from the
 * generated {@code brave-search-version.properties} resource — the same single source the CLI
 * version help reads — so the agent string can never drift from the shipped version. The line
 * is computed once at class initialization into an immutable field, so no mutable static state
 * exists and every caller observes the same value; a missing or unusable resource is a build
 * misconfiguration, not a runtime condition to paper over.
 */
final class ProductUserAgent {

    private static final String RESOURCE = "/brave-search-version.properties";
    private static final String PRODUCT = "brave-search";

    private static final String PRODUCT_LINE = PRODUCT + "/" + readVersion();

    private ProductUserAgent() {}

    static String value() {
        return PRODUCT_LINE;
    }

    private static String readVersion() {
        Properties properties = new Properties();
        try (InputStream resource = ProductUserAgent.class.getResourceAsStream(RESOURCE)) {
            if (resource == null) {
                throw new IllegalStateException("version resource " + RESOURCE + " is missing");
            }
            properties.load(resource);
        } catch (IOException unreadable) {
            throw new IllegalStateException("version resource " + RESOURCE + " is unreadable", unreadable);
        }
        String version = properties.getProperty("version");
        if (version == null || version.isBlank()) {
            throw new IllegalStateException("version resource " + RESOURCE + " has no usable version");
        }
        return version;
    }
}

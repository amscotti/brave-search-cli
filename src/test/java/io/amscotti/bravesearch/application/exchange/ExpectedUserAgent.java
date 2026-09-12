package io.amscotti.bravesearch.application.exchange;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The User-Agent line the product is expected to send, derived in the test source from the
 * same generated version resource the adapter reads, so assertions never hard-code a version.
 */
public final class ExpectedUserAgent {

    private ExpectedUserAgent() {}

    public static String fromVersionResource() throws IOException {
        Properties properties = new Properties();
        try (InputStream resource =
                ExpectedUserAgent.class.getResourceAsStream("/brave-search-version.properties")) {
            assertNotNull(resource, "the generated version resource must be on the test classpath");
            properties.load(resource);
        }
        String version = properties.getProperty("version");
        assertNotNull(version, "the generated version resource must carry a version entry");
        return "brave-search/" + version;
    }
}

package io.amscotti.bravesearch.adapter.config;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.application.port.out.CredentialResolutionException;
import io.amscotti.bravesearch.domain.config.Credential;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The loopback test-key source: the injected environment lookup is its only seam, a missing
 * variable is the named not-set failure, a present value keeps its exact bytes as a validated
 * credential, and an invalid value names the variable without echoing it.
 */
final class LoopbackTestKeyCredentialsTest {

    @Test
    void aMissingTestKeyFailsNamingTheVariable() throws CredentialResolutionException {
        LoopbackTestKeyCredentials source = new LoopbackTestKeyCredentials(name -> null);

        CredentialResolutionException failure =
                assertThrows(CredentialResolutionException.class, source::resolve);

        assertEquals(CredentialResolutionException.Category.MISSING, failure.category());
        assertEquals("BRAVE_SEARCH_TEST_KEY is not set", failure.getMessage());
    }

    @Test
    void aPresentTestKeyResolvesToItsExactBytes() throws CredentialResolutionException {
        Map<String, String> environment = new HashMap<>();
        environment.put("BRAVE_SEARCH_TEST_KEY", "proxy-placeholder-token");
        LoopbackTestKeyCredentials source = new LoopbackTestKeyCredentials(environment::get);

        Credential resolved = source.resolve();

        assertArrayEquals(
                "proxy-placeholder-token".getBytes(UTF_8), resolved.tokenBytes(), "bytes round-trip untouched");
        assertEquals("environment BRAVE_SEARCH_TEST_KEY", source.resolveWithProvenance().sourceName());
    }

    @Test
    void anInvalidTestValueNamesTheVariableWithoutEchoingIt() {
        Map<String, String> environment = new HashMap<>();
        environment.put("BRAVE_SEARCH_TEST_KEY", "token\u0007with-control");
        LoopbackTestKeyCredentials source = new LoopbackTestKeyCredentials(environment::get);

        CredentialResolutionException failure =
                assertThrows(CredentialResolutionException.class, source::resolve);

        assertEquals(CredentialResolutionException.Category.INVALID, failure.category());
        assertTrue(failure.getMessage().contains("BRAVE_SEARCH_TEST_KEY"), failure.getMessage());
        assertTrue(failure.getMessage().contains("control"), failure.getMessage());
        assertEquals("environment BRAVE_SEARCH_TEST_KEY", failure.sourceName().orElseThrow());
    }
}

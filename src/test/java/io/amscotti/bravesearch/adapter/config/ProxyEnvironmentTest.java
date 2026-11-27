package io.amscotti.bravesearch.adapter.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.application.exchange.HttpProxy;
import io.amscotti.bravesearch.domain.config.LocalConfigException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The standard proxy variables as the CLI reads them: {@code HTTPS_PROXY} (or its lowercase
 * spelling) names the proxy, {@code NO_PROXY} (or {@code no_proxy}) names what bypasses it,
 * an unset proxy means a direct connection, and a value the client cannot honor is a
 * configuration error that names the rule and never the value — a proxy address may carry
 * credentials.
 */
final class ProxyEnvironmentTest {

    private static final String SENTINEL = "sentinel-user:sentinel-pass@proxy.internal:3128";

    @Test
    void anUnsetProxyVariableMeansADirectConnection() {
        assertEquals(Optional.empty(), ProxyEnvironment.httpsProxy(name -> null));
        assertEquals(
                Optional.empty(),
                ProxyEnvironment.httpsProxy(name -> name.equals("HTTPS_PROXY") ? "   " : null),
                "a blank value configures nothing");
    }

    @Test
    void theProxyAddressAndBypassListAreReadFromTheStandardVariables() {
        Optional<HttpProxy> proxy = ProxyEnvironment.httpsProxy(name -> switch (name) {
            case "HTTPS_PROXY" -> " http://proxy.internal:3128 ";
            case "NO_PROXY" -> "localhost, .corp.example,";
            default -> null;
        });

        assertEquals(new HttpProxy("proxy.internal", 3128, List.of("localhost", ".corp.example")), proxy.orElseThrow());
    }

    @Test
    void theLowercaseSpellingsServeWhenTheUppercaseOnesAreAbsentAndUppercaseWins() {
        Optional<HttpProxy> lowercase = ProxyEnvironment.httpsProxy(
                name -> name.equals("https_proxy") ? "http://lower.internal:8080" : name.equals("no_proxy") ? "a.b" : null);
        Optional<HttpProxy> uppercase = ProxyEnvironment.httpsProxy(name -> switch (name) {
            case "HTTPS_PROXY", "https_proxy" -> name.equals("HTTPS_PROXY") ? "http://upper.internal" : "http://lower.internal";
            default -> null;
        });

        assertEquals("lower.internal", lowercase.orElseThrow().host());
        assertEquals("upper.internal", uppercase.orElseThrow().host(), "the documented precedence: uppercase first");
        assertEquals(80, uppercase.orElseThrow().port(), "a proxy without a port is the plain-http default port");
        assertEquals(List.of("a.b"), lowercase.orElseThrow().bypass());
    }

    @Test
    void aProxyAddressWithoutASchemeIsAHostAndPort() {
        assertEquals(
                new HttpProxy("proxy.internal", 3128, List.of()),
                ProxyEnvironment.httpsProxy(name -> name.equals("HTTPS_PROXY") ? "proxy.internal:3128" : null)
                        .orElseThrow());
    }

    @Test
    void aValueTheClientCannotHonorIsAConfigurationErrorThatNeverEchoesTheValue() {
        for (String unwirable : List.of(SENTINEL, "socks5://proxy.internal:1080", "https://proxy.internal:3128", "not a uri")) {
            LocalConfigException failure = assertThrows(
                    LocalConfigException.class,
                    () -> ProxyEnvironment.httpsProxy(name -> name.equals("HTTPS_PROXY") ? unwirable : null),
                    () -> "refused: " + unwirable);

            assertEquals(LocalConfigException.Reason.MISCONFIGURED_PROXY, failure.reason());
            assertFalse(failure.getMessage().contains("sentinel"), "the failure never carries the value: " + failure.getMessage());
            assertFalse(failure.getMessage().contains("proxy.internal:3128"), "nor the address: " + failure.getMessage());
            assertTrue(failure.getMessage().startsWith("local configuration error: "), failure.getMessage());
        }
    }

    @Test
    void eachRefusalNamesItsRule() {
        assertEquals("local configuration error: the proxy address is not a parsable URI", refused("not a uri"));
        assertEquals(
                "local configuration error: only plain http proxies are supported",
                refused("socks5://h:1"));
        assertEquals(
                "local configuration error: an authenticated proxy is not supported",
                refused("http://u:p@h:1"));
        assertEquals("local configuration error: the proxy address names no host", refused("http://:3128"));
        assertThrows(NullPointerException.class, () -> ProxyEnvironment.httpsProxy(null));
    }

    @Test
    void anOutOfRangeProxyPortIsAConfigurationErrorNamingTheRule() {
        assertEquals(
                "local configuration error: the proxy port is outside 1 through 65535",
                refused("http://proxy.internal:70000"));
        assertEquals(
                "local configuration error: the proxy port is outside 1 through 65535",
                refused("http://proxy.internal:0"));
    }

    @Test
    void blankBypassEntriesAreDropped() {
        Optional<HttpProxy> proxy = ProxyEnvironment.httpsProxy(name -> switch (name) {
            case "HTTPS_PROXY" -> "http://proxy.internal:3128";
            case "NO_PROXY" -> " , a.b, ,c.d,, ";
            default -> null;
        });

        assertEquals(List.of("a.b", "c.d"), proxy.orElseThrow().bypass(), "only real entries survive");
    }

    private static String refused(String value) {
        return assertThrows(LocalConfigException.class, () -> ProxyEnvironment.httpsProxy(name -> value))
                .getMessage();
    }

    @Test
    void theVariablesAreReadInTheirDocumentedPrecedenceAndOnlyAsFarAsNeeded() {
        List<String> asked = new java.util.ArrayList<>();
        Optional<HttpProxy> proxy = ProxyEnvironment.httpsProxy(name -> {
            asked.add(name);
            return switch (name) {
                case "HTTPS_PROXY" -> "http://proxy.internal:3128";
                case "NO_PROXY" -> "localhost";
                default -> null;
            };
        });

        assertEquals(List.of("HTTPS_PROXY", "NO_PROXY"), asked, "the uppercase names are read first and stop the search");
        assertEquals(3128, proxy.orElseThrow().port());
        assertEquals(
                List.of("HTTPS_PROXY", "https_proxy"),
                ProxyEnvironment.PROXY_VARIABLES,
                "the proxy variables, in precedence order");
        assertEquals(List.of("NO_PROXY", "no_proxy"), ProxyEnvironment.BYPASS_VARIABLES);
    }

    @Test
    void aBlankUppercaseProxyVariableOverridesLowercaseAndForcesDirectConnection() {
        List<String> asked = new java.util.ArrayList<>();
        Optional<HttpProxy> emptyUpper = ProxyEnvironment.httpsProxy(name -> {
            asked.add(name);
            return switch (name) {
                case "HTTPS_PROXY" -> "";
                case "https_proxy" -> "http://lower.internal:8080";
                default -> null;
            };
        });
        Optional<HttpProxy> blankUpper = ProxyEnvironment.httpsProxy(name -> switch (name) {
            case "HTTPS_PROXY" -> "   ";
            case "https_proxy" -> "http://lower.internal:8080";
            default -> null;
        });

        assertEquals(Optional.empty(), emptyUpper, "an explicit empty HTTPS_PROXY disables https_proxy");
        assertEquals(Optional.empty(), blankUpper, "an explicit blank HTTPS_PROXY disables https_proxy");
        assertEquals(List.of("HTTPS_PROXY"), asked, "present HTTPS_PROXY stops the lookup without consulting https_proxy");
    }

    @Test
    void aBlankUppercaseNoProxyOverridesLowercaseAndClearsTheBypassList() {
        List<String> asked = new java.util.ArrayList<>();
        Optional<HttpProxy> proxy = ProxyEnvironment.httpsProxy(name -> {
            asked.add(name);
            return switch (name) {
                case "HTTPS_PROXY" -> "http://proxy.internal:3128";
                case "NO_PROXY" -> "   ";
                case "no_proxy" -> "*";
                default -> null;
            };
        });

        assertEquals(List.of(), proxy.orElseThrow().bypass(), "an explicit blank NO_PROXY clears no_proxy");
        assertEquals(
                List.of("HTTPS_PROXY", "NO_PROXY"),
                asked,
                "present NO_PROXY stops the lookup without consulting no_proxy");
    }

    @Test
    void aTrailingSlashAndIpv6ProxyLiteralsAreAccepted() {
        assertEquals(
                new HttpProxy("proxy.internal", 3128, List.of()),
                ProxyEnvironment.httpsProxy(name -> name.equals("HTTPS_PROXY") ? "http://proxy.internal:3128/" : null)
                        .orElseThrow());
        assertEquals(
                new HttpProxy("[::1]", 3128, List.of()),
                ProxyEnvironment.httpsProxy(name -> name.equals("HTTPS_PROXY") ? "http://[::1]:3128" : null)
                        .orElseThrow());
        assertEquals(
                new HttpProxy("[2001:db8::1]", 8080, List.of()),
                ProxyEnvironment.httpsProxy(name -> name.equals("HTTPS_PROXY") ? "[2001:db8::1]:8080" : null)
                        .orElseThrow());
    }

    @Test
    void pathsQueriesFragmentsAndCredentialsContainingDelimitersAreRefusedWithoutLeaking() {
        List<String> pathInputs = List.of(
                "http://proxy.internal/proxy.pac",
                "http://proxy.internal:3128/v1/proxy",
                "http://proxy.internal:3128//",
                "http:///proxy.pac",
                "http://sentinel-secret/rest@proxy.internal:3128",
                "http://sentinel-user:8080/sentinel-pass@proxy.internal:3128");
        for (String input : pathInputs) {
            LocalConfigException failure = assertThrows(
                    LocalConfigException.class,
                    () -> ProxyEnvironment.httpsProxy(name -> name.equals("HTTPS_PROXY") ? input : null),
                    () -> "refused: " + input);
            assertEquals(LocalConfigException.Reason.MISCONFIGURED_PROXY, failure.reason());
            assertEquals("local configuration error: the proxy address must not carry a path", failure.getMessage());
            assertFalse(failure.getMessage().contains("sentinel"), failure.getMessage());
            assertFalse(failure.getMessage().contains("proxy.internal"), failure.getMessage());
        }

        List<String> queryOrFragmentInputs = List.of(
                "http://proxy.internal:3128?token=sentinel-secret",
                "http://proxy.internal:3128#sentinel-secret",
                "http://proxy.internal/?",
                "http://proxy.internal/#",
                "http://sentinel-secret?rest@proxy.internal:3128",
                "http://sentinel-secret#rest@proxy.internal:3128",
                "http://sentinel-user:8080?sentinel-pass@proxy.internal:3128",
                "http://sentinel-user:8080#sentinel-pass@proxy.internal:3128");
        for (String input : queryOrFragmentInputs) {
            LocalConfigException failure = assertThrows(
                    LocalConfigException.class,
                    () -> ProxyEnvironment.httpsProxy(name -> name.equals("HTTPS_PROXY") ? input : null),
                    () -> "refused: " + input);
            assertEquals(LocalConfigException.Reason.MISCONFIGURED_PROXY, failure.reason());
            assertEquals(
                    "local configuration error: the proxy address must not carry a query or fragment",
                    failure.getMessage());
            assertFalse(failure.getMessage().contains("sentinel"), failure.getMessage());
            assertFalse(failure.getMessage().contains("proxy.internal"), failure.getMessage());
        }
    }

    @Test
    void trailingEmptyPortsAndRegistryBasedUserInfoAreRefusedWithoutLeaking() {
        for (String emptyPort : List.of("http://proxy.internal:", "proxy.internal:", "http://[::1]:")) {
            assertEquals(
                    "local configuration error: the proxy port is outside 1 through 65535",
                    refused(emptyPort),
                    () -> "empty port refused: " + emptyPort);
        }
        for (String withAt : List.of(
                "http://sentinel-user:sentinel-pass@proxy_internal:3128",
                "http://sentinel-user:p@ss@proxy.internal:3128")) {
            LocalConfigException failure = assertThrows(
                    LocalConfigException.class,
                    () -> ProxyEnvironment.httpsProxy(name -> name.equals("HTTPS_PROXY") ? withAt : null));
            assertEquals(LocalConfigException.Reason.MISCONFIGURED_PROXY, failure.reason());
            assertEquals("local configuration error: an authenticated proxy is not supported", failure.getMessage());
            assertFalse(failure.getMessage().contains("sentinel"), failure.getMessage());
        }
    }
}

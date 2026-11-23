package io.amscotti.bravesearch.application.exchange;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Which exchanges a configured proxy carries: every origin except the ones the bypass list
 * names and except loopback — a loopback override is a test or a local proxy itself, and
 * routing it through another proxy would only hide it.
 */
final class HttpProxyTest {

    @Test
    void anOriginOutsideTheBypassListTravelsThroughTheProxy() {
        HttpProxy proxy = new HttpProxy("proxy.internal", 3128, List.of("localhost", ".corp.example"));

        assertTrue(proxy.proxies("api.search.brave.com"), "an unlisted origin is proxied");
        assertTrue(proxy.proxies("API.SEARCH.BRAVE.COM"), "the decision ignores case");
    }

    @Test
    void aBypassEntryCoversItsHostAndEverySubdomain() {
        HttpProxy proxy = new HttpProxy("proxy.internal", 3128, List.of("example.com", ".internal", "host:8443"));

        assertFalse(proxy.proxies("example.com"), "an exact entry bypasses its host");
        assertFalse(proxy.proxies("www.example.com"), "and every subdomain of it");
        assertFalse(proxy.proxies("api.internal"), "a leading dot means the domain and everything below it");
        assertTrue(proxy.proxies("notexample.com"), "a suffix match needs a domain boundary: a look-alike host is still proxied");
        assertFalse(proxy.proxies("host"), "a host:port entry bypasses on its host part");
        assertTrue(proxy.proxies("api.search.brave.com"));
    }

    @Test
    void aStarBypassesEveryOrigin() {
        assertFalse(new HttpProxy("proxy.internal", 3128, List.of("*")).proxies("api.search.brave.com"));
    }

    @Test
    void aLoopbackTargetIsNeverProxied() {
        HttpProxy proxy = new HttpProxy("proxy.internal", 3128, List.of());

        assertFalse(proxy.proxies("localhost"), "the localhost name");
        assertFalse(proxy.proxies("127.0.0.1"), "the IPv4 loopback");
        assertFalse(proxy.proxies("127.31.9.4"), "every address of the IPv4 loopback block");
        assertFalse(proxy.proxies("[::1]"), "the IPv6 loopback as a URI host spells it");
        assertFalse(proxy.proxies("::1"), "and as a bare address");
    }

    @Test
    void theValueIsBoundedAtConstruction() {
        assertThrows(NullPointerException.class, () -> new HttpProxy(null, 3128, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new HttpProxy(" ", 3128, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new HttpProxy("proxy.internal", 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new HttpProxy("proxy.internal", 65536, List.of()));
        assertThrows(NullPointerException.class, () -> new HttpProxy("proxy.internal", 3128, null));
    }
}

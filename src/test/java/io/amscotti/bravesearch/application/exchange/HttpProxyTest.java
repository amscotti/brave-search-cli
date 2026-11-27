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
        assertFalse(proxy.proxies("localhost."), "localhost with a trailing DNS root dot");
        assertFalse(proxy.proxies("127.0.0.1"), "the IPv4 loopback");
        assertFalse(proxy.proxies("127.31.9.4"), "every address of the IPv4 loopback block");
        assertFalse(proxy.proxies("127.255.255.255"), "the top of the IPv4 loopback block");
        assertFalse(proxy.proxies("[::1]"), "the IPv6 loopback as a URI host spells it");
        assertFalse(proxy.proxies("::1"), "and as a bare address");

        assertTrue(proxy.proxies("127.example.com"), "a domain starting with 127. is not an IPv4 loopback");
        assertTrue(proxy.proxies("127.0.0.1.nip.io"), "a domain prefixed with an IPv4 loopback is not loopback");
        assertTrue(proxy.proxies("127.0.0.256"), "an out-of-range octet is not a valid IPv4 loopback");
        assertTrue(proxy.proxies("127.0.0.a"), "a non-numeric octet is not a valid IPv4 loopback");
    }

    @Test
    void ipv6BypassEntriesMatchBracketedUnbracketedAndPortedFormsWithoutTruncating() {
        HttpProxy proxy = new HttpProxy(
                "proxy.internal", 3128, List.of("2001:db8::1", "[2001:db8::2]:8443", "[fe80::1]", "[malformed"));

        assertFalse(proxy.proxies("[2001:db8::1]"), "unbracketed IPv6 entry matches bracketed URI host");
        assertFalse(proxy.proxies("2001:db8::1"), "unbracketed IPv6 entry matches bare IPv6 host");
        assertTrue(proxy.proxies("2001"), "unbracketed IPv6 must not be truncated at its first colon");
        assertTrue(proxy.proxies("sub.2001"), "unbracketed IPv6 must not match unrelated hostnames");
        assertFalse(proxy.proxies("[2001:db8::2]"), "bracketed IPv6 with port strips only the port");
        assertFalse(proxy.proxies("2001:db8::2"), "bracketed IPv6 with port matches bare IPv6 host");
        assertFalse(proxy.proxies("[2001:DB8::2]"), "IPv6 matching is case-insensitive");
        assertFalse(proxy.proxies("fe80::1"), "bracketed IPv6 without port matches bare host");
        assertTrue(proxy.proxies("malformed"), "malformed bracketed entry never matches");
    }

    @Test
    void trailingDnsRootDotsAreNormalizedOnBothTargetsAndBypassEntries() {
        HttpProxy proxy = new HttpProxy("proxy.internal", 3128, List.of("example.com.", ".corp.internal."));

        assertFalse(proxy.proxies("example.com"), "trailing dot on entry matches bare host");
        assertFalse(proxy.proxies("api.example.com."), "trailing dot on target matches domain");
        assertFalse(proxy.proxies("svc.corp.internal."), "leading and trailing dots match subdomains");
        assertTrue(proxy.proxies("api.search.brave.com."));
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

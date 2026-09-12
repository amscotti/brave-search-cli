package io.amscotti.bravesearch.application.exchange;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.FailureKind;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Origin policy of the Brave HTTP adapter: production is one fixed constant, an override is a
 * loopback literal or a once-resolved verified-loopback localhost rewritten to a literal, and
 * stored credentials route only to the exact production origin. Rejections carry a typed usage
 * failure whose text never repeats the rejected input, so a base URL with user information can
 * never leak that user information through a diagnostic.
 */
final class BraveApiOriginTest {

    private static final LocalhostResolver LITERALS_ARE_NEVER_RESOLVED =
            host -> { throw new AssertionError("literal loopback hosts must not be resolved"); };

    @Test
    void productionOriginIsTheFixedBraveResV1BaseAndAllowsStoredCredentials() {
        BraveApiOrigin origin = BraveApiOrigin.production();

        assertEquals(URI.create("https://api.search.brave.com/res/v1"), origin.baseUri());
        assertTrue(origin.credentialsAllowed());
        assertEquals(BraveApiOrigin.production(), origin, "the production origin is one value");
    }

    @Test
    void literalLoopbackFormsAreAcceptedWithoutResolutionAndNeverTakeStoredCredentials() {
        record Accepted(String raw, String normalized) {}

        List<Accepted> accepted =
                List.of(
                        new Accepted("http://127.0.0.1", "http://127.0.0.1"),
                        new Accepted("https://127.0.0.1", "https://127.0.0.1"),
                        new Accepted("http://127.0.0.1:8443", "http://127.0.0.1:8443"),
                        new Accepted("https://[::1]", "https://[::1]"),
                        new Accepted("http://[::1]:9000", "http://[::1]:9000"),
                        new Accepted("https://[::1]:65535", "https://[::1]:65535"));

        for (Accepted form : accepted) {
            BraveApiOrigin origin = BraveApiOrigin.fromOverride(form.raw(), LITERALS_ARE_NEVER_RESOLVED);

            assertEquals(URI.create(form.normalized()), origin.baseUri(), () -> "wrong origin for " + form.raw());
            assertFalse(origin.credentialsAllowed(), () -> "loopback must not take stored credentials: " + form.raw());
        }
    }

    @Test
    void trailingSlashOnTheRootPathNormalizesToTheSlashlessOrigin() {
        BraveApiOrigin withSlash = BraveApiOrigin.fromOverride("http://127.0.0.1:9000/", LITERALS_ARE_NEVER_RESOLVED);
        BraveApiOrigin withoutSlash =
                BraveApiOrigin.fromOverride("http://127.0.0.1:9000", LITERALS_ARE_NEVER_RESOLVED);

        assertEquals(withoutSlash, withSlash);
        assertEquals("http://127.0.0.1:9000", withSlash.baseUri().toString());
    }

    @Test
    void localhostIsResolvedOnceVerifiedLoopbackAndRewrittenToThePreferredIpv4Literal()
            throws UnknownHostException {
        AtomicInteger resolutions = new AtomicInteger();
        LocalhostResolver mixedFamilyResolver = host -> {
            resolutions.incrementAndGet();
            return List.of(
                    InetAddress.getByName("::1"),
                    InetAddress.getByName("127.0.0.1"),
                    InetAddress.getByName("127.0.0.2"));
        };

        BraveApiOrigin origin = BraveApiOrigin.fromOverride("http://localhost:8443", mixedFamilyResolver);

        assertEquals(URI.create("http://127.0.0.1:8443"), origin.baseUri());
        assertEquals(1, resolutions.get(), "localhost must be resolved exactly once");
        assertFalse(origin.credentialsAllowed());
    }

    @Test
    void localhostFallsBackToTheIpv6LiteralWhenNoIpv4LoopbackAnswered() throws UnknownHostException {
        LocalhostResolver ipv6Only = host -> List.of(InetAddress.getByName("::1"));

        BraveApiOrigin origin = BraveApiOrigin.fromOverride("https://localhost", ipv6Only);

        assertEquals(URI.create("https://[::1]"), origin.baseUri());
    }

    @Test
    void localhostResolutionFailureIsAUsageFailureWithoutTheResolverText() {
        String resolverText = "sentinel-" + UUID.randomUUID();
        LocalhostResolver unresolved = host -> { throw new UnknownHostException(resolverText); };

        InvalidOriginException failure =
                assertThrows(
                        InvalidOriginException.class,
                        () -> BraveApiOrigin.fromOverride("http://localhost:9000", unresolved));

        assertEquals(FailureKind.USAGE, failure.kind());
        assertSecretAbsentRecursively(failure, resolverText);
    }

    @Test
    void localhostResolvingToNoAddressIsAUsageFailure() {
        LocalhostResolver empty = host -> List.of();

        InvalidOriginException failure =
                assertThrows(
                        InvalidOriginException.class,
                        () -> BraveApiOrigin.fromOverride("http://localhost:9000", empty));

        assertEquals(FailureKind.USAGE, failure.kind());
    }

    @Test
    void localhostResolvingToAnyNonLoopbackAddressIsAUsageFailure() throws UnknownHostException {
        String offLoopback = "192.168.10.23";
        LocalhostResolver mixedTrust = host ->
                List.of(InetAddress.getByName("127.0.0.1"), InetAddress.getByName(offLoopback));

        InvalidOriginException failure =
                assertThrows(
                        InvalidOriginException.class,
                        () -> BraveApiOrigin.fromOverride("http://localhost:9000", mixedTrust));

        assertEquals(FailureKind.USAGE, failure.kind());
        assertFalse(failure.getMessage().contains(offLoopback), "the rejection must not repeat resolved addresses");
    }

    @Test
    void overrideFormsOutsideTheLoopbackContractAreUsageFailures() {
        String sentinel = "sentinel-" + UUID.randomUUID();
        record Rejected(String raw, String why) {}

        List<Rejected> rejected =
                List.of(
                        new Rejected("http://user:" + sentinel + "@127.0.0.1:8080", "user information"),
                        new Rejected("https://127.0.0.1:8080/?q=1", "a query"),
                        new Rejected("http://127.0.0.1?", "an empty query separator"),
                        new Rejected("http://127.0.0.1#tail", "a fragment"),
                        new Rejected("http://127.0.0.1:8080/res/v1", "a non-root path"),
                        new Rejected("http://127.0.0.1:8080/../web", "dot-segment traversal"),
                        new Rejected("http://127.0.0.1:8080/.", "a dot-segment path"),
                        new Rejected("http://LOCALHOST:8080", "an uppercase host"),
                        new Rejected("http://127.0.0.01", "a non-normalized IPv4 literal"),
                        new Rejected("http://localhost:8080/web/search", "a path on localhost"),
                        new Rejected("http://[::1%25lo0]", "a zone identifier"),
                        new Rejected("http://127.0.0.1.", "a trailing-dot host"),
                        new Rejected("http://127.0.0.1:0", "a zero port"),
                        new Rejected("http://127.0.0.1:65536", "an out-of-range port"),
                        new Rejected("http://127.0.0.1:", "an empty port"),
                        new Rejected("ftp://127.0.0.1", "a non-http scheme"),
                        new Rejected("HTTP://127.0.0.1", "a non-literal scheme spelling"),
                        new Rejected("https://api.search.brave.com/res/v1", "a remote origin as override"),
                        new Rejected("https://xn--e1afmkfd.xn--p1ai", "an IDNA host"));

        for (Rejected form : rejected) {
            InvalidOriginException failure =
                    assertThrows(
                            InvalidOriginException.class,
                            () -> BraveApiOrigin.fromOverride(form.raw(), LITERALS_ARE_NEVER_RESOLVED),
                            () -> "must reject " + form.why());

            assertEquals(FailureKind.USAGE, failure.kind(), () -> "rejection must classify as usage: " + form.why());
            assertSecretAbsentRecursively(failure, sentinel);
        }
    }

    @Test
    void storedCredentialsRouteOnlyToTheProductionOrigin() {
        String storedToken = "sentinel-" + UUID.randomUUID();
        String loopbackToken = "sentinel-" + UUID.randomUUID();
        Supplier<Credential> storedCredentials = () -> Credential.of(storedToken.getBytes(UTF_8));
        Supplier<Credential> loopbackTestToken = () -> Credential.of(loopbackToken.getBytes(UTF_8));

        assertSame(storedCredentials, BraveApiOrigin.production().credentialSource(storedCredentials, loopbackTestToken));

        BraveApiOrigin loopback = BraveApiOrigin.fromOverride(
                "http://localhost:9", host -> List.of(InetAddress.getByName("127.0.0.1")));
        assertSame(loopbackTestToken, loopback.credentialSource(storedCredentials, loopbackTestToken));
        assertNotSame(storedCredentials, loopback.credentialSource(storedCredentials, loopbackTestToken));
        assertEquals(
                Credential.of(loopbackToken.getBytes(UTF_8)),
                loopback.credentialSource(storedCredentials, loopbackTestToken)
                        .get());
    }

    @Test
    void platformResolverAnswersLocalhostWithVerifiedLoopbackAddressesOnly() throws UnknownHostException {
        List<InetAddress> addresses = LocalhostResolver.platform().resolveAll("localhost");

        assertFalse(addresses.isEmpty(), "localhost must resolve to at least one address on the platform");
        for (InetAddress address : addresses) {
            assertTrue(address.isLoopbackAddress(), "every platform answer for localhost must be loopback");
        }
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(NullPointerException.class, () -> BraveApiOrigin.fromOverride(null, LITERALS_ARE_NEVER_RESOLVED));
        assertThrows(NullPointerException.class, () -> BraveApiOrigin.fromOverride("http://127.0.0.1", null));
        assertThrows(
                NullPointerException.class,
                () -> BraveApiOrigin.production().credentialSource(null, () -> null));
        assertThrows(
                NullPointerException.class,
                () -> BraveApiOrigin.production().credentialSource(() -> null, null));
    }

    private static void assertSecretAbsentRecursively(Throwable failure, String secret) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            assertFalse(
                    current.toString().contains(secret),
                    "an exception text in the cause chain carries secret material");
            for (Throwable suppressed : current.getSuppressed()) {
                assertSecretAbsentRecursively(suppressed, secret);
            }
        }
    }
}

package io.amscotti.bravesearch.application.port.out;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.domain.config.Credential;
import java.net.InetAddress;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The credential routing rule of the remote exchanges: stored production credentials reach
 * only the exact production origin, a loopback origin draws its token from the test-token
 * provider, and a routed provider's resolution failure surfaces as the checked exception
 * itself.
 */
final class CredentialProviderTest {

    @Test
    void aFixedProviderAnswersTheSameCredentialOnEveryResolution() throws CredentialResolutionException {
        Credential credential = Credential.of(("sentinel-" + UUID.randomUUID()).getBytes(UTF_8));
        CredentialProvider fixed = CredentialProvider.of(credential);

        assertSame(credential, fixed.resolve());
        assertSame(credential, fixed.resolve());
        assertEquals(credential, fixed.resolveWithProvenance().credential());
        assertThrows(NullPointerException.class, () -> CredentialProvider.of(null));
    }

    @Test
    void storedCredentialsRouteOnlyToTheProductionOrigin() throws CredentialResolutionException {
        Credential stored = Credential.of(("sentinel-" + UUID.randomUUID()).getBytes(UTF_8));
        Credential loopbackToken = Credential.of(("sentinel-" + UUID.randomUUID()).getBytes(UTF_8));
        CredentialProvider storedCredentials = CredentialProvider.of(stored);
        CredentialProvider loopbackTestToken = CredentialProvider.of(loopbackToken);

        assertSame(storedCredentials, CredentialProvider.routedTo(BraveApiOrigin.production(), storedCredentials, loopbackTestToken));

        BraveApiOrigin loopback = BraveApiOrigin.fromOverride(
                "http://localhost:9", host -> List.of(InetAddress.getByName("127.0.0.1")));
        CredentialProvider routed = CredentialProvider.routedTo(loopback, storedCredentials, loopbackTestToken);

        assertSame(loopbackTestToken, routed);
        assertNotSame(storedCredentials, routed);
        assertEquals(loopbackToken, routed.resolve(), "a loopback exchange draws its token from the test provider");
    }

    @Test
    void aRoutedProvidersResolutionFailureSurfacesAsTheCheckedExceptionItself() {
        CredentialResolutionException unresolved = CredentialResolutionException.missing("nowhere");
        CredentialProvider missing = new CredentialProvider() {

            @Override
            public Credential resolve() throws CredentialResolutionException {
                throw unresolved;
            }

            @Override
            public ResolvedCredential resolveWithProvenance() throws CredentialResolutionException {
                throw unresolved;
            }
        };

        CredentialProvider routed = CredentialProvider.routedTo(
                BraveApiOrigin.production(), missing, CredentialProvider.of(Credential.of("loopback".getBytes(UTF_8))));

        assertSame(unresolved, assertThrows(CredentialResolutionException.class, routed::resolve));
    }

    @Test
    void nullRoutingArgumentsAreRejected() {
        CredentialProvider fixed = CredentialProvider.of(Credential.of("loopback".getBytes(UTF_8)));

        assertThrows(NullPointerException.class, () -> CredentialProvider.routedTo(null, fixed, fixed));
        assertThrows(NullPointerException.class, () -> CredentialProvider.routedTo(BraveApiOrigin.production(), null, fixed));
        assertThrows(
                NullPointerException.class,
                () -> CredentialProvider.routedTo(BraveApiOrigin.production(), fixed, null));
    }
}

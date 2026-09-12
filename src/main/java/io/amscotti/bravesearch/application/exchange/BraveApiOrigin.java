package io.amscotti.bravesearch.application.exchange;

import io.amscotti.bravesearch.domain.config.Credential;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Immutable origin policy of the Brave API exchange.
 *
 * <p>Production traffic always goes to {@value #PRODUCTION_BASE_URL}. An override — the hidden
 * test and proxy base-url option — is accepted only as a literal loopback origin: http(s) on
 * exactly {@code 127.0.0.1}, {@code [::1]}, or {@code localhost}, with an optional port, no
 * user information, no query, no fragment, and a path that is empty or a single slash. The name
 * {@code localhost} is resolved once through the injected resolver, every answered address is
 * verified loopback, and the origin is then rewritten to a selected literal ({@code 127.0.0.1}
 * preferred, {@code [::1]} as the fallback), so after construction the held origin never
 * depends on name resolution and a DNS change cannot redirect traffic. A test or proxy
 * override must never be able to move stored credentials to an arbitrary peer, which is why
 * the acceptance rules refuse everything outside these exact spellings: uppercase or IDNA
 * hosts, dot-segment traversal, user information, and non-http(s) schemes are all usage
 * failures.
 *
 * <p>Normalization is deterministic: a trailing slash on the otherwise-empty path is dropped,
 * so {@code http://127.0.0.1:9000/} and {@code http://127.0.0.1:9000} denote one origin, and
 * the held base URI never ends with a slash. Rejection messages name the violated rule but
 * never repeat the rejected input, because that text may itself carry user information.
 */
public final class BraveApiOrigin {

    /** The one origin stored production credentials may be sent to. */
    public static final String PRODUCTION_BASE_URL = "https://api.search.brave.com/res/v1";

    private static final String LOOPBACK_IPV4 = "127.0.0.1";
    private static final String LOOPBACK_IPV6 = "[::1]";
    private static final String LOCALHOST = "localhost";

    private final URI baseUri;
    private final boolean credentialsAllowed;

    private BraveApiOrigin(URI baseUri, boolean credentialsAllowed) {
        this.baseUri = baseUri;
        this.credentialsAllowed = credentialsAllowed;
    }

    /** The production origin; the only origin that ever receives stored credentials. */
    public static BraveApiOrigin production() {
        return new BraveApiOrigin(URI.create(PRODUCTION_BASE_URL), true);
    }

    /**
     * Parses and verifies a base-URL override as a loopback origin.
     *
     * @throws InvalidOriginException when the override violates the loopback origin contract;
     *     always the usage failure kind, and never carrying the rejected text
     */
    public static BraveApiOrigin fromOverride(String rawBaseUrl, LocalhostResolver localhostResolver) {
        Objects.requireNonNull(rawBaseUrl, "rawBaseUrl");
        Objects.requireNonNull(localhostResolver, "localhostResolver");
        URI parsed = parse(rawBaseUrl);
        requireLiteralHttpScheme(parsed);
        rejectUserInformation(parsed);
        rejectQueryAndFragment(parsed);
        rejectNonRootPath(parsed);
        String host = requireLiteralLoopbackHost(parsed);
        requireUsablePort(parsed);
        String literalHost = host.equals(LOCALHOST) ? resolveLocalhostLiteral(localhostResolver) : host;
        return new BraveApiOrigin(URI.create(literalOrigin(parsed, literalHost)), false);
    }

    /** The normalized base URI every endpoint path is joined under; never ends with a slash. */
    public URI baseUri() {
        return baseUri;
    }

    /** Whether stored production credentials may be sent to this origin. */
    public boolean credentialsAllowed() {
        return credentialsAllowed;
    }

    /**
     * Routes between the stored production credential supplier and the injected loopback
     * test-token supplier: the stored credential reaches only the exact production origin,
     * while every loopback origin draws its token from the test-token supplier instead. The
     * composition injects both suppliers; the origin only enforces the routing rule.
     */
    public Supplier<Credential> credentialSource(
            Supplier<Credential> storedCredential, Supplier<Credential> loopbackTestToken) {
        Objects.requireNonNull(storedCredential, "storedCredential");
        Objects.requireNonNull(loopbackTestToken, "loopbackTestToken");
        return credentialsAllowed ? storedCredential : loopbackTestToken;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BraveApiOrigin candidate
                && baseUri.equals(candidate.baseUri)
                && credentialsAllowed == candidate.credentialsAllowed;
    }

    @Override
    public int hashCode() {
        return 31 * baseUri.hashCode() + Boolean.hashCode(credentialsAllowed);
    }

    @Override
    public String toString() {
        return "BraveApiOrigin[" + baseUri + "]";
    }

    private static URI parse(String rawBaseUrl) {
        try {
            return URI.create(rawBaseUrl);
        } catch (IllegalArgumentException malformed) {
            // the parser's own message repeats the rejected input, which may carry user
            // information, so the typed failure names the rule instead of chaining it
            throw new InvalidOriginException("base URL is not a parseable http(s) origin");
        }
    }

    private static void requireLiteralHttpScheme(URI parsed) {
        String scheme = parsed.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new InvalidOriginException("base URL scheme must be a literal http or https");
        }
    }

    private static void rejectUserInformation(URI parsed) {
        if (parsed.getUserInfo() != null) {
            throw new InvalidOriginException("base URL must not carry user information");
        }
    }

    private static void rejectQueryAndFragment(URI parsed) {
        if (parsed.getRawQuery() != null || parsed.getRawFragment() != null) {
            throw new InvalidOriginException("base URL must not carry a query or a fragment");
        }
    }

    private static void rejectNonRootPath(URI parsed) {
        String path = parsed.getPath();
        if (!(path == null || path.isEmpty() || "/".equals(path))) {
            throw new InvalidOriginException(
                    "base URL path must be empty, a single slash, or absent; dot-segment paths are not accepted");
        }
    }

    private static String requireLiteralLoopbackHost(URI parsed) {
        String host = parsed.getHost();
        if (host == null
                || !(host.equals(LOOPBACK_IPV4) || host.equals(LOOPBACK_IPV6) || host.equals(LOCALHOST))) {
            throw new InvalidOriginException(
                    "base URL host must be exactly 127.0.0.1, [::1], or lowercase localhost");
        }
        return host;
    }

    private static void requireUsablePort(URI parsed) {
        int port = parsed.getPort();
        if (port == 0 || port > 65535) {
            throw new InvalidOriginException("base URL port must be between 1 and 65535");
        }
        String authority = parsed.getRawAuthority();
        if (port == -1 && authority != null && authority.endsWith(":")) {
            // an authority ending in a colon is an empty port spelling, which the parser
            // silently treats as absent; the contract keeps it visible as a rejection
            throw new InvalidOriginException("base URL port must be between 1 and 65535");
        }
    }

    private static String resolveLocalhostLiteral(LocalhostResolver localhostResolver) {
        List<InetAddress> addresses;
        try {
            addresses = localhostResolver.resolveAll(LOCALHOST);
        } catch (UnknownHostException unresolved) {
            // the resolver's message is not repeated: it is outside this code base's control
            throw new InvalidOriginException("localhost did not resolve to any address");
        }
        if (addresses == null || addresses.isEmpty()) {
            throw new InvalidOriginException("localhost did not resolve to any address");
        }
        boolean ipv4Loopback = false;
        boolean ipv6Loopback = false;
        for (InetAddress address : addresses) {
            if (address == null || !address.isLoopbackAddress()) {
                throw new InvalidOriginException("localhost resolved to an address that is not loopback");
            }
            ipv4Loopback |= address instanceof Inet4Address;
            ipv6Loopback |= address instanceof Inet6Address;
        }
        if (ipv4Loopback) {
            return LOOPBACK_IPV4;
        }
        if (ipv6Loopback) {
            return LOOPBACK_IPV6;
        }
        throw new InvalidOriginException("localhost resolved to no IPv4 or IPv6 loopback address");
    }

    private static String literalOrigin(URI parsed, String literalHost) {
        int port = parsed.getPort();
        return parsed.getScheme() + "://" + literalHost + (port == -1 ? "" : ":" + port);
    }
}

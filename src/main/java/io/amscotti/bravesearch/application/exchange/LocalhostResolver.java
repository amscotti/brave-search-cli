package io.amscotti.bravesearch.application.exchange;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * The single name-resolution seam of the origin policy.
 *
 * <p>Resolving the localhost name is injectable so the origin can verify that every answered
 * address is loopback without depending on platform resolution in its contract tests, while
 * production wiring uses the platform resolver.
 */
@FunctionalInterface
public interface LocalhostResolver {

    /**
     * All addresses the given host resolves to.
     *
     * @throws UnknownHostException when the name does not resolve
     */
    List<InetAddress> resolveAll(String host) throws UnknownHostException;

    /** The platform resolver over {@link InetAddress#getAllByName(String)}. */
    static LocalhostResolver platform() {
        return host -> List.of(InetAddress.getAllByName(host));
    }
}

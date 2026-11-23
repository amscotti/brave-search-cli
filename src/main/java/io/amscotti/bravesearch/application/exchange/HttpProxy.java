package io.amscotti.bravesearch.application.exchange;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * An upstream HTTP proxy the exchanges travel through: its host and port, and the bypass
 * list of the origins that are reached directly instead. The one decision a proxy carries is
 * {@link #proxies(String)}, and it is deliberately narrow — a loopback target is never
 * proxied, because a loopback origin is a test override or a local proxy itself.
 *
 * <p>The proxy changes where the bytes travel, never who reads them: a production exchange
 * is tunneled through the proxy inside its own TLS session, so the subscription token is
 * sealed exactly as it is on a direct connection.
 *
 * @param host the proxy's host name or address literal
 * @param port the proxy's listening port
 * @param bypass the origins reached directly: a host, a domain (which covers its subdomains),
 *     an optional leading dot, or {@code *}; a {@code host:port} entry matches on its host
 */
public record HttpProxy(String host, int port, List<String> bypass) {

    /** The listening port of a proxy address that names none. */
    public static final int DEFAULT_PORT = 80;

    public HttpProxy {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(bypass, "bypass");
        if (host.isBlank()) {
            throw new IllegalArgumentException("proxy host must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("proxy port must be between 1 and 65535");
        }
        bypass = bypass.stream().map(String::strip).toList();
    }

    /** Whether an exchange to {@code targetHost} travels through this proxy. */
    public boolean proxies(String targetHost) {
        Objects.requireNonNull(targetHost, "targetHost");
        String target = targetHost.toLowerCase(Locale.ROOT);
        if (isLoopback(target)) {
            return false;
        }
        for (String entry : bypass) {
            if (matches(entry, target)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(String entry, String target) {
        String rule = entry.toLowerCase(Locale.ROOT);
        int portSeparator = rule.indexOf(':');
        if (portSeparator >= 0 && !rule.startsWith("[")) {
            rule = rule.substring(0, portSeparator);
        }
        if (rule.equals("*")) {
            return true;
        }
        if (rule.startsWith(".")) {
            rule = rule.substring(1);
        }
        return !rule.isEmpty() && (target.equals(rule) || target.endsWith("." + rule));
    }

    private static boolean isLoopback(String host) {
        return host.equals("localhost") || host.startsWith("127.") || host.equals("::1") || host.equals("[::1]");
    }
}

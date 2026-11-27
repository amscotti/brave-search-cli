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
        String target = normalizeHost(targetHost);
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

    private static String normalizeHost(String raw) {
        String host = raw.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]") && host.length() >= 2) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host;
    }

    private static boolean matches(String entry, String target) {
        String rule = entry.toLowerCase(Locale.ROOT);
        if (rule.startsWith("[")) {
            int closeBracket = rule.indexOf(']');
            if (closeBracket < 0) {
                return false;
            }
            if (closeBracket == rule.length() - 1 || rule.charAt(closeBracket + 1) == ':') {
                rule = rule.substring(1, closeBracket);
            } else {
                return false;
            }
        } else {
            int firstColon = rule.indexOf(':');
            if (firstColon >= 0 && firstColon == rule.lastIndexOf(':')) {
                // a single colon separates host and port; multiple colons denote an unbracketed IPv6 literal
                rule = rule.substring(0, firstColon);
            }
        }
        if (rule.equals("*")) {
            return true;
        }
        if (rule.startsWith(".")) {
            rule = rule.substring(1);
        }
        if (rule.endsWith(".")) {
            rule = rule.substring(0, rule.length() - 1);
        }
        if (rule.isEmpty()) {
            return false;
        }
        if (rule.indexOf(':') >= 0) {
            return target.equals(rule);
        }
        return target.equals(rule) || target.endsWith("." + rule);
    }

    private static boolean isLoopback(String host) {
        return host.equals("localhost") || isIpv4Loopback(host) || host.equals("::1");
    }

    /** True when {@code host} is a dotted-quad IPv4 address literal inside {@code 127.0.0.0/8}. */
    private static boolean isIpv4Loopback(String host) {
        if (!host.startsWith("127.")) {
            return false;
        }
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (int index = 1; index < 4; index++) {
            if (!isIpv4Octet(octets[index])) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIpv4Octet(String segment) {
        if (segment.isEmpty() || segment.length() > 3) {
            return false;
        }
        int value = 0;
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            value = value * 10 + (c - '0');
        }
        return value <= 255;
    }
}

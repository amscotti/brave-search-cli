package io.amscotti.bravesearch.adapter.config;

import io.amscotti.bravesearch.application.exchange.HttpProxy;
import io.amscotti.bravesearch.domain.config.LocalConfigException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The standard proxy variables as the CLI reads them, through the injected environment
 * lookup: {@code HTTPS_PROXY} (or its lowercase spelling) names the proxy every remote
 * exchange travels through, and {@code NO_PROXY} (or {@code no_proxy}) names the origins
 * reached directly. The uppercase spelling always takes precedence when present — even when
 * blank, so an explicitly blank {@code HTTPS_PROXY} disables an inherited lowercase proxy and
 * an explicitly blank {@code NO_PROXY} clears an inherited lowercase bypass list.
 *
 * <p>A value the client cannot honor is a configuration error that names the rule and never
 * the value, because a proxy address may carry credentials. The client tunnels through plain
 * HTTP proxies only: it speaks CONNECT to the proxy and never authenticates to it.
 */
public final class ProxyEnvironment {

    /** The proxy variables, in precedence order. */
    public static final List<String> PROXY_VARIABLES = List.of("HTTPS_PROXY", "https_proxy");

    /** The bypass variables, in precedence order. */
    public static final List<String> BYPASS_VARIABLES = List.of("NO_PROXY", "no_proxy");

    private static final String SCHEME_SEPARATOR = "://";

    private ProxyEnvironment() {}

    /**
     * The proxy the environment asks for, or empty when it configures none.
     *
     * @throws LocalConfigException with reason {@code MISCONFIGURED_PROXY} when the proxy
     *     variable carries a value the client cannot honor
     * @throws NullPointerException when {@code environmentLookup} is null
     */
    public static Optional<HttpProxy> httpsProxy(Function<String, String> environmentLookup) {
        Objects.requireNonNull(environmentLookup, "environmentLookup");
        String proxy = firstPresent(PROXY_VARIABLES, environmentLookup);
        if (proxy == null || proxy.isBlank()) {
            return Optional.empty();
        }
        String bypass = firstPresent(BYPASS_VARIABLES, environmentLookup);
        return Optional.of(parse(proxy, bypass == null || bypass.isBlank() ? null : bypass));
    }

    private static String firstPresent(List<String> names, Function<String, String> environmentLookup) {
        for (String name : names) {
            String value = environmentLookup.apply(name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    static HttpProxy parse(String rawProxy, String rawBypass) {
        String address = rawProxy.strip();
        String scheme = schemeOf(address);
        URI uri = parsed(address, scheme);
        String authority = uri.getRawAuthority();
        if (authority != null && authority.indexOf('@') >= 0) {
            throw LocalConfigException.misconfiguredProxy("an authenticated proxy is not supported");
        }
        String rawPath = Objects.requireNonNullElse(uri.getRawPath(), "");
        if (!rawPath.isEmpty() && !"/".equals(rawPath)) {
            throw LocalConfigException.misconfiguredProxy("the proxy address must not carry a path");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw LocalConfigException.misconfiguredProxy("the proxy address must not carry a query or fragment");
        }
        if (uri.getHost() == null) {
            throw LocalConfigException.misconfiguredProxy("the proxy address names no host");
        }
        if (uri.getPort() == -1 && authority.endsWith(":")) {
            throw LocalConfigException.misconfiguredProxy("the proxy port is outside 1 through 65535");
        }
        int port = uri.getPort() == -1 ? HttpProxy.DEFAULT_PORT : uri.getPort();
        List<String> bypass = rawBypass == null
                ? List.of()
                : Arrays.stream(rawBypass.split(",")).map(String::strip).filter(entry -> !entry.isEmpty()).toList();
        try {
            return new HttpProxy(uri.getHost(), port, bypass);
        } catch (IllegalArgumentException outOfRange) {
            throw LocalConfigException.misconfiguredProxy("the proxy port is outside 1 through 65535");
        }
    }

    private static String schemeOf(String address) {
        int separator = address.indexOf(SCHEME_SEPARATOR);
        return separator < 0 ? null : address.substring(0, separator);
    }

    private static URI parsed(String address, String scheme) {
        // a bare host:port is the plain-http proxy by definition; every other spelling must
        // say its scheme outright, so a proxy is never guessed
        URI uri;
        try {
            uri = scheme == null ? URI.create("http://" + address) : URI.create(address);
        } catch (IllegalArgumentException malformed) {
            throw LocalConfigException.misconfiguredProxy("the proxy address is not a parsable URI");
        }
        if (scheme != null && !"http".equalsIgnoreCase(scheme)) {
            throw LocalConfigException.misconfiguredProxy("only plain http proxies are supported");
        }
        return uri;
    }
}

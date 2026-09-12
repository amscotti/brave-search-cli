package io.amscotti.bravesearch.adapter.bravehttp;

import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.QueryParameter;
import io.amscotti.bravesearch.application.exchange.RequestUri;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Composes request URIs under an origin: the origin's base (carrying {@code /res/v1} for
 * production, nothing for loopback) joined with the endpoint path, plus the query.
 *
 * <p>Every query name and value is percent-encoded independently and strictly: only the
 * RFC 3986 unreserved characters (letters, digits, and {@code - . _ ~}) pass through
 * unescaped, a space becomes {@code %20} (never {@code +}), reserved characters such as
 * {@code & = ?} are encoded, and the bytes are UTF-8, so a multibyte value survives the round
 * trip unchanged. Parameters appear in insertion order and duplicates are preserved, so
 * identical inputs always compose byte-identical URIs and a caller's repeated parameters are
 * never reordered.
 *
 * <p>The redacted rendering keeps scheme, host, path, and parameter names but replaces every
 * value with a fixed marker: diagnostics can then show where a query traveled without ever
 * repeating user query text. Token-bearing headers are not part of this rendering at all —
 * they are never rendered on any diagnostic channel.
 */
public final class RequestUriBuilder {

    /** The fixed replacement for every query value in the redacted rendering. */
    private static final String REDACTED_VALUE = "<redacted>";

    private final BraveApiOrigin origin;

    public RequestUriBuilder(BraveApiOrigin origin) {
        this.origin = Objects.requireNonNull(origin, "origin");
    }

    /**
     * Composes the endpoint's URI and its redacted rendering under the given origin.
     *
     * @throws NullPointerException when the origin, the endpoint path, the parameter list, a
     *     parameter, or a parameter's name or value is null
     * @throws IllegalArgumentException when the endpoint path is blank, carries a leading or
     *     trailing slash, contains an empty inner segment, or contains dot segments
     */
    public RequestUri build(String endpointPath, List<QueryParameter> queryParameters) {
        return compose(origin, endpointPath, queryParameters);
    }

    /**
     * Composes the endpoint's URI and its redacted rendering under the given origin, without
     * an instance: composition is pure over the origin, so static call sites in the adapter
     * never need to hold a builder.
     *
     * @throws NullPointerException when the origin, the endpoint path, the parameter list, a
     *     parameter, or a parameter's name or value is null
     * @throws IllegalArgumentException when the endpoint path is blank, carries a leading or
     *     trailing slash, contains an empty inner segment, or contains dot segments
     */
    public static RequestUri compose(
            BraveApiOrigin origin, String endpointPath, List<QueryParameter> queryParameters) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(endpointPath, "endpointPath");
        Objects.requireNonNull(queryParameters, "queryParameters");
        requireWellFormedEndpoint(endpointPath);
        List<QueryParameter> parameters = queryParameters.stream()
                .map(parameter -> Objects.requireNonNull(parameter, "queryParameter"))
                .toList();

        String base = origin.baseUri().toString();
        String query = composeQuery(parameters, false);
        String redactedQuery = composeQuery(parameters, true);
        String uriText = base + "/" + endpointPath + (query.isEmpty() ? "" : "?" + query);
        String redactedText = base + "/" + endpointPath + (redactedQuery.isEmpty() ? "" : "?" + redactedQuery);
        return new RequestUri(URI.create(uriText), redactedText);
    }

    private static void requireWellFormedEndpoint(String endpointPath) {
        if (endpointPath.isBlank()) {
            throw new IllegalArgumentException("endpoint path must not be blank");
        }
        if (endpointPath.startsWith("/")) {
            throw new IllegalArgumentException("endpoint path must be relative without a leading slash");
        }
        if (endpointPath.endsWith("/")) {
            throw new IllegalArgumentException("endpoint path must not end with a slash");
        }
        for (String segment : endpointPath.split("/", -1)) {
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("endpoint path must not contain empty segments");
            }
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("endpoint path must not contain dot segments");
            }
        }
    }

    private static String composeQuery(List<QueryParameter> parameters, boolean redacted) {
        StringBuilder query = new StringBuilder();
        for (QueryParameter parameter : parameters) {
            if (!query.isEmpty()) {
                query.append('&');
            }
            query.append(encode(parameter.name()))
                    .append('=')
                    .append(redacted ? REDACTED_VALUE : encode(parameter.value()));
        }
        return query.toString();
    }

    private static String encode(String component) {
        StringBuilder encoded = new StringBuilder();
        for (byte rawByte : component.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = rawByte & 0xff;
            if (isUnreserved(unsigned)) {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%');
                if (unsigned < 16) {
                    encoded.append('0');
                }
                encoded.append(Integer.toHexString(unsigned).toUpperCase(Locale.ROOT));
            }
        }
        return encoded.toString();
    }

    private static boolean isUnreserved(int unsigned) {
        return (unsigned >= 'A' && unsigned <= 'Z')
                || (unsigned >= 'a' && unsigned <= 'z')
                || (unsigned >= '0' && unsigned <= '9')
                || unsigned == '-'
                || unsigned == '.'
                || unsigned == '_'
                || unsigned == '~';
    }
}

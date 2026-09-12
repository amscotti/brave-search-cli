package io.amscotti.bravesearch.application.exchange;

import io.amscotti.bravesearch.domain.config.Credential;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One fully composed, immutable Brave API request: method, target URI, the assembled header
 * lines, and the optional JSON body bytes.
 *
 * <p>Header assembly is deterministic and fixed: {@code X-Subscription-Token} first, then
 * {@code Accept}, {@code Accept-Encoding} (identity in raw response mode, gzip otherwise),
 * {@code User-Agent}; {@code Api-Version} follows only when a version pin was given, and
 * {@code Content-Type: application/json} closes the fixed assembly exactly on a POST carrying its
 * JSON body — a bodyless GET never has one. Endpoint-specific headers appended through
 * {@link Builder#additionalHeader(String, String)} follow the fixed set in the order given, so an
 * endpoint's own header group keeps a stable, caller-controlled order. The token arrives as a
 * {@link Credential} whose bytes are strictly round-tripping, printable, control-free UTF-8; the
 * header alphabet adds one ceiling of its own — an HTTP header value travels as ISO-8859-1
 * bytes, so a token carrying any character above U+00FF can never be sent, and
 * {@link #requireWireableToken} rejects such a token once and typed instead of letting every
 * exchange fail at send time. A token inside that alphabet is rendered only into the header
 * value from its exact bytes; the credential itself never appears in any rendering.
 *
 * <p>The User-Agent defaults to the product line from the generated version resource and stays
 * configurable per request, because the upstream may recommend browser-style agent strings
 * for device-specific behavior and the HTTP adapter decides what to send.
 */
public final class BraveApiRequest {

    /** The authentication header every normal endpoint call carries. */
    public static final String TOKEN_HEADER = "X-Subscription-Token";

    /** The accept header, whose value follows the endpoint's response type. */
    public static final String ACCEPT_HEADER = "Accept";

    /** The response-encoding negotiation header, selected by the response mode. */
    public static final String ACCEPT_ENCODING_HEADER = "Accept-Encoding";

    /** The product identification header. */
    public static final String USER_AGENT_HEADER = "User-Agent";

    /** The API version pin header, present only when explicitly requested. */
    public static final String API_VERSION_HEADER = "Api-Version";

    /** The body type header, present exactly on a POST with a JSON body. */
    public static final String CONTENT_TYPE_HEADER = "Content-Type";

    private static final String JSON_ACCEPT = "application/json";
    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final String GZIP_ENCODING = "gzip";
    private static final String IDENTITY_ENCODING = "identity";

    /** The HTTP methods the Brave API uses. */
    public enum Method {
        GET,
        POST
    }

    /** One header line in the request's deterministic assembly order. */
    public record HeaderLine(String name, String value) {}

    private final Method method;
    private final URI uri;
    private final List<HeaderLine> headers;
    private final byte[] bodyBytes;
    private final boolean rawResponseMode;

    private BraveApiRequest(
            Method method, URI uri, List<HeaderLine> headers, byte[] bodyBytes, boolean rawResponseMode) {
        this.method = method;
        this.uri = uri;
        this.headers = headers;
        this.bodyBytes = bodyBytes;
        this.rawResponseMode = rawResponseMode;
    }

    /** Starts a bodyless GET request for the given URI. */
    public static Builder get(URI uri) {
        Objects.requireNonNull(uri, "uri");
        return new Builder(Method.GET, uri, null);
    }

    /** Starts a POST request carrying the given JSON body bytes. */
    public static Builder post(URI uri, byte[] jsonBody) {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(jsonBody, "jsonBody");
        return new Builder(Method.POST, uri, jsonBody.clone());
    }

    public Method method() {
        return method;
    }

    /**
     * Enforces the header alphabet of the subscription token: an HTTP header value travels as
     * ISO-8859-1 bytes, so a credential carrying any character above U+00FF — Cyrillic, CJK,
     * emoji — can never be written into one and is rejected here, once and typed, instead of
     * failing every exchange at send time inside the transport. A {@link Credential} already
     * guarantees strictly round-tripping, printable, control-free UTF-8, so decoding here
     * yields the token's own characters and only the ceiling needs checking.
     *
     * @throws InvalidTokenException when the token carries a character above U+00FF; the
     *     message names the rule and never token material
     * @throws NullPointerException when {@code credential} is null
     */
    public static void requireWireableToken(Credential credential) {
        Objects.requireNonNull(credential, "credential");
        String decoded = new String(credential.tokenBytes(), StandardCharsets.UTF_8);
        if (decoded.chars().anyMatch(character -> character > 0xFF)) {
            throw new InvalidTokenException("subscription token carries a character an HTTP header value cannot carry");
        }
    }

    public URI uri() {
        return uri;
    }

    /** The assembled header lines in their fixed order; the token appears only in its header. */
    public List<HeaderLine> headers() {
        return headers;
    }

    /** A fresh copy of the JSON body bytes, or null on a bodyless request. */
    public byte[] bodyBytes() {
        return bodyBytes == null ? null : bodyBytes.clone();
    }

    /**
     * Whether this exchange asked for the response in raw form: the request negotiated
     * {@code Accept-Encoding: identity} and the response side skips structured parsing, so the
     * transport needs the flag to enforce the matching accept rules.
     */
    public boolean rawResponseMode() {
        return rawResponseMode;
    }

    /** Configures one request; the header assembly order is fixed by {@link #build()}. */
    public static final class Builder {

        private final Method method;
        private final URI uri;
        private final byte[] jsonBody;
        private final List<HeaderLine> appendedHeaders = new ArrayList<>();

        private Credential token;
        private String accept = JSON_ACCEPT;
        private boolean rawResponseMode;
        private String pinnedApiVersion;
        private String userAgent;

        private Builder(Method method, URI uri, byte[] jsonBody) {
            this.method = method;
            this.uri = uri;
            this.jsonBody = jsonBody;
        }

        /** Sets the credential whose exact bytes become the subscription-token header value. */
        public Builder token(Credential credential) {
            this.token = Objects.requireNonNull(credential, "credential");
            return this;
        }

        /** Overrides the accept value; the default suits JSON endpoints. */
        public Builder accept(String acceptValue) {
            this.accept = Objects.requireNonNull(acceptValue, "acceptValue");
            return this;
        }

        /** Selects the response mode: raw streams identity, parsed modes negotiate gzip. */
        public Builder rawMode(boolean rawResponseMode) {
            this.rawResponseMode = rawResponseMode;
            return this;
        }

        /** Pins the API version; the header is sent only when a pin is set. */
        public Builder apiVersion(String pinnedApiVersion) {
            this.pinnedApiVersion = Objects.requireNonNull(pinnedApiVersion, "pinnedApiVersion");
            return this;
        }

        /** Overrides the product User-Agent line. */
        public Builder userAgent(String userAgent) {
            this.userAgent = Objects.requireNonNull(userAgent, "userAgent");
            return this;
        }

        /**
         * Appends one endpoint-specific header line after the fixed assembly, in insertion
         * order; the caller guarantees the value is header-safe.
         */
        public Builder additionalHeader(String name, String value) {
            appendedHeaders.add(new HeaderLine(
                    Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value")));
            return this;
        }

        /**
         * Freezes the request.
         *
         * @throws NullPointerException when no token was set, because every Brave exchange is
         *     authenticated
         * @throws InvalidTokenException when the token carries a character an HTTP header
         *     value cannot carry
         */
        public BraveApiRequest build() {
            Objects.requireNonNull(token, "token is required for the X-Subscription-Token header");
            requireWireableToken(token);
            List<HeaderLine> assembled = new ArrayList<>();
            assembled.add(new HeaderLine(TOKEN_HEADER, new String(token.tokenBytes(), StandardCharsets.UTF_8)));
            assembled.add(new HeaderLine(ACCEPT_HEADER, accept));
            assembled.add(
                    new HeaderLine(ACCEPT_ENCODING_HEADER, rawResponseMode ? IDENTITY_ENCODING : GZIP_ENCODING));
            assembled.add(new HeaderLine(USER_AGENT_HEADER, userAgent == null ? ProductUserAgent.value() : userAgent));
            if (pinnedApiVersion != null) {
                assembled.add(new HeaderLine(API_VERSION_HEADER, pinnedApiVersion));
            }
            if (method == Method.POST) {
                assembled.add(new HeaderLine(CONTENT_TYPE_HEADER, JSON_CONTENT_TYPE));
            }
            assembled.addAll(appendedHeaders);
            return new BraveApiRequest(method, uri, List.copyOf(assembled), jsonBody, rawResponseMode);
        }
    }
}

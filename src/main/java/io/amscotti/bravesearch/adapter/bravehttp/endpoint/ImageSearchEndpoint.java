package io.amscotti.bravesearch.adapter.bravehttp.endpoint;

import io.amscotti.bravesearch.adapter.bravehttp.RequestUriBuilder;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.BraveApiRequest;
import io.amscotti.bravesearch.application.exchange.QueryParameter;
import io.amscotti.bravesearch.application.exchange.QueryParameters;
import io.amscotti.bravesearch.application.exchange.RequestUri;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.request.ImageSearchRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Assembles the images search exchange: a validated domain request becomes the wire form
 * of {@code /images/search} — always GET with percent-encoded query parameters, because
 * the upstream reference documents GET only for images: no POST form exists, so not even
 * an encoded URI beyond the shared 8,000-byte threshold switches methods here, and no
 * Goggle or location group exists that could ever force one.
 *
 * <p>The parameters render in alphabetical wire-name order — {@code count}, {@code
 * country}, {@code q}, {@code safesearch}, {@code search_lang}, {@code spellcheck} — so
 * identical requests always render the same order. Every value the caller left unset is
 * omitted rather than coerced to an upstream default, and the SafeSearch level is
 * already narrowed to the images pair {@code off|strict} by the request itself.
 */
public final class ImageSearchEndpoint {

    /** The images search endpoint path under the origin base. */
    public static final String ENDPOINT_PATH = "images/search";

    private ImageSearchEndpoint() {}

    /**
     * Assembles the GET request for the given search under the given origin without a
     * version pin; identical to
     * {@link #assemble(ImageSearchRequest, BraveApiOrigin, Credential, String)} with an
     * absent pin.
     */
    public static BraveApiRequest assemble(ImageSearchRequest request, BraveApiOrigin origin, Credential token) {
        return assemble(request, origin, token, null);
    }

    /**
     * Assembles the GET request for the given search under the given origin, pinning
     * {@code Api-Version} to the given exact spelling when one is supplied.
     *
     * @param pinnedApiVersion the exact {@code Api-Version} header value, or null for no pin
     */
    public static BraveApiRequest assemble(
            ImageSearchRequest request, BraveApiOrigin origin, Credential token, String pinnedApiVersion) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(token, "token");
        RequestUri target = RequestUriBuilder.compose(origin, ENDPOINT_PATH, queryParametersOf(request));
        BraveApiRequest.Builder assembly = BraveApiRequest.get(target.uri()).token(token);
        if (pinnedApiVersion != null) {
            assembly.apiVersion(pinnedApiVersion);
        }
        return assembly.build();
    }

    /**
     * The GET parameters, alphabetical by wire name; identical requests always render the
     * same order.
     */
    private static List<QueryParameter> queryParametersOf(ImageSearchRequest request) {
        List<QueryParameter> parameters = new ArrayList<>();
        QueryParameters.addNumber(parameters, "count", request.count());
        QueryParameters.addString(parameters, "country", request.country());
        parameters.add(new QueryParameter("q", request.query()));
        QueryParameters.addString(parameters, "safesearch", request.safeSearch() == null ? null : request.safeSearch().wireName());
        QueryParameters.addString(parameters, "search_lang", request.searchLang());
        QueryParameters.addFlag(parameters, "spellcheck", request.spellcheck());
        parameters.sort(Comparator.comparing(QueryParameter::name));
        return parameters;
    }
}

package io.amscotti.bravesearch.adapter.bravehttp.endpoint;

import io.amscotti.bravesearch.adapter.bravehttp.RequestUriBuilder;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.BraveApiRequest;
import io.amscotti.bravesearch.application.exchange.QueryParameter;
import io.amscotti.bravesearch.application.exchange.QueryParameters;
import io.amscotti.bravesearch.application.exchange.RequestUri;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.request.SuggestRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Assembles the suggest exchange: a validated domain request becomes the wire form of
 * {@code /suggest/search} — always GET with percent-encoded query parameters, because
 * the live reference documents GET only for suggest: no POST form exists, so not even an
 * encoded URI beyond the shared 8,000-byte threshold switches methods here.
 *
 * <p>The parameters render in alphabetical wire-name order — {@code count}, {@code
 * country}, {@code lang}, {@code q}, {@code rich} — so identical requests always render
 * the same order. The language hint travels under the suggest endpoint's own {@code
 * lang} wire name; the search verticals' {@code search_lang} never rides a suggest
 * request. Every value the caller left unset is omitted rather than coerced to an
 * upstream default, so Brave's count default of 5 and rich default of false stay
 * upstream decisions.
 */
public final class SuggestEndpoint {

    /** The suggest endpoint path under the origin base. */
    public static final String ENDPOINT_PATH = "suggest/search";

    private SuggestEndpoint() {}

    /**
     * Assembles the GET request for the given suggest lookup under the given origin
     * without a version pin; identical to
     * {@link #assemble(SuggestRequest, BraveApiOrigin, Credential, String)} with an
     * absent pin.
     */
    public static BraveApiRequest assemble(SuggestRequest request, BraveApiOrigin origin, Credential token) {
        return assemble(request, origin, token, null);
    }

    /**
     * Assembles the GET request for the given suggest lookup under the given origin,
     * pinning {@code Api-Version} to the given exact spelling when one is supplied.
     *
     * @param pinnedApiVersion the exact {@code Api-Version} header value, or null for no pin
     */
    public static BraveApiRequest assemble(
            SuggestRequest request, BraveApiOrigin origin, Credential token, String pinnedApiVersion) {
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
    private static List<QueryParameter> queryParametersOf(SuggestRequest request) {
        List<QueryParameter> parameters = new ArrayList<>();
        QueryParameters.addNumber(parameters, "count", request.count());
        QueryParameters.addString(parameters, "country", request.country());
        QueryParameters.addString(parameters, "lang", request.lang());
        parameters.add(new QueryParameter("q", request.query()));
        QueryParameters.addFlag(parameters, "rich", request.rich());
        parameters.sort(Comparator.comparing(QueryParameter::name));
        return parameters;
    }
}

package io.amscotti.bravesearch.adapter.bravehttp.endpoint;

import io.amscotti.bravesearch.adapter.bravehttp.RequestUriBuilder;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.BraveApiRequest;
import io.amscotti.bravesearch.application.exchange.QueryParameter;
import io.amscotti.bravesearch.application.exchange.QueryParameters;
import io.amscotti.bravesearch.application.exchange.RequestUri;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.request.PlaceAnchor;
import io.amscotti.bravesearch.domain.request.PlaceSearchRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Assembles the place search exchange: a validated domain request becomes the wire
 * form of {@code /local/place_search} — always GET with percent-encoded query
 * parameters, because the checked-in upstream contract documents GET only for place
 * search: no POST form exists, so not even an encoded URI beyond the shared 8,000-byte
 * threshold switches methods here.
 *
 * <p>The parameters render in alphabetical wire-name order — {@code count}, {@code
 * country}, {@code geoloc}, {@code latitude}, {@code location}, {@code longitude},
 * {@code q}, {@code radius}, {@code safesearch}, {@code search_lang}, {@code
 * spellcheck}, {@code ui_lang}, {@code units} — so identical requests always render
 * the same order. The query stays optional exactly as the endpoint documents: an
 * explore request carries its anchor without any {@code q}, and a query-less
 * anchor-less request — the valid broad global search — carries neither. The anchor's
 * coordinates pair serializes as the two separate float members, the place name as
 * {@code location}, and the geoloc hint exactly as {@code latitudexlongitude}; every
 * value the caller left unset is omitted rather than coerced to an upstream default,
 * so Brave's count default of 20, safesearch default of strict, and spellcheck default
 * of true stay upstream decisions.
 */
public final class PlaceSearchEndpoint {

    /** The place search endpoint path under the origin base. */
    public static final String ENDPOINT_PATH = "local/place_search";

    private PlaceSearchEndpoint() {}

    /**
     * Assembles the GET request for the given place search under the given origin
     * without a version pin; identical to
     * {@link #assemble(PlaceSearchRequest, BraveApiOrigin, Credential, String)} with an
     * absent pin.
     */
    public static BraveApiRequest assemble(PlaceSearchRequest request, BraveApiOrigin origin, Credential token) {
        return assemble(request, origin, token, null);
    }

    /**
     * Assembles the GET request for the given place search under the given origin,
     * pinning {@code Api-Version} to the given exact spelling when one is supplied.
     *
     * @param pinnedApiVersion the exact {@code Api-Version} header value, or null for no pin
     */
    public static BraveApiRequest assemble(
            PlaceSearchRequest request, BraveApiOrigin origin, Credential token, String pinnedApiVersion) {
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
     * The GET parameters, alphabetical by wire name; identical requests always render
     * the same order.
     */
    private static List<QueryParameter> queryParametersOf(PlaceSearchRequest request) {
        List<QueryParameter> parameters = new ArrayList<>();
        QueryParameters.addNumber(parameters, "count", request.count());
        QueryParameters.addString(parameters, "country", request.country());
        QueryParameters.addString(parameters, "geoloc", request.geoloc() == null ? null : request.geoloc().wireForm());
        if (request.anchor() instanceof PlaceAnchor.Coordinates coordinates) {
            parameters.add(new QueryParameter("latitude", coordinates.latitude().toString()));
            parameters.add(new QueryParameter("longitude", coordinates.longitude().toString()));
        }
        if (request.anchor() instanceof PlaceAnchor.LocationName location) {
            parameters.add(new QueryParameter("location", location.name()));
        }
        QueryParameters.addString(parameters, "q", request.query());
        QueryParameters.addString(parameters, "radius", request.radius() == null ? null : request.radius().toPlainString());
        QueryParameters.addString(
                parameters, "safesearch", request.safeSearch() == null ? null : request.safeSearch().wireName());
        QueryParameters.addString(parameters, "search_lang", request.searchLang());
        QueryParameters.addFlag(parameters, "spellcheck", request.spellcheck());
        QueryParameters.addString(parameters, "ui_lang", request.uiLang());
        QueryParameters.addString(parameters, "units", request.units() == null ? null : request.units().wireName());
        parameters.sort(Comparator.comparing(QueryParameter::name));
        return parameters;
    }
}

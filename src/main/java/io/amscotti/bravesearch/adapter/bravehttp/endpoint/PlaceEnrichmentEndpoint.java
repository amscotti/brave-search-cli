package io.amscotti.bravesearch.adapter.bravehttp.endpoint;

import io.amscotti.bravesearch.adapter.bravehttp.RequestUriBuilder;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.BraveApiRequest;
import io.amscotti.bravesearch.application.exchange.QueryParameter;
import io.amscotti.bravesearch.application.exchange.QueryParameters;
import io.amscotti.bravesearch.application.exchange.RequestUri;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.request.PlaceEnrichmentRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Assembles the place enrichment exchanges: a validated chunk request becomes the wire
 * form of {@code /local/pois} or {@code /local/descriptions} — always GET, because the
 * checked-in upstream contract documents GET only for both endpoints.
 *
 * <p>The id list rides the wire exactly as the upstream contract documents it: one
 * repeated {@code ids} query parameter per id, in the chunk's input order, duplicates
 * preserved, every value percent-encoded independently. The ids stay opaque — no
 * spelling is rewritten or refused here — and no other parameter exists on these
 * endpoints, so the repeated {@code ids} sequence is the whole query.
 */
public final class PlaceEnrichmentEndpoint {

    /** The POI detail endpoint path under the origin base. */
    public static final String DETAILS_PATH = "local/pois";

    /** The AI-description endpoint path under the origin base. */
    public static final String DESCRIPTIONS_PATH = "local/descriptions";

    private PlaceEnrichmentEndpoint() {}

    /**
     * Assembles the GET request for the given chunk under the given origin without a
     * version pin; identical to
     * {@link #assemble(PlaceEnrichmentRequest, BraveApiOrigin, Credential, String)}
     * with an absent pin.
     */
    public static BraveApiRequest assemble(
            PlaceEnrichmentRequest request, BraveApiOrigin origin, Credential token) {
        return assemble(request, origin, token, null);
    }

    /**
     * Assembles the GET request for the given chunk under the given origin, pinning
     * {@code Api-Version} to the given exact spelling when one is supplied.
     *
     * @param pinnedApiVersion the exact {@code Api-Version} header value, or null for no pin
     */
    public static BraveApiRequest assemble(
            PlaceEnrichmentRequest request, BraveApiOrigin origin, Credential token, String pinnedApiVersion) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(token, "token");
        RequestUri target = RequestUriBuilder.compose(origin, pathOf(request.kind()), queryParametersOf(request));
        BraveApiRequest.Builder assembly = BraveApiRequest.get(target.uri()).token(token);
        if (pinnedApiVersion != null) {
            assembly.apiVersion(pinnedApiVersion);
        }
        return assembly.build();
    }

    private static String pathOf(PlaceEnrichmentRequest.Kind kind) {
        return kind == PlaceEnrichmentRequest.Kind.DETAILS ? DETAILS_PATH : DESCRIPTIONS_PATH;
    }

    /**
     * The repeated {@code ids} parameters of one chunk, in its input order; the
     * sequence is the whole query, so identical requests always render the same order.
     */
    private static List<QueryParameter> queryParametersOf(PlaceEnrichmentRequest request) {
        List<QueryParameter> parameters = new ArrayList<>(request.ids().size());
        for (String id : request.ids()) {
            QueryParameters.addString(parameters, "ids", id);
        }
        return parameters;
    }
}

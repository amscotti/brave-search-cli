package io.amscotti.bravesearch.adapter.bravehttp.endpoint;

import io.amscotti.bravesearch.adapter.bravehttp.RequestUriBuilder;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.BraveApiRequest;
import io.amscotti.bravesearch.application.exchange.QueryParameter;
import io.amscotti.bravesearch.application.exchange.RequestUri;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.request.RichRequest;
import java.util.List;
import java.util.Objects;

/**
 * Assembles the rich callback exchange: a validated domain request becomes the wire form
 * of {@code /web/rich} — always GET with one percent-encoded query parameter, because
 * the checked-in upstream contract documents GET only and exactly the {@code
 * callback_key} member: the key is an opaque reference an earlier web search issued, so
 * the search verticals' {@code q} never rides this request and no default ever joins the
 * key. The callback endpoint has no reference page of its own, so this wire form is the
 * CLI's pinned spelling, proven by the wire tests rather than by a live page.
 */
public final class RichEndpoint {

    /** The rich callback endpoint path under the origin base. */
    public static final String ENDPOINT_PATH = "web/rich";

    /** The one documented wire name of the callback key. */
    public static final String CALLBACK_KEY_PARAMETER = "callback_key";

    private RichEndpoint() {}

    /**
     * Assembles the GET request for the given callback lookup under the given origin
     * without a version pin; identical to
     * {@link #assemble(RichRequest, BraveApiOrigin, Credential, String)} with an absent
     * pin.
     */
    public static BraveApiRequest assemble(RichRequest request, BraveApiOrigin origin, Credential token) {
        return assemble(request, origin, token, null);
    }

    /**
     * Assembles the GET request for the given callback lookup under the given origin,
     * pinning {@code Api-Version} to the given exact spelling when one is supplied.
     *
     * @param pinnedApiVersion the exact {@code Api-Version} header value, or null for no pin
     */
    public static BraveApiRequest assemble(
            RichRequest request, BraveApiOrigin origin, Credential token, String pinnedApiVersion) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(token, "token");
        RequestUri target = RequestUriBuilder.compose(
                origin, ENDPOINT_PATH, List.of(new QueryParameter(CALLBACK_KEY_PARAMETER, request.callbackKey())));
        BraveApiRequest.Builder assembly = BraveApiRequest.get(target.uri()).token(token);
        if (pinnedApiVersion != null) {
            assembly.apiVersion(pinnedApiVersion);
        }
        return assembly.build();
    }
}

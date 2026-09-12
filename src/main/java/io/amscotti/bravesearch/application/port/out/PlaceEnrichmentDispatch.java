package io.amscotti.bravesearch.application.port.out;

import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.request.PlaceEnrichmentRequest;
import java.time.Duration;
import java.util.Objects;

/**
 * One parsed place-enrichment chunk invocation in the shape the exchange needs: the
 * chunk's request — already sliced to at most twenty ids by the walk — the origin the
 * parsed {@code --base-url} selected, the already-resolved credential the preflight
 * drew for that origin, the two parsed budgets, and the parsed {@code Api-Version} pin
 * — {@code null} when none was given.
 *
 * <p>The credential travels inside the invocation so it resolves exactly once per run:
 * the command's preflight is the single resolution, and every chunk exchange of the
 * walk consumes the result instead of re-resolving from the environment.
 *
 * @param request the chunk's validated request, at most twenty ids of one invocation
 * @param origin the origin this chunk travels to; production unless {@code --base-url}
 *     selected a loopback override
 * @param credential the credential the preflight resolved for {@code origin}
 * @param totalTimeout the non-streaming total budget from dispatch through the complete
 *     bounded body
 * @param connectTimeout the client connection establishment budget
 * @param pinnedApiVersion the exact {@code Api-Version} date spelling to pin, or null
 *     when the invocation requested no pin
 * @param cancellation the run's cancellation latch; the exchange joins it for its
 *     whole life so a signal reaching the live exchange cancels it
 */
public record PlaceEnrichmentDispatch(
        PlaceEnrichmentRequest request,
        BraveApiOrigin origin,
        Credential credential,
        Duration totalTimeout,
        Duration connectTimeout,
        String pinnedApiVersion,
        CancellationContext cancellation) {

    public PlaceEnrichmentDispatch {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(totalTimeout, "totalTimeout");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(cancellation, "cancellation");
    }
}

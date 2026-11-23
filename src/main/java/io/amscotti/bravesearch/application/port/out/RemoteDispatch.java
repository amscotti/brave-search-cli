package io.amscotti.bravesearch.application.port.out;

import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.domain.config.Credential;
import java.time.Duration;
import java.util.Objects;

/**
 * One parsed remote invocation in the shape the exchange needs: the validated request, the
 * origin the parsed {@code --base-url} selected, the already-resolved credential the preflight
 * drew for that origin, the two parsed budgets, and the parsed {@code Api-Version} pin —
 * {@code null} when none was given.
 *
 * <p>The credential travels inside the invocation so it resolves exactly once per run: the
 * command's preflight is the single resolution, and the exchange consumes the result instead
 * of re-resolving from the environment.
 *
 * <p>The invocation is endpoint-generic on purpose: every remote endpoint needs exactly these
 * members, so one record serves them all and the endpoint's own request type is the type
 * argument.
 *
 * @param <Req> the endpoint's validated domain request
 */
public record RemoteDispatch<Req>(
        Req request,
        BraveApiOrigin origin,
        Credential credential,
        Duration totalTimeout,
        Duration connectTimeout,
        String pinnedApiVersion,
        CancellationContext cancellation) {

    public RemoteDispatch {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(totalTimeout, "totalTimeout");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(cancellation, "cancellation");
    }

    /**
     * The same invocation pinned to {@code replacement}: the form a sequential walk uses to
     * send each request under one scope — every other member is the walk's own.
     */
    public RemoteDispatch<Req> withRequest(Req replacement) {
        return new RemoteDispatch<>(
                replacement, origin, credential, totalTimeout, connectTimeout, pinnedApiVersion, cancellation);
    }
}

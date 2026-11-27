package io.amscotti.bravesearch.application.port.out;

import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.request.AnswersRequest;
import java.time.Duration;
import java.util.Objects;

/**
 * One parsed streaming answers invocation in the shape the streaming exchange needs: the
 * validated request (which carries the two stream budgets — the idle window and the
 * wall-clock limit — as its own fields), the origin the parsed {@code --base-url} selected,
 * the already-resolved credential the preflight drew for that origin, the connection
 * establishment budget, the parsed {@code Api-Version} pin — {@code null} when none was
 * given — and an optional caller-owned cancellation context.
 *
 * <p>Like the blocking dispatch, the credential travels inside the invocation so it resolves
 * exactly once per run; the streaming gateway consumes it without re-resolving from the
 * environment. The two stream deadlines are the request's own {@code idleTimeout} and
 * {@code streamTimeout} members: absent budgets resolve to the streaming exchange's effective
 * defaults inside the gateway, so a caller that pinned nothing still runs bounded. When the
 * caller supplies a {@code cancellation} context, the streaming gateway joins that latch
 * directly across both the opening headers phase and the live stream instead of registering a
 * second one.
 *
 * @param request the validated domain request carrying {@code stream=true} and the budgets
 * @param origin the origin this invocation travels to; production unless {@code --base-url}
 *     selected a loopback override
 * @param credential the credential the preflight resolved for {@code origin}
 * @param connectTimeout the client connection establishment budget
 * @param pinnedApiVersion the exact {@code Api-Version} date spelling to pin, or null when
 *     the invocation requested no pin
 * @param cancellation the caller-registered cancellation context to share across open and
 *     stream phases, or null when the streaming gateway should create and register its own
 */
public record AnswersStreamDispatch(
        AnswersRequest request,
        BraveApiOrigin origin,
        Credential credential,
        Duration connectTimeout,
        String pinnedApiVersion,
        CancellationContext cancellation) {

    public AnswersStreamDispatch {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
    }

    /** Convenience constructor when the streaming gateway creates and registers its own cancellation context. */
    public AnswersStreamDispatch(
            AnswersRequest request,
            BraveApiOrigin origin,
            Credential credential,
            Duration connectTimeout,
            String pinnedApiVersion) {
        this(request, origin, credential, connectTimeout, pinnedApiVersion, null);
    }
}

package io.amscotti.bravesearch.application.port.out;

import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.request.AnswersRequest;
import java.time.Duration;
import java.util.Objects;

/**
 * One parsed streaming answers invocation in the shape the streaming exchange needs: the
 * validated request (which carries the two stream budgets — the idle window and the
 * wall-clock limit — as its own fields), the origin the parsed {@code --base-url} selected,
 * the already-resolved credential the preflight drew for that origin, the connection
 * establishment budget, and the parsed {@code Api-Version} pin — {@code null} when none was
 * given.
 *
 * <p>Like the blocking dispatch, the credential travels inside the invocation so it resolves
 * exactly once per run; the streaming gateway consumes it without re-resolving from the
 * environment. The two stream deadlines are the request's own {@code idleTimeout} and
 * {@code streamTimeout} members: absent budgets resolve to the streaming exchange's effective
 * defaults inside the gateway, so a caller that pinned nothing still runs bounded.
 *
 * @param request the validated domain request carrying {@code stream=true} and the budgets
 * @param origin the origin this invocation travels to; production unless {@code --base-url}
 *     selected a loopback override
 * @param credential the credential the preflight resolved for {@code origin}
 * @param connectTimeout the client connection establishment budget
 * @param pinnedApiVersion the exact {@code Api-Version} date spelling to pin, or null when
 *     the invocation requested no pin
 */
public record AnswersStreamDispatch(
        AnswersRequest request,
        BraveApiOrigin origin,
        Credential credential,
        Duration connectTimeout,
        String pinnedApiVersion) {

    public AnswersStreamDispatch {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
    }
}

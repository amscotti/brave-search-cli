package io.amscotti.bravesearch.application.port.out;

import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.request.AnswersRequest;
import java.time.Duration;
import java.util.Objects;

/**
 * One parsed answers invocation in the shape the exchange needs: the validated request,
 * the origin the parsed {@code --base-url} selected, the already-resolved credential the
 * preflight drew for that origin, the parsed budgets, and the parsed {@code Api-Version}
 * pin — {@code null} when none was given.
 *
 * <p>The credential travels inside the invocation so it resolves exactly once per run: the
 * command's preflight is the single resolution, and the exchange consumes the result
 * instead of re-resolving from the environment. The two stream budgets are the streaming
 * roles' own deadlines — inert for a blocking invocation, whose only budget is the
 * non-streaming total deadline.
 *
 * @param request the validated domain request
 * @param origin the origin this invocation travels to; production unless {@code --base-url}
 *     selected a loopback override
 * @param credential the credential the preflight resolved for {@code origin}
 * @param totalTimeout the non-streaming total budget from dispatch through the complete
 *     bounded body; the deadline of a blocking answers invocation
 * @param connectTimeout the client connection establishment budget
 * @param pinnedApiVersion the exact {@code Api-Version} date spelling to pin, or null when
 *     the invocation requested no pin
 * @param cancellation the run's cancellation latch; the exchange joins it for its
 *     whole life so a signal reaching the live exchange cancels it
 */
public record AnswersDispatch(
        AnswersRequest request,
        BraveApiOrigin origin,
        Credential credential,
        Duration totalTimeout,
        Duration connectTimeout,
        String pinnedApiVersion,
        CancellationContext cancellation) {

    public AnswersDispatch {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(totalTimeout, "totalTimeout");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(cancellation, "cancellation");
    }
}

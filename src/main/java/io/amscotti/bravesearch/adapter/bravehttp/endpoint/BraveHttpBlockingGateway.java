package io.amscotti.bravesearch.adapter.bravehttp.endpoint;

import io.amscotti.bravesearch.adapter.bravehttp.BraveHttpTransport;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.BraveApiRequest;
import io.amscotti.bravesearch.application.exchange.BraveHttpResponse;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.CredentialResolutionException;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.metadata.Usage;
import java.time.Duration;
import java.util.Objects;

/**
 * The Brave HTTP gateway behind every blocking endpoint: one assembled exchange per
 * invocation over the shared transport.
 *
 * <p>The gateway is endpoint-generic because an endpoint owns exactly two decisions, and both
 * arrive as functions: {@link Assembly} turns the endpoint's domain request into the wire
 * request (the endpoint's own {@code assemble}), and {@link Projection} turns a completed
 * exchange into the endpoint's result (the result record's constructor). Everything else is
 * written once here — the credential routing, the budget, the interrupt contract, and the
 * failure passthrough — so one gateway serves every blocking endpoint and a fix to any of it
 * lands for all of them.
 *
 * <p>The credential arrives through the origin's routing between two injected providers. The
 * CLI composition injects the credential its preflight already resolved for the invocation,
 * so resolution happens once per run; the library composition injects the routing rule — the
 * stored production credential, resolved through the injected provider, reaches only the
 * exact production origin, while a loopback origin draws its token from the injected loopback
 * test-token provider, so a test or proxy override can never capture stored credential
 * material. A credential that cannot resolve on the library path is a local-configuration
 * failure, not an exchange.
 *
 * <p>The exchange travels the shared transport under the injected total budget — the
 * non-streaming deadline from dispatch through the complete bounded body, 30 seconds by
 * default — and a successful response is projected member for member: status, lossless body
 * bytes, rate-limit snapshot, usage, and the request and API version identifiers the response
 * headers offered, with no java.net.http type crossing the port. Failures keep the redacted
 * shape the transport and its classifier already produced. A thread interrupted mid-exchange
 * keeps its interrupt status and reports a TRANSPORT failure, because the port's contract is
 * an outcome, never a checked break. The gateway closes the transport it was handed on
 * {@link #close()}.
 *
 * @param <Req> the endpoint's validated domain request
 * @param <Res> the endpoint's completed-exchange type
 */
public final class BraveHttpBlockingGateway<Req, Res> implements AutoCloseable {

    /** The non-streaming total budget from dispatch through the complete bounded body. */
    public static final Duration DEFAULT_TOTAL_TIMEOUT = Duration.ofSeconds(30);

    /** How a domain request becomes the wire request; the endpoint's own {@code assemble}. */
    @FunctionalInterface
    public interface Assembly<Req> {

        /**
         * Assembles the wire request under the invocation's origin and credential.
         *
         * @param pinnedApiVersion the exact {@code Api-Version} header value, or null for no pin
         */
        BraveApiRequest assemble(Req request, BraveApiOrigin origin, Credential credential, String pinnedApiVersion);
    }

    /** How a completed exchange becomes the endpoint's result; the result record's constructor. */
    @FunctionalInterface
    public interface Projection<Res> {

        /** Projects the completed exchange member for member. */
        Res project(
                int httpStatus,
                UpstreamPayload body,
                RateLimitSnapshot rateLimits,
                Usage usage,
                String requestId,
                String apiVersion);
    }

    private final BraveHttpTransport transport;

    private final BraveApiOrigin origin;

    private final CredentialProvider credentialSource;

    private final Duration totalTimeout;

    private final String pinnedApiVersion;

    private final Assembly<Req> assembly;

    private final Projection<Res> projection;

    /**
     * The library wiring: the credential resolves per exchange through the origin's routing
     * rule between the stored provider and the loopback test-token provider.
     */
    public BraveHttpBlockingGateway(
            BraveHttpTransport transport,
            BraveApiOrigin origin,
            CredentialProvider storedCredentials,
            CredentialProvider loopbackTestToken,
            Duration totalTimeout,
            String pinnedApiVersion,
            Assembly<Req> assembly,
            Projection<Res> projection) {
        this(
                transport,
                origin,
                CredentialProvider.routedTo(origin, storedCredentials, loopbackTestToken),
                totalTimeout,
                pinnedApiVersion,
                assembly,
                projection);
    }

    /**
     * The CLI wiring: the credential the command preflight resolved for this invocation, so
     * the exchange never resolves a second time.
     */
    public BraveHttpBlockingGateway(
            BraveHttpTransport transport,
            BraveApiOrigin origin,
            Credential resolvedCredential,
            Duration totalTimeout,
            String pinnedApiVersion,
            Assembly<Req> assembly,
            Projection<Res> projection) {
        this(
                transport,
                origin,
                CredentialProvider.of(resolvedCredential),
                totalTimeout,
                pinnedApiVersion,
                assembly,
                projection);
    }

    private BraveHttpBlockingGateway(
            BraveHttpTransport transport,
            BraveApiOrigin origin,
            CredentialProvider credentialSource,
            Duration totalTimeout,
            String pinnedApiVersion,
            Assembly<Req> assembly,
            Projection<Res> projection) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.origin = Objects.requireNonNull(origin, "origin");
        this.credentialSource = Objects.requireNonNull(credentialSource, "credentialSource");
        this.totalTimeout = requirePositive(totalTimeout);
        this.pinnedApiVersion = pinnedApiVersion;
        this.assembly = Objects.requireNonNull(assembly, "assembly");
        this.projection = Objects.requireNonNull(projection, "projection");
    }

    /**
     * Performs the exchange of {@code request}.
     *
     * @throws NullPointerException when {@code request} is null
     */
    public Outcome<Res> exchange(Req request) {
        Objects.requireNonNull(request, "request");
        Credential credential;
        try {
            credential = credentialSource.resolve();
        } catch (CredentialResolutionException unresolved) {
            // a credential that cannot resolve is a local-configuration failure, not an exchange
            return new Outcome.Failure<>(FailureKind.LOCAL_CONFIG, unresolved.getMessage());
        }
        Outcome<BraveHttpResponse> exchange;
        try {
            exchange = transport.send(assembly.assemble(request, origin, credential, pinnedApiVersion), totalTimeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Outcome.Failure<>(FailureKind.TRANSPORT, "upstream exchange interrupted");
        }
        return switch (exchange) {
            case Outcome.Success<BraveHttpResponse> success -> new Outcome.Success<>(project(success.value()));
            case Outcome.Failure<BraveHttpResponse> failure -> failure.widen();
        };
    }

    /** Closes the transport the gateway rode, and through it its HTTP client. */
    @Override
    public void close() {
        transport.close();
    }

    private Res project(BraveHttpResponse response) {
        return projection.project(
                response.statusCode(),
                response.body(),
                response.rateLimits(),
                response.usage(),
                response.requestId(),
                response.apiVersion());
    }

    private static Duration requirePositive(Duration totalTimeout) {
        Objects.requireNonNull(totalTimeout, "totalTimeout");
        if (totalTimeout.isZero() || totalTimeout.isNegative()) {
            throw new IllegalArgumentException("total timeout must be positive");
        }
        return totalTimeout;
    }
}

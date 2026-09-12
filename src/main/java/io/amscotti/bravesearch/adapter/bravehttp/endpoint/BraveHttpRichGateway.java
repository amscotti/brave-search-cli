package io.amscotti.bravesearch.adapter.bravehttp.endpoint;

import io.amscotti.bravesearch.adapter.bravehttp.BraveHttpTransport;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.BraveHttpResponse;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.CredentialResolutionException;
import io.amscotti.bravesearch.application.port.out.RichPort;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.request.RichRequest;
import io.amscotti.bravesearch.domain.result.RichResult;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * The Brave HTTP gateway behind {@link RichPort}: one assembled GET exchange per rich
 * callback lookup.
 *
 * <p>The credential arrives through one injected source. The CLI composition injects the
 * credential its preflight already resolved for the invocation, so resolution happens once
 * per run; the library composition injects the origin's routing rule — the stored
 * production credential, resolved through the injected provider, reaches only the exact
 * production origin, while a loopback origin draws its token from the injected loopback
 * test-token supplier, so a test or proxy override can never capture stored credential
 * material. A credential that cannot resolve on the library path is a local-configuration
 * failure, not an exchange.
 *
 * <p>The exchange travels the shared transport under the injected total budget — the
 * non-streaming deadline from dispatch through the complete bounded body, 30 seconds by
 * default — and a successful response is projected verbatim: status, lossless body bytes,
 * rate-limit snapshot, usage, and the request and API version identifiers the response
 * headers offered, with no java.net.http type crossing the port. Failures keep the redacted
 * shape the transport and its classifier already produced. A thread interrupted
 * mid-exchange keeps its interrupt status and reports a TRANSPORT failure, because the
 * port's contract is an outcome, never a checked break. The gateway closes the transport
 * it was handed on {@link #close()}.
 */
public final class BraveHttpRichGateway implements RichPort, AutoCloseable {

    /** The non-streaming total budget from dispatch through the complete bounded body. */
    public static final Duration DEFAULT_TOTAL_TIMEOUT = Duration.ofSeconds(30);

    private final BraveHttpTransport transport;
    private final BraveApiOrigin origin;
    private final Supplier<Credential> credentialSource;
    private final Duration totalTimeout;
    private final String pinnedApiVersion;

    /**
     * The library wiring: the credential resolves per lookup through the origin's routing
     * rule between the stored provider and the loopback test-token supplier.
     */
    public BraveHttpRichGateway(
            BraveHttpTransport transport,
            BraveApiOrigin origin,
            CredentialProvider storedCredentials,
            Supplier<Credential> loopbackTestToken,
            Duration totalTimeout,
            String pinnedApiVersion) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.origin = Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(storedCredentials, "storedCredentials");
        Objects.requireNonNull(loopbackTestToken, "loopbackTestToken");
        this.credentialSource = origin.credentialSource(
                () -> resolveWrapped(storedCredentials), loopbackTestToken);
        this.totalTimeout = requirePositive(totalTimeout);
        this.pinnedApiVersion = pinnedApiVersion;
    }

    /**
     * The CLI wiring: the credential the command preflight resolved for this invocation, so
     * the exchange never resolves a second time.
     */
    public BraveHttpRichGateway(
            BraveHttpTransport transport,
            BraveApiOrigin origin,
            Credential resolvedCredential,
            Duration totalTimeout,
            String pinnedApiVersion) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.origin = Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(resolvedCredential, "resolvedCredential");
        this.credentialSource = () -> resolvedCredential;
        this.totalTimeout = requirePositive(totalTimeout);
        this.pinnedApiVersion = pinnedApiVersion;
    }

    private static Duration requirePositive(Duration totalTimeout) {
        Objects.requireNonNull(totalTimeout, "totalTimeout");
        if (totalTimeout.isZero() || totalTimeout.isNegative()) {
            throw new IllegalArgumentException("total timeout must be positive");
        }
        return totalTimeout;
    }

    @Override
    public Outcome<RichResult> rich(RichRequest request) {
        Objects.requireNonNull(request, "request");
        Credential credential;
        try {
            credential = credentialSource.get();
        } catch (CompletionException routed) {
            // the only unchecked carrier is a checked resolution failure wrapped by the
            // source; it keeps the local-configuration status instead of an exchange
            if (routed.getCause() instanceof CredentialResolutionException unresolved) {
                return new Outcome.Failure<>(FailureKind.LOCAL_CONFIG, unresolved.getMessage());
            }
            throw routed;
        }
        Outcome<BraveHttpResponse> exchange;
        try {
            exchange = transport.send(
                    RichEndpoint.assemble(request, origin, credential, pinnedApiVersion), totalTimeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Outcome.Failure<>(FailureKind.TRANSPORT, "upstream exchange interrupted");
        }
        return switch (exchange) {
            case Outcome.Success<BraveHttpResponse> success -> new Outcome.Success<>(project(success.value()));
            case Outcome.Failure<BraveHttpResponse> failure -> new Outcome.Failure<>(
                    failure.kind(), failure.diagnostic(), failure.upstream(), failure.rateLimits(), failure.httpStatus());
        };
    }

    /** Closes the transport the gateway rode, and through it its HTTP client. */
    @Override
    public void close() {
        transport.close();
    }

    private static Credential resolveWrapped(CredentialProvider provider) {
        try {
            return provider.resolve();
        } catch (CredentialResolutionException unresolved) {
            throw new CompletionException(unresolved);
        }
    }

    private static RichResult project(BraveHttpResponse response) {
        return new RichResult(
                response.statusCode(),
                response.body(),
                response.rateLimits(),
                response.usage(),
                response.requestId(),
                response.apiVersion());
    }
}

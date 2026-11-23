package io.amscotti.bravesearch.bootstrap;

import io.amscotti.bravesearch.adapter.bravehttp.BraveHttpClientFactory;
import io.amscotti.bravesearch.adapter.bravehttp.BraveHttpTransport;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.BraveHttpBlockingGateway;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.BraveHttpBlockingGateway.Assembly;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.BraveHttpBlockingGateway.Projection;
import io.amscotti.bravesearch.adapter.config.ProxyEnvironment;
import io.amscotti.bravesearch.application.exchange.HttpProxy;
import io.amscotti.bravesearch.application.exchange.ResponseLimits;
import io.amscotti.bravesearch.application.port.out.RemoteDispatch;
import io.amscotti.bravesearch.application.port.out.RemoteExchange;
import io.amscotti.bravesearch.application.port.out.RemoteWalk;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.domain.error.Outcome;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The process exchanges of the remote commands over one blocking endpoint's wire translation.
 *
 * <p>Lifecycle is scoped to the run. A single dispatch opens one transport — the HTTP client,
 * the budgets, and the run's cancellation latch — and closes it with the exchange. A
 * sequential walk instead opens that transport once for the whole run, so every request keeps
 * its connection to the peer and the walk's close releases it. Nothing outlives the run that
 * opened it, and no credential is resolved here: each dispatch already carries the one the
 * command's preflight resolved.
 */
final class RemoteExchanges {

    /**
     * Opens the transport of one run scope: its connection budget, its total budget, the
     * cancellation latch the exchange joins, and the proxy its exchanges travel through. A
     * seam, so a test can observe how many transports a run opens.
     */
    @FunctionalInterface
    interface TransportOpener {

        BraveHttpTransport open(
                Duration connectTimeout,
                Duration totalTimeout,
                CancellationContext cancellation,
                Optional<HttpProxy> proxy);
    }

    /** The opener of a composition with no proxy: a fresh client per transport over a direct connection. */
    static final TransportOpener DIRECT_TRANSPORTS =
            (connectTimeout, totalTimeout, cancellation, proxy) -> new BraveHttpTransport(
                    new BraveHttpClientFactory(connectTimeout).newClient(proxy),
                    totalTimeout,
                    ResponseLimits.production(),
                    Clock.systemUTC(),
                    cancellation);

    private RemoteExchanges() {}

    /**
     * The process exchange of one blocking endpoint over its wire translation. The proxy of
     * each run is resolved when the run opens its transport, through the injected environment
     * lookup: an unset variable means a direct connection, and a value the client cannot honor
     * fails that run as a local-configuration error, never a command that needs no exchange.
     */
    static <Req, Res> RemoteExchange<Req, Res> blocking(
            Assembly<Req> assembly, Projection<Res> projection, Function<String, String> environmentLookup) {
        Objects.requireNonNull(environmentLookup, "environmentLookup");
        return blocking(
                assembly,
                projection,
                (connectTimeout, totalTimeout, cancellation, ignored) -> DIRECT_TRANSPORTS.open(
                        connectTimeout, totalTimeout, cancellation, ProxyEnvironment.httpsProxy(environmentLookup)));
    }

    /** The same exchange over an injected transport opener. */
    static <Req, Res> RemoteExchange<Req, Res> blocking(
            Assembly<Req> assembly, Projection<Res> projection, TransportOpener transports) {
        Objects.requireNonNull(assembly, "assembly");
        Objects.requireNonNull(projection, "projection");
        Objects.requireNonNull(transports, "transports");
        return new RemoteExchange<>() {

            @Override
            public Outcome<Res> dispatch(RemoteDispatch<Req> invocation) {
                try (RemoteWalk<Req, Res> walk = openWalk(invocation)) {
                    return walk.dispatch(invocation.request());
                }
            }

            @Override
            public RemoteWalk<Req, Res> openWalk(RemoteDispatch<Req> scope) {
                Objects.requireNonNull(scope, "scope");
                BraveHttpTransport transport = transports.open(
                        scope.connectTimeout(), scope.totalTimeout(), scope.cancellation(), Optional.empty());
                BraveHttpBlockingGateway<Req, Res> gateway = new BraveHttpBlockingGateway<>(
                        transport,
                        scope.origin(),
                        scope.credential(),
                        scope.totalTimeout(),
                        scope.pinnedApiVersion(),
                        assembly,
                        projection);
                return new RemoteWalk<>() {

                    @Override
                    public Outcome<Res> dispatch(Req request) {
                        return gateway.exchange(request);
                    }

                    @Override
                    public void close() {
                        gateway.close();
                    }
                };
            }
        };
    }
}

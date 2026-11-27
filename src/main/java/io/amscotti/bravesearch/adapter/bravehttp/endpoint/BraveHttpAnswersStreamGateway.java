package io.amscotti.bravesearch.adapter.bravehttp.endpoint;

import io.amscotti.bravesearch.adapter.bravehttp.BoundedBodyHandler;
import io.amscotti.bravesearch.adapter.bravehttp.BraveHttpClientFactory;
import io.amscotti.bravesearch.adapter.bravehttp.ContentEncodingDecoder;
import io.amscotti.bravesearch.adapter.bravehttp.ResponseClassifier;
import io.amscotti.bravesearch.adapter.bravehttp.UpstreamErrorDecoder;
import io.amscotti.bravesearch.adapter.bravehttp.error.TransportFailureMapping;
import io.amscotti.bravesearch.adapter.bravehttp.metadata.RateLimitHeaderParser;
import io.amscotti.bravesearch.adapter.bravehttp.sse.AnswerStreamProcessor;
import io.amscotti.bravesearch.adapter.bravehttp.sse.SemanticStreamAssembly;
import io.amscotti.bravesearch.application.exchange.BraveApiRequest;
import io.amscotti.bravesearch.application.exchange.ContentCoding;
import io.amscotti.bravesearch.application.exchange.ExchangeCancelledException;
import io.amscotti.bravesearch.application.exchange.HttpProxy;
import io.amscotti.bravesearch.application.exchange.ResponseLimits;
import io.amscotti.bravesearch.application.exchange.ResponseRejectionException;
import io.amscotti.bravesearch.application.exchange.TotalDeadlineExceededException;
import io.amscotti.bravesearch.application.port.out.AnswersStreamDispatch;
import io.amscotti.bravesearch.application.port.out.AnswersStreamExchange;
import io.amscotti.bravesearch.application.port.out.AnswersStreamPort;
import io.amscotti.bravesearch.application.port.out.LiveStreamExchange;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.CancellationRegistry;
import io.amscotti.bravesearch.application.stream.DeadlineWatchdog;
import io.amscotti.bravesearch.application.stream.ProgressMarkingStream;
import io.amscotti.bravesearch.application.stream.StreamBodyPublisher;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.metadata.RequestMeta;
import io.amscotti.bravesearch.domain.request.AnswersRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The Brave HTTP gateway behind {@link AnswersStreamPort}: one streaming chat-completions
 * exchange per invocation, deliberately bypassing the shared blocking transport and its
 * classifier, because a streaming body must publish progressively under demand instead of
 * completing inside a bounded reader. Each exchange therefore owns its own redirect-refusing
 * {@link HttpClient}, its own single-thread daemon reader executor, and its own body
 * publisher; all three are released together on every terminal path by the composed
 * exchange's single close action.
 *
 * <p>The wire form is the endpoint's streaming assembly: POST with the event-stream accept
 * type and identity coding — one exchange serves both the raw and the semantic
 * representation, and the raw one needs decoded-exact event-stream bytes, so identity is the
 * only coding a stream ever requests; a response that answers with any other content coding
 * is a malformed response shape. A 2xx with a body must carry exactly the
 * {@code text/event-stream} media type (parameters allowed, a bodyless 2xx exempt); a
 * violation is a MALFORMED failure whose diagnostic carries the same bounded escaped preview
 * the blocking classifier produces. A non-2xx keeps the shared upstream classification with
 * its rate-limit observation, read as one bounded error body. Both open-failure reads — the
 * error body and the malformed-content-type preview — run under the run's wall deadline and
 * cancellation latch, the discipline the blocking transport gives its body reads, so a peer
 * that answers with failing or malformed headers and then withholds the body can neither
 * outlast the open nor ignore a signal.
 *
 * <p>The semantic layer arrives through the injected {@link SemanticStreamAssembly} —
 * composition-root code, because the parser, decoder, and processor it builds are concrete
 * adapters only a composition root may instantiate — and the gateway calls it once per open
 * exchange with the exchange's raw publisher and cancellation context.
 *
 * <p>Deadline semantics: the headers phase is bounded by the tighter of the fixed 30-second
 * ceiling and the run's wall budget, and a cancellation guard interrupts the opening send
 * the moment the run's cancellation context latches (a signal or a client close), so a
 * silent-header wait ends promptly instead of running out its budget. Once the body opens,
 * the run's shared cancellation context — reused when the dispatch carries one, or freshly
 * registered with the injected registry before the exchange opens and detached on every
 * terminal path — arbitrates every ending: the idle window resets on every decoded body byte
 * (the progress-marking stream below the publisher marks each read), the wall deadline is
 * absolute from the opening call and heartbeats cannot extend it, and a deadline watchdog
 * latches the matching cause and closes the exchange, which is the only portable way to
 * unblock a reader parked inside a silent body. Absent budgets resolve to the streaming
 * defaults: a 60-second idle window (300 for research) and, for research runs that pinned
 * their research seconds, a wall budget of those seconds plus a 30-second grace; ordinary
 * streams carry no default wall.
 */
public final class BraveHttpAnswersStreamGateway implements AnswersStreamPort {

    /** The idle window of an ordinary stream whose invocation supplied none. */
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(60);

    /** The idle window of a research stream whose invocation supplied none. */
    public static final Duration RESEARCH_IDLE_TIMEOUT = Duration.ofSeconds(300);

    /** The fixed ceiling of the headers phase when the run carries no tighter wall budget. */
    public static final Duration HEADERS_PHASE_TIMEOUT = Duration.ofSeconds(30);

    /** The grace a default research wall budget adds to the pinned research seconds. */
    public static final Duration RESEARCH_WALL_GRACE = Duration.ofSeconds(30);

    /** The most decoded body bytes a malformed-open diagnostic preview reads. */
    private static final int PREVIEW_READ_BYTES = 1024;

    /** The fixed diagnostic of a stream request whose assembly the JDK client refused. */
    private static final String REQUEST_ASSEMBLY_FAILURE = "outbound request assembly failed";

    /** The fixed diagnostic of a stream open ended by its run's cancellation latch. */
    private static final String CANCELLED_STREAM_FAILURE = "upstream stream exchange cancelled";

    private final CancellationRegistry registry;

    private final Clock clock;

    private final ResponseLimits limits;

    private final SemanticStreamAssembly semanticAssembly;

    private final Supplier<ExecutorService> readers;

    /** The proxy of each exchange, resolved when the exchange opens; empty means a direct connection. */
    private final Supplier<Optional<HttpProxy>> proxy;

    /** Optional executor the per-stream HTTP client rides; null uses the JDK default. */
    private final Executor clientExecutor;

    /**
     * @param readers the factory of the single-thread reader executor each open exchange
     *     owns; the process compositions hand {@link #daemonReader()}, a test hands a
     *     capturing factory
     * @param proxy the proxy of each exchange, resolved when the exchange opens; the
     *     library composition configures none
     * @throws NullPointerException when any argument is null
     */
    public BraveHttpAnswersStreamGateway(
            CancellationRegistry registry,
            Clock clock,
            ResponseLimits limits,
            SemanticStreamAssembly semanticAssembly,
            Supplier<ExecutorService> readers,
            Supplier<Optional<HttpProxy>> proxy) {
        this(registry, clock, limits, semanticAssembly, readers, proxy, Optional.empty());
    }

    /**
     * The executor-aware wiring for compositions that route the stream client's internal
     * tasks onto a chosen executor — the library composition routes its builder executor
     * policy here, and the executor's lifecycle stays owned by whoever supplied it.
     *
     * @throws NullPointerException when any argument is null
     */
    public BraveHttpAnswersStreamGateway(
            CancellationRegistry registry,
            Clock clock,
            ResponseLimits limits,
            SemanticStreamAssembly semanticAssembly,
            Supplier<ExecutorService> readers,
            Supplier<Optional<HttpProxy>> proxy,
            Executor clientExecutor) {
        this(
                registry,
                clock,
                limits,
                semanticAssembly,
                readers,
                proxy,
                Optional.of(Objects.requireNonNull(clientExecutor, "clientExecutor")));
    }

    private BraveHttpAnswersStreamGateway(
            CancellationRegistry registry,
            Clock clock,
            ResponseLimits limits,
            SemanticStreamAssembly semanticAssembly,
            Supplier<ExecutorService> readers,
            Supplier<Optional<HttpProxy>> proxy,
            Optional<Executor> clientExecutor) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.semanticAssembly = Objects.requireNonNull(semanticAssembly, "semanticAssembly");
        this.readers = Objects.requireNonNull(readers, "readers");
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.clientExecutor = clientExecutor.orElse(null);
    }

    /**
     * Opens one streaming exchange: reuses the dispatch's cancellation context (or registers a
     * fresh one when absent), performs the opening POST under the headers-phase ceiling and
     * cancellation guard, and either hands back the live exchange or the expected failure of
     * an exchange that never opened — with the registry detached (when this gateway
     * registered it) and the client released on every failing path, the rethrowing ones
     * included.
     *
     * @throws IllegalArgumentException when the invocation's request does not carry
     *     {@code stream=true}
     */
    @Override
    public Outcome<AnswersStreamExchange> stream(AnswersStreamDispatch invocation) {
        Objects.requireNonNull(invocation, "invocation");
        // resolved before anything is registered or opened: an unusable proxy value fails the
        // open as a local-configuration error and leaves nothing behind to release
        Optional<HttpProxy> route = proxy.get();
        CancellationContext provided = invocation.cancellation();
        CancellationContext cancellation = provided != null ? provided : new CancellationContext();
        if (cancellation.cancelled()) {
            return new Outcome.Failure<>(FailureKind.TRANSPORT, CANCELLED_STREAM_FAILURE);
        }
        Runnable detach = provided != null ? () -> {} : registry.register(cancellation);
        HttpClient.Builder building = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(invocation.connectTimeout())
                .proxy(route.map(BraveHttpClientFactory::proxySelector).orElse(HttpClient.Builder.NO_PROXY));
        if (clientExecutor != null) {
            building.executor(clientExecutor);
        }
        HttpClient client = building.build();
        try {
            return open(invocation, cancellation, detach, client);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            release(client, detach);
            return new Outcome.Failure<>(FailureKind.TRANSPORT, "upstream stream exchange interrupted");
        } catch (RuntimeException failedOpen) {
            // the documented stream=true rejection and any failure of the injected assembly
            // escape to the caller, but never with the registration or client stranded
            release(client, detach);
            throw failedOpen;
        }
    }

    private Outcome<AnswersStreamExchange> open(
            AnswersStreamDispatch invocation,
            CancellationContext cancellation,
            Runnable detach,
            HttpClient client)
            throws InterruptedException {
        AnswersRequest request = invocation.request();
        BraveApiRequest api = AnswersEndpoint.assembleStream(
                request, invocation.origin(), invocation.credential(), invocation.pinnedApiVersion());
        Duration idle = resolveIdle(request);
        Duration wall = resolveWall(request);
        Instant openedAt = clock.instant();
        Instant wallDeadline = wall == null ? null : openedAt.plus(wall);
        HttpRequest outgoing;
        try {
            outgoing = assemble(api, resolveHeadersTimeout(wall));
        } catch (IllegalArgumentException rejectedAssembly) {
            // the client's own message embeds the rejected header line; the fixed diagnostic is
            // the only safe rendering of an unsendable assembly
            return refuseOpen(
                    client, detach, new Outcome.Failure<>(FailureKind.INTERNAL, REQUEST_ASSEMBLY_FAILURE));
        }
        HttpResponse<InputStream> response;
        AtomicBoolean guardFired = new AtomicBoolean();
        Thread interrupter = cancellationGuard(cancellation, guardFired);
        boolean unwindingInterruption = false;
        interrupter.start();
        try {
            if (cancellation.cancelled()) {
                return refuseOpen(
                        client, detach, new Outcome.Failure<>(FailureKind.TRANSPORT, CANCELLED_STREAM_FAILURE));
            }
            response = client.send(outgoing, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException transportLevel) {
            if (cancellation.cancelled()) {
                return refuseOpen(
                        client, detach, new Outcome.Failure<>(FailureKind.TRANSPORT, CANCELLED_STREAM_FAILURE));
            }
            return refuseOpen(client, detach, TransportFailureMapping.of(transportLevel));
        } catch (InterruptedException interrupted) {
            if (cancellation.cancelled()) {
                return refuseOpen(
                        client, detach, new Outcome.Failure<>(FailureKind.TRANSPORT, CANCELLED_STREAM_FAILURE));
            }
            unwindingInterruption = true;
            throw interrupted;
        } finally {
            interrupter.interrupt();
            boolean externalInterruptDuringJoin = false;
            while (interrupter.isAlive()) {
                try {
                    interrupter.join();
                } catch (InterruptedException waited) {
                    externalInterruptDuringJoin = true;
                }
            }
            if (guardFired.get() || cancellation.cancelled()) {
                if (!unwindingInterruption) {
                    Thread.interrupted();
                }
            } else if (externalInterruptDuringJoin) {
                Thread.currentThread().interrupt();
            }
        }
        if (cancellation.cancelled()) {
            consumeQuietly(response.body());
            return refuseOpen(
                    client, detach, new Outcome.Failure<>(FailureKind.TRANSPORT, CANCELLED_STREAM_FAILURE));
        }
        int status = response.statusCode();
        HttpHeaders headers = response.headers();
        if (status < 200 || status > 299) {
            return refuseOpen(
                    client, detach, upstreamFailure(status, headers, response.body(), wallDeadline, cancellation));
        }
        if (ContentEncodingDecoder.parse(headers.allValues("Content-Encoding")) != ContentCoding.IDENTITY) {
            consumeQuietly(response.body());
            return refuseOpen(
                    client,
                    detach,
                    new Outcome.Failure<>(
                            FailureKind.MALFORMED,
                            "the stream response carried a content coding a stream exchange never requests",
                            null,
                            null,
                            status));
        }
        String contentType = headers.firstValue("Content-Type").orElse(null);
        if (!bodyless(status, headers) && !isEventStream(contentType)) {
            byte[] preview = previewPrefix(response.body(), wallDeadline, cancellation);
            return refuseOpen(
                    client,
                    detach,
                    new Outcome.Failure<>(
                            FailureKind.MALFORMED,
                            (contentType == null || contentType.isBlank()
                                    ? "response content type is missing"
                                    : "response content type is not text/event-stream")
                                    + "; body preview: \"" + ResponseClassifier.escapedPreview(preview) + "\"",
                            null,
                            null,
                            status));
        }
        return liveExchange(cancellation, detach, client, response, status, headers, idle, openedAt, wallDeadline);
    }

    private Outcome<AnswersStreamExchange> liveExchange(
            CancellationContext cancellation,
            Runnable detach,
            HttpClient client,
            HttpResponse<InputStream> response,
            int status,
            HttpHeaders headers,
            Duration idle,
            Instant openedAt,
            Instant wallDeadline) {
        ExecutorService reader = Objects.requireNonNull(readers.get(), "the reader factory answered no executor");
        AtomicReference<Instant> lastTransportActivity = new AtomicReference<>(clock.instant());
        StreamBodyPublisher rawFrames = new StreamBodyPublisher(
                new ProgressMarkingStream(response.body(), lastTransportActivity, clock),
                reader,
                cancellation,
                clock,
                idle,
                wallDeadline);
        AnswerStreamProcessor semanticFrames;
        try {
            semanticFrames = semanticAssembly.assemble(rawFrames, cancellation);
        } catch (RuntimeException failedAssembly) {
            // the exchange owns the reader it built; an assembly that cannot arm the
            // semantic layer releases it before its failure escapes to the caller
            reader.shutdownNow();
            throw failedAssembly;
        }
        LiveStreamExchange exchange = new LiveStreamExchange(
                openMeta(status, headers),
                rawFrames,
                semanticFrames,
                cancellation,
                lastTransportActivity,
                semanticFrames::lastSemanticProgress,
                reader,
                client,
                detach);
        exchange.armWatchdog(
                DeadlineWatchdog.armed(
                        exchange,
                        cancellation,
                        idle,
                        remainingWall(wallDeadline),
                        lastTransportActivity,
                        clock));
        return new Outcome.Success<>(exchange);
    }

    /** The reader executor an open exchange owns: one daemon thread, named for its exchange. */
    public static ExecutorService daemonReader() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("answers-stream-reader-" + thread.threadId());
            thread.setDaemon(true);
            return thread;
        });
    }

    private RequestMeta openMeta(int status, HttpHeaders headers) {
        return new RequestMeta(
                firstObservedValue(headers.map(), "x-request-id"),
                status,
                firstObservedValue(headers.map(), "api-version"),
                RateLimitHeaderParser.parse(headers.map(), clock).windows(),
                null);
    }

    /**
     * The classified failure of a non-2xx open, its error body read under the structured
     * limit, the run's wall deadline, and the run's cancellation latch — the same discipline
     * the blocking transport gives its body reads, so a peer that answers with failing
     * headers and then withholds the body can neither outlast the open nor ignore a signal.
     */
    private Outcome.Failure<AnswersStreamExchange> upstreamFailure(
            int status, HttpHeaders headers, InputStream body, Instant wallDeadline, CancellationContext cancellation) {
        byte[] errorBody;
        try {
            errorBody = BoundedBodyHandler.readBounded(
                    body,
                    limits.errorBytes(),
                    "response exceeds structured error limit",
                    clock,
                    wallDeadline,
                    cancellation);
        } catch (ResponseRejectionException rejected) {
            return new Outcome.Failure<>(FailureKind.MALFORMED, rejected.getMessage(), null, null, status);
        } catch (ExchangeCancelledException cancelled) {
            return new Outcome.Failure<>(FailureKind.TRANSPORT, CANCELLED_STREAM_FAILURE);
        } catch (TotalDeadlineExceededException exceeded) {
            return new Outcome.Failure<>(FailureKind.TRANSPORT, "upstream stream exchange exceeded its wall budget");
        } catch (IOException unreadable) {
            errorBody = new byte[0];
        }
        var decoded = UpstreamErrorDecoder.decode(status, errorBody);
        return new Outcome.Failure<>(
                decoded.kind(),
                decoded.diagnostic(),
                decoded.upstream(),
                RateLimitHeaderParser.parse(headers.map(), clock),
                status);
    }

    private HttpRequest assemble(BraveApiRequest api, Duration headersTimeout) {
        HttpRequest.Builder building = HttpRequest.newBuilder(api.uri()).timeout(headersTimeout);
        for (BraveApiRequest.HeaderLine header : api.headers()) {
            building.header(header.name(), header.value());
        }
        return building.POST(HttpRequest.BodyPublishers.ofByteArray(api.bodyBytes())).build();
    }

    /**
     * The stream's idle window: the request's pin, else the documented default — the
     * 300-second research window when research runs, the ordinary 60-second window
     * otherwise. Package-private because the resolved defaults are the documented contract
     * the endpoint suite pins directly.
     */
    static Duration resolveIdle(AnswersRequest request) {
        if (request.idleTimeout() != null) {
            return request.idleTimeout();
        }
        return Boolean.TRUE.equals(request.research()) ? RESEARCH_IDLE_TIMEOUT : DEFAULT_IDLE_TIMEOUT;
    }

    /**
     * The stream's wall budget: the request's pin, else — for a research run that pinned
     * its research seconds — those seconds plus the grace, and nothing at all otherwise.
     * Package-private for the same reason as {@link #resolveIdle}.
     */
    static Duration resolveWall(AnswersRequest request) {
        if (request.streamTimeout() != null) {
            return request.streamTimeout();
        }
        if (Boolean.TRUE.equals(request.research()) && request.researchMaximumSeconds() != null) {
            return Duration.ofSeconds(request.researchMaximumSeconds()).plus(RESEARCH_WALL_GRACE);
        }
        return null;
    }

    private static Duration resolveHeadersTimeout(Duration wall) {
        return wall == null || HEADERS_PHASE_TIMEOUT.compareTo(wall) < 0 ? HEADERS_PHASE_TIMEOUT : wall;
    }

    /** The wall budget still remaining at arm time, so the watchdog's clock stays absolute. */
    private Duration remainingWall(Instant wallDeadline) {
        if (wallDeadline == null) {
            return null;
        }
        Duration remaining = Duration.between(clock.instant(), wallDeadline);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    private static boolean bodyless(int status, HttpHeaders headers) {
        return status == 204 || "0".equals(headers.firstValue("Content-Length").orElse(null));
    }

    /** Exactly {@code text/event-stream} ignoring case, surrounding spaces, and parameters. */
    private static boolean isEventStream(String contentType) {
        if (contentType == null) {
            return false;
        }
        int parameters = contentType.indexOf(';');
        String mediaType = (parameters < 0 ? contentType : contentType.substring(0, parameters)).strip();
        return "text/event-stream".equalsIgnoreCase(mediaType);
    }

    /** The first value of the header spelling the given lowercase name, or null when absent. */
    private static String firstObservedValue(Map<String, List<String>> headers, String lowercasedName) {
        for (Map.Entry<String, List<String>> line : headers.entrySet()) {
            if (line.getKey().toLowerCase(Locale.ROOT).equals(lowercasedName) && !line.getValue().isEmpty()) {
                return line.getValue().getFirst();
            }
        }
        return null;
    }

    /**
     * The bounded preview of a malformed open's body: at most {@link #PREVIEW_READ_BYTES}
     * bytes, read under the run's wall deadline and cancellation latch — the preview ends
     * with whatever arrived when either fires, because the malformed verdict stands on the
     * content type alone.
     */
    private byte[] previewPrefix(InputStream body, Instant wallDeadline, CancellationContext cancellation) {
        return BoundedBodyHandler.preview(body, PREVIEW_READ_BYTES, clock, wallDeadline, cancellation);
    }

    /** Interrupts the opening send thread the moment {@code cancellation} latches. */
    private static Thread cancellationGuard(CancellationContext cancellation, AtomicBoolean guardFired) {
        Thread sender = Thread.currentThread();
        return Thread.ofVirtual()
                .name("stream-open-cancellation-guard")
                .unstarted(() -> {
                    try {
                        cancellation.await();
                    } catch (InterruptedException stoppedEarly) {
                        return;
                    }
                    guardFired.set(true);
                    sender.interrupt();
                });
    }

    private static void consumeQuietly(InputStream body) {
        try {
            body.close();
        } catch (IOException ignored) {
            // closing is best-effort cleanup on refusing paths
        }
    }

    private static <T> Outcome<T> refuseOpen(HttpClient client, Runnable detach, Outcome<T> failure) {
        release(client, detach);
        return failure;
    }

    private static void release(HttpClient client, Runnable detach) {
        client.shutdownNow();
        detach.run();
    }
}

package io.amscotti.bravesearch.adapter.bravehttp;

import io.amscotti.bravesearch.adapter.bravehttp.error.TransportFailureMapping;
import io.amscotti.bravesearch.adapter.bravehttp.metadata.AnswersUsageParser;
import io.amscotti.bravesearch.adapter.bravehttp.metadata.RateLimitHeaderParser;
import io.amscotti.bravesearch.application.exchange.BraveApiRequest;
import io.amscotti.bravesearch.application.exchange.BraveHttpResponse;
import io.amscotti.bravesearch.application.exchange.ContentCoding;
import io.amscotti.bravesearch.application.exchange.ExchangeCancelledException;
import io.amscotti.bravesearch.application.exchange.ResponseLimits;
import io.amscotti.bravesearch.application.exchange.ResponseRejectionException;
import io.amscotti.bravesearch.application.exchange.TotalDeadlineExceededException;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The single exchange transport of the Brave HTTP adapter: one redirect-refusing send per
 * composed request, the header lines attached in the request's deterministic order, and the
 * completed body bounded, decoded, and classified.
 *
 * <p>The response side runs one pipeline: the body arrives as the JDK client's response
 * stream, {@link BoundedBodyHandler} reads it while counting the bytes actually delivered —
 * closing the stream (cancelling the subscription, severing the peer) the instant the
 * applicable ceiling is exceeded, so neither an advertised {@code Content-Length} nor an
 * endless peer decides the volume — then {@link ContentEncodingDecoder} inflates an explicit
 * gzip coding under the separate decoded ceiling, and {@link ResponseClassifier} enforces the
 * accept rules of the response mode, routing non-2xx statuses into the upstream error
 * decoding. A rejection anywhere becomes a MALFORMED failure whose message names only the
 * violated rule.
 *
 * <p>Failure mapping is redacted by construction: any I/O-level break becomes a TRANSPORT
 * failure whose diagnostic carries no URI text and no headers, and any status outside 2xx is
 * classified with its structured error facts attached — a 3xx therefore reports as an upstream
 * failure instead of being followed, and authentication, rate-limit, and upstream categories
 * keep their documented statuses. The one quoted body is the bounded escaped preview of a
 * malformed 2xx (see {@link ResponseClassifier}): decoded body text only, never a header
 * value, so credential material the headers carried cannot echo through it. Request
 * assembly is guarded the same way: a header line the
 * JDK client refuses becomes an INTERNAL failure with a fixed diagnostic, because the client's
 * own message embeds the rejected line and that line may carry credential material.
 *
 * <p>The response-headers timeout bounds each send through the arrival of the response
 * headers, the same ceiling role the stream probe applies to its opening phase; the
 * composition passes its fixed ceiling, contract tests pass shorter ones, and contract tests
 * also inject kibibyte-scale {@link ResponseLimits} where production uses the defaults. A
 * per-send total budget may additionally bound the body reads through their completion; its
 * breach is its own TRANSPORT diagnostic, so the two ceiling roles never masquerade as each
 * other. A run handed in through the cancellation-aware constructor owns the same power
 * across both phases: the latch firing mid-send or mid-read unblocks and fails the exchange
 * as its own cancelled condition, so a signal reaching a live exchange ends it promptly.
 * The transport owns the HTTP client it rides and closes it on {@link #close()}; an
 * exchange attempted afterwards reports the fixed internal failure, because the JDK client
 * signals its own closed state only through an opaque IOException message.
 *
 * <p>On both classified paths the exchange's rate-limit metadata is attached: the snapshot
 * parsed from the {@code X-RateLimit-*} headers against the injected {@link Clock}, so a
 * rate-limited failure can explain itself with the windows it observed. On the success path
 * the Answers usage joins it when {@code X-Request-*} headers are present. Metadata parsing
 * is nonfatal by construction — malformed or missing headers degrade to notes and dropped
 * windows — so it can never downgrade a 2xx into a failure or change a failure's category.
 */
public final class BraveHttpTransport implements AutoCloseable {

    /** The fixed diagnostic of a request whose assembly the JDK client refused. */
    private static final String REQUEST_ASSEMBLY_FAILURE = "outbound request assembly failed";

    /** The fixed diagnostic of a total budget breached while the response body was being read. */
    private static final String TOTAL_DEADLINE_FAILURE = "total request deadline exceeded";

    /** The fixed diagnostic of an exchange whose run's cancellation latch fired mid-flight. */
    private static final String CANCELLED_EXCHANGE_FAILURE = "upstream exchange cancelled";

    /** The fixed diagnostic of an exchange attempted after the client was closed. */
    private static final String CLIENT_CLOSED_FAILURE = "the HTTP client has been closed";

    private final HttpClient client;
    private final Duration responseHeadersTimeout;
    private final ResponseLimits limits;
    private final Clock clock;
    private final CancellationContext cancellation;
    private final ReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private volatile boolean closed;

    /**
     * @throws NullPointerException when any argument is null
     * @throws IllegalArgumentException when {@code responseHeadersTimeout} is zero or negative
     */
    public BraveHttpTransport(
            HttpClient client, Duration responseHeadersTimeout, ResponseLimits limits, Clock clock) {
        // field assignment instead of constructor delegation: an adapter never instantiates
        // another adapter, not even its own class through this
        this.client = Objects.requireNonNull(client, "client");
        this.responseHeadersTimeout = Objects.requireNonNull(responseHeadersTimeout, "responseHeadersTimeout");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cancellation = null;
        if (responseHeadersTimeout.isZero() || responseHeadersTimeout.isNegative()) {
            throw new IllegalArgumentException("response headers timeout must be positive");
        }
    }

    /**
     * The exchange runs under the given cancellation latch: the moment it fires, the blocked
     * send or body read unblocks and the exchange fails as cancelled — so a signal reaching a
     * live single-request exchange ends it promptly instead of waiting out its budgets.
     *
     * @param cancellation the run's terminal-cause latch, or null when the caller runs
     *     without one
     * @throws NullPointerException when any other argument is null
     * @throws IllegalArgumentException when {@code responseHeadersTimeout} is zero or negative
     */
    public BraveHttpTransport(
            HttpClient client,
            Duration responseHeadersTimeout,
            ResponseLimits limits,
            Clock clock,
            CancellationContext cancellation) {
        this.client = Objects.requireNonNull(client, "client");
        this.responseHeadersTimeout = Objects.requireNonNull(responseHeadersTimeout, "responseHeadersTimeout");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cancellation = cancellation;
        if (responseHeadersTimeout.isZero() || responseHeadersTimeout.isNegative()) {
            throw new IllegalArgumentException("response headers timeout must be positive");
        }
    }

    /**
     * Sends the request and maps the exchange onto the outcome envelope.
     *
     * @throws InterruptedException when the calling thread is interrupted while exchanging
     */
    public Outcome<BraveHttpResponse> send(BraveApiRequest request) throws InterruptedException {
        return send(request, null);
    }

    /**
     * Sends the request under an optional total budget that bounds the response body reads:
     * a body trickling or stalling past the deadline is closed and reported as its own
     * TRANSPORT failure, distinct from the response-headers timeout, which keeps bounding
     * the opening phase regardless of this budget.
     *
     * @throws IllegalArgumentException when {@code totalTimeout} is zero or negative
     * @throws InterruptedException when the calling thread is interrupted while exchanging
     */
    public Outcome<BraveHttpResponse> send(BraveApiRequest request, Duration totalTimeout)
            throws InterruptedException {
        Objects.requireNonNull(request, "request");
        if (totalTimeout != null && (totalTimeout.isZero() || totalTimeout.isNegative())) {
            throw new IllegalArgumentException("total timeout must be positive");
        }
        Instant totalDeadline = totalTimeout == null ? null : clock.instant().plus(totalTimeout);
        HttpRequest outgoing;
        try {
            HttpRequest.Builder building = HttpRequest.newBuilder(request.uri()).timeout(responseHeadersTimeout);
            for (BraveApiRequest.HeaderLine header : request.headers()) {
                building.header(header.name(), header.value());
            }
            if (request.method() == BraveApiRequest.Method.POST) {
                building.POST(HttpRequest.BodyPublishers.ofByteArray(request.bodyBytes()));
            } else {
                building.GET();
            }
            outgoing = building.build();
        } catch (IllegalArgumentException rejectedAssembly) {
            // the client's own message embeds the rejected line; the fixed diagnostic is the
            // only safe rendering of an unsendable assembly
            return new Outcome.Failure<>(FailureKind.INTERNAL, REQUEST_ASSEMBLY_FAILURE);
        }
        lifecycle.readLock().lock();
        try {
            if (closed) {
                // the JDK client signals its closed state as a plain IOException whose message
                // is not a contract; the transport names the condition itself
                return new Outcome.Failure<>(FailureKind.INTERNAL, CLIENT_CLOSED_FAILURE);
            }
            return exchange(request, outgoing, totalDeadline);
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /**
     * The guarded exchange: runs under the lifecycle's shared read side from the closed
     * check above through the completion below, so a close arriving mid-exchange waits
     * for it instead of severing it, while concurrent exchanges still proceed together.
     */
    private Outcome<BraveHttpResponse> exchange(
            BraveApiRequest request, HttpRequest outgoing, Instant totalDeadline) throws InterruptedException {
        Thread interrupter = cancellationGuard();
        boolean unwindingInterruption = false;
        if (interrupter != null) {
            interrupter.start();
        }
        try {
            HttpResponse<InputStream> response =
                    client.send(outgoing, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            ContentCoding coding = ContentEncodingDecoder.parse(
                    response.headers().allValues("Content-Encoding"));
            byte[] decoded;
            try {
                byte[] wire = BoundedBodyHandler.readBounded(
                        response.body(),
                        wireLimitFor(status, coding),
                        breachDiagnosticFor(status, coding),
                        clock,
                        totalDeadline,
                        cancellation);
                long decodeLimit =
                        status >= 200 && status <= 299 ? limits.decodedBytes() : limits.errorBytes();
                decoded = ContentEncodingDecoder.decode(wire, coding, decodeLimit);
            } catch (ResponseRejectionException rejected) {
                return new Outcome.Failure<>(FailureKind.MALFORMED, rejected.getMessage(), null, null, status);
            } catch (TotalDeadlineExceededException exceeded) {
                return new Outcome.Failure<>(FailureKind.TRANSPORT, TOTAL_DEADLINE_FAILURE);
            } catch (ExchangeCancelledException cancelled) {
                return cancelledExchange();
            }
            return attachMetadata(
                    ResponseClassifier.classify(
                            status,
                            response.headers().firstValue("Content-Type").orElse(null),
                            request.rawResponseMode(),
                            decoded),
                    response.headers().map());
        } catch (IOException transportLevel) {
            if (cancellation != null && cancellation.cancelled()) {
                return cancelledExchange();
            }
            return TransportFailureMapping.of(transportLevel);
        } catch (InterruptedException interrupted) {
            if (cancellation != null && cancellation.cancelled()) {
                return cancelledExchange();
            }
            unwindingInterruption = true;
            throw interrupted;
        } finally {
            if (interrupter != null) {
                // stopping the guard and waiting for its exit closes the tail race: a latch
                // firing as the exchange completes can no longer land its interrupt after
                // the outcome exists, because the guard has left the stage for good
                interrupter.interrupt();
                try {
                    interrupter.join();
                } catch (InterruptedException waited) {
                    // only the guard's own tail interrupt can arrive while joining, and the
                    // clear below resolves that flag either way
                }
            }
            if (!unwindingInterruption) {
                // every non-throwing return hands its caller a clean interrupt status: the
                // guard's interrupt belongs to the exchange it armed, never to the caller
                Thread.interrupted();
            }
        }
    }

    /**
     * The send-phase arm of a latched run: the moment the latch fires, the thread blocked
     * waiting for response headers is interrupted, because no stream exists yet for the body
     * reader's watcher to close. The watch polls and stops on interrupt, and {@code send}
     * stops it and waits for its exit before returning, so a completed exchange never
     * leaves a parked guard — or the interrupt it landed — behind.
     */
    private Thread cancellationGuard() {
        if (cancellation == null) {
            return null;
        }
        Thread sender = Thread.currentThread();
        return Thread.ofVirtual()
                .name("exchange-cancellation-guard")
                .unstarted(() -> {
                    while (!cancellation.cancelled()) {
                        try {
                            Thread.sleep(25);
                        } catch (InterruptedException stoppedEarly) {
                            return;
                        }
                    }
                    sender.interrupt();
                });
    }

    /**
     * The cancelled exchange's own verdict: a TRANSPORT failure with a fixed redacted
     * diagnostic — the latched cause, carried by the run's context, decides the process
     * status — with the sender's interrupt flag cleared, because the interrupt has been
     * converted into this value.
     */
    private Outcome<BraveHttpResponse> cancelledExchange() {
        Thread.interrupted();
        return new Outcome.Failure<>(FailureKind.TRANSPORT, CANCELLED_EXCHANGE_FAILURE);
    }

    /**
     * Closes the owned HTTP client; a close arriving mid-exchange waits for its
     * completion on the lifecycle's exclusive side, and exchanges already returned are
     * unaffected.
     */
    @Override
    public void close() {
        lifecycle.writeLock().lock();
        try {
            closed = true;
            client.close();
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    /**
     * The completed exchange with its observed response metadata attached: the success path
     * carries the rate-limit snapshot, the usage when its headers are present, and the
     * request and API version identifiers the response headers offered; a failure carries
     * the rate-limit snapshot alone, because the failure envelope's only metadata is the
     * window list — the windows a 429 explains itself with. Parsers are total, so
     * attachment cannot change whether an exchange succeeded.
     */
    private Outcome<BraveHttpResponse> attachMetadata(
            Outcome<BraveHttpResponse> classified, Map<String, List<String>> headers) {
        String requestId = firstObservedValue(headers, "x-request-id");
        String apiVersion = firstObservedValue(headers, "api-version");
        return switch (classified) {
            case Outcome.Success<BraveHttpResponse> success -> new Outcome.Success<>(
                    success.value()
                            .withMetadata(
                                    RateLimitHeaderParser.parse(headers, clock),
                                    AnswersUsageParser.parse(headers),
                                    requestId,
                                    apiVersion));
            case Outcome.Failure<BraveHttpResponse> failure -> new Outcome.Failure<>(
                    failure.kind(),
                    failure.diagnostic(),
                    failure.upstream(),
                    RateLimitHeaderParser.parse(headers, clock),
                    failure.httpStatus());
        };
    }

    /** The first value of the header spelling the given lowercase name, or null when absent. */
    private static String firstObservedValue(Map<String, List<String>> headers, String lowercasedName) {
        for (Map.Entry<String, List<String>> line : headers.entrySet()) {
            if (line.getKey().toLowerCase(Locale.ROOT).equals(lowercasedName)
                    && !line.getValue().isEmpty()) {
                return line.getValue().getFirst();
            }
        }
        return null;
    }

    private long wireLimitFor(int status, ContentCoding coding) {
        if (status < 200 || status > 299) {
            return limits.errorBytes();
        }
        return coding == ContentCoding.GZIP
                ? limits.compressedBytes()
                : limits.decodedBytes();
    }

    private String breachDiagnosticFor(int status, ContentCoding coding) {
        if (status < 200 || status > 299) {
            return "response exceeds structured error limit";
        }
        return coding == ContentCoding.GZIP
                ? "response exceeds compressed limit"
                : "response exceeds decoded limit";
    }
}

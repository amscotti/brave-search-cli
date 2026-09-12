package io.amscotti.bravesearch.application.port.out;

import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.DeadlineWatchdog;
import io.amscotti.bravesearch.application.stream.StreamBodyPublisher;
import io.amscotti.bravesearch.domain.answer.AnswerStreamEvent;
import io.amscotti.bravesearch.domain.metadata.RequestMeta;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * One live streaming exchange: the bundle of everything a streaming run owns and must
 * release — the raw body publisher, the semantic layer, the shared cancellation context, the
 * reader executor, the transport, and the registry attachment — behind the exchange view the
 * port hands out. The streaming gateway composes it per open exchange; it lives here, beside
 * the port it implements, because it is transport-independent assembly over
 * application-owned machinery, and only composition roots may instantiate concrete adapters.
 *
 * <p>{@link #close()} is the single release action every terminal path converges on — the
 * caller's explicit close and a watchdog's fired deadline alike — and is idempotent: it stops
 * the deadline watchdog, cancels the body publisher (which closes the body and unblocks a
 * parked reader), shuts the reader executor down now, closes the transport, and detaches the
 * cancellation context from its registry.
 */
public final class LiveStreamExchange implements AnswersStreamExchange {

    private final RequestMeta openMeta;

    private final StreamBodyPublisher rawFrames;

    private final Flow.Publisher<AnswerStreamEvent> semanticFrames;

    private final CancellationContext cancellation;

    private final AtomicReference<Instant> lastTransportActivity;

    private final Supplier<Instant> lastSemanticProgress;

    private final ExecutorService reader;

    private final AutoCloseable transport;

    private final Runnable detachRegistry;

    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile DeadlineWatchdog watchdog;

    public LiveStreamExchange(
            RequestMeta openMeta,
            StreamBodyPublisher rawFrames,
            Flow.Publisher<AnswerStreamEvent> semanticFrames,
            CancellationContext cancellation,
            AtomicReference<Instant> lastTransportActivity,
            Supplier<Instant> lastSemanticProgress,
            ExecutorService reader,
            AutoCloseable transport,
            Runnable detachRegistry) {
        this.openMeta = Objects.requireNonNull(openMeta, "openMeta");
        this.rawFrames = Objects.requireNonNull(rawFrames, "rawFrames");
        this.semanticFrames = Objects.requireNonNull(semanticFrames, "semanticFrames");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.lastTransportActivity = Objects.requireNonNull(lastTransportActivity, "lastTransportActivity");
        this.lastSemanticProgress = Objects.requireNonNull(lastSemanticProgress, "lastSemanticProgress");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.detachRegistry = Objects.requireNonNull(detachRegistry, "detachRegistry");
    }

    /**
     * Attaches the run's deadline watchdog so {@link #close()} stops it on every terminal
     * path. A close that raced this arming found no watchdog to stop, so when the exchange
     * is already closed the arming itself stops the watchdog at once: whichever of closing
     * and arming happens last still ends with a stopped watchdog.
     */
    public void armWatchdog(DeadlineWatchdog armed) {
        DeadlineWatchdog attached = Objects.requireNonNull(armed, "armed");
        this.watchdog = attached;
        if (closed.get()) {
            attached.close();
        }
    }

    @Override
    public RequestMeta openMeta() {
        return openMeta;
    }

    @Override
    public Flow.Publisher<byte[]> decodedRawFrames() {
        return rawFrames;
    }

    @Override
    public Flow.Publisher<AnswerStreamEvent> semanticFrames() {
        return semanticFrames;
    }

    @Override
    public CancellationContext cancellation() {
        return cancellation;
    }

    @Override
    public Instant lastTransportActivity() {
        return lastTransportActivity.get();
    }

    @Override
    public Instant lastSemanticProgress() {
        return lastSemanticProgress.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        DeadlineWatchdog armed = watchdog;
        if (armed != null) {
            armed.close();
        }
        rawFrames.cancel();
        reader.shutdownNow();
        try {
            transport.close();
        } catch (Exception closeFailure) {
            // closing is the release path; a failing close still released everything else
        }
        detachRegistry.run();
    }
}

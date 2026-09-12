package io.amscotti.bravesearch.testsupport;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Scriptable HTTP byte server for exercising streaming clients against scripted responses.
 *
 * <p>A server is described fluently: a status code, response headers, then a sequence of steps
 * ({@link #writeBytes(byte[])} deliveries, {@link #flush()} boundaries, {@link #heartbeat(String)}
 * comment lines, {@link #stallUntil(CountDownLatch)} gates, {@link #delay(Duration)} pacing, and a
 * terminal {@link #abruptClose()}). {@link #start()} binds an {@link HttpServer} on the loopback
 * interface on an ephemeral port and replays the whole script for every incoming request, so
 * retrying clients observe the same scripted response.
 *
 * <p>An abrupt close cuts the connection without a body terminator: the response promises more
 * bytes than the script delivers, so the framing can never complete and clients see an
 * {@link IOException} instead of a well-formed end of body.
 *
 * <p>Requests are recorded (method, path, headers, body bytes) for assertions and release
 * {@link #awaitFirstRequest(Duration)}. Handlers run on virtual threads and close every exchange
 * explicitly, because returning from a handler without closing leaves the response terminator
 * unsent.
 */
public final class ScriptedSseServer implements AutoCloseable {

    /** Bytes the truncated framing claims but never delivers, keeping the body provably short. */
    private static final int TRUNCATION_PAD = 64;

    /** Seconds {@link #close()} lets in-flight exchanges finish before tearing the server down. */
    private static final int CLOSE_GRACE_SECONDS = 3;

    /**
     * The bound of a recorded request body: recording exists so wire-form assertions can quote
     * the exact bytes a client sent, and any request larger than this bound is itself a defect
     * worth failing loudly instead of buffering.
     */
    private static final int MAX_RECORDED_BODY_BYTES = 1024 * 1024;

    private final HttpServer server;
    private final ExecutorService handlerExecutor;
    private final List<Script> scripts;
    private final CountDownLatch headersGate;
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final CountDownLatch connectionClosed = new CountDownLatch(1);

    private ScriptedSseServer(List<Script> scripts, CountDownLatch headersGate) throws IOException {
        this.scripts = List.copyOf(scripts);
        this.headersGate = headersGate;
        this.handlerExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(handlerExecutor);
        server.createContext("/", this::handle);
        server.start();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Base address every scripted context answers on, with a trailing path separator. */
    public URI baseUrl() {
        InetSocketAddress address = server.getAddress();
        return URI.create("http://" + address.getHostString() + ":" + address.getPort() + "/");
    }

    /** Snapshot of the requests recorded so far, oldest first. */
    public List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    /**
     * Blocks until the first request has been recorded, returning {@code false} if the timeout
     * elapses without one.
     */
    public boolean awaitFirstRequest(Duration timeout) throws InterruptedException {
        return awaitRequestCount(1, timeout);
    }

    /** Waits for the requested arrival count, so an earlier client cannot satisfy a later client's wait. */
    public boolean awaitRequestCount(int expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (requests) {
            while (requests.size() < expected) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(requests, remaining);
            }
            return true;
        }
    }

    /**
     * Blocks until some handler observed a broken connection while answering — a write that
     * failed because the client went away — proving the client closed its side. Returns
     * {@code false} if the timeout elapses without such evidence.
     */
    public boolean awaitConnectionClosed(Duration timeout) throws InterruptedException {
        return connectionClosed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Stops the server after a grace period for in-flight exchanges and releases its handlers. */
    @Override
    public void close() {
        server.stop(CLOSE_GRACE_SECONDS);
        handlerExecutor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        int arrivalIndex = requests.size();
        recordRequest(exchange);
        try {
            if (headersGate != null) {
                headersGate.await();
            }
            Script script = scripts.get(Math.min(arrivalIndex, scripts.size() - 1));
            OutputStream body = openScriptedResponse(exchange, script);
            for (Step step : script.steps()) {
                step.run(body);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException failedDelivery) {
            // the client went away or the connection broke mid-script: record the disconnect
            // as evidence for the closed-connection witness; the script's job is done
            connectionClosed.countDown();
        } finally {
            exchange.close();
        }
    }

    private OutputStream openScriptedResponse(HttpExchange exchange, Script script) throws IOException {
        long promisedLength = script.truncates() ? script.byteLength() + TRUNCATION_PAD : 0;
        exchange.getResponseHeaders().putAll(script.headers());
        exchange.sendResponseHeaders(script.statusCode(), promisedLength);
        return exchange.getResponseBody();
    }

    private void recordRequest(HttpExchange exchange) throws IOException {
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, List.copyOf(values)));
        var body = exchange.getRequestBody();
        byte[] recorded = body.readNBytes(MAX_RECORDED_BODY_BYTES);
        if (body.read() != -1) {
            // recording hands byte-exact assertions their evidence, so an over-bound body
            // fails this exchange loudly rather than surviving as a truncated copy
            exchange.close();
            throw new IOException("request body passed the " + MAX_RECORDED_BODY_BYTES + "-byte recording bound");
        }
        synchronized (requests) {
            requests.add(new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestURI().toString(), headers, recorded));
            requests.notifyAll();
        }
    }

    /** Builds and starts a server that replays one scripted response to every request. */
    public static final class Builder {

        private int statusCode = 200;
        private final Headers headers = new Headers();
        private final List<Step> steps = new ArrayList<>();
        private CountDownLatch headersGate;

        public Builder statusCode(int statusCode) {
            this.statusCode = statusCode;
            return this;
        }

        public Builder header(String name, String value) {
            headers.add(name, value);
            return this;
        }

        public Builder writeBytes(byte[] bytes) {
            steps.add(new WriteBytes(bytes.clone()));
            return this;
        }

        /**
         * Writes the chunk repeatedly until the connection breaks: the script never ends on its
         * own, so only a client that stops reading (a bounded reader cancelling its
         * subscription) can end the exchange, which the closed-connection witness records.
         */
        public Builder writeBytesUntilClosed(byte[] chunk) {
            steps.add(new WriteBytesUntilClosed(chunk.clone()));
            return this;
        }

        /**
         * Streams an endless gzip member of incompressible noise until the connection breaks:
         * the member's trailer never arrives, so the server is provably still writing whenever
         * the client stops reading — the compressed-transfer form of {@link
         * #writeBytesUntilClosed(byte[])}.
         */
        public Builder writeGzippedUntilClosed() {
            steps.add(new WriteGzippedUntilClosed());
            return this;
        }

        public Builder flush() {
            steps.add(new Flush());
            return this;
        }

        public Builder stallUntil(CountDownLatch latch) {
            steps.add(new StallUntil(latch));
            return this;
        }

        /** Withholds the response headers until the latch opens: the connect-then-silence shape. */
        public Builder holdHeadersUntil(CountDownLatch latch) {
            headersGate = latch;
            return this;
        }

        public Builder heartbeat(String comment) {
            steps.add(new Heartbeat(comment));
            return this;
        }

        /**
         * Writes comment lines until the connection breaks, so a client that disconnected
         * mid-run is eventually observed by a failing handler-side write.
         */
        public Builder heartbeatUntilClosed(String comment) {
            steps.add(new HeartbeatUntilClosed(comment));
            return this;
        }

        public Builder abruptClose() {
            steps.add(new AbruptClose());
            return this;
        }

        public Builder delay(Duration duration) {
            steps.add(new Delay(duration));
            return this;
        }

        public ScriptedSseServer start() throws IOException {
            return startSequence(this);
        }

        /** Builds one response of a sequence; the builders are independent from each other. */
        private Script buildScript() {
            boolean truncates = steps.stream().anyMatch(AbruptClose.class::isInstance);
            long byteLength = steps.stream().mapToLong(Step::byteLength).sum();
            return new Script(statusCode, headers, List.copyOf(steps), truncates, byteLength);
        }
    }

    /**
     * Starts a server that serves the given response scripts in request order: the first
     * request observes the first script, the second the second, and every request beyond the
     * sequence repeats the last script — the multi-request shape a sequential client walk
     * needs, with the same tail behavior a retrying client already sees. Scripts are assigned
     * by arrival order of the recorded requests; strictly sequential clients are the intended
     * users.
     */
    public static ScriptedSseServer startSequence(Builder... responses) throws IOException {
        if (responses.length == 0) {
            throw new IllegalArgumentException("a sequential server needs at least one response script");
        }
        CountDownLatch headersGate = responses[0].headersGate;
        List<Script> scripts = new ArrayList<>(responses.length);
        for (Builder response : responses) {
            if (response.headersGate != null && response.headersGate != headersGate) {
                throw new IllegalArgumentException("one headers gate governs the whole sequence");
            }
            scripts.add(response.buildScript());
        }
        return new ScriptedSseServer(scripts, headersGate);
    }

    /**
     * One request as the server observed it, with case-insensitive multi-valued headers and
     * the request body bytes the client sent (empty when the request carried none).
     */
    public record RecordedRequest(String method, String path, Map<String, List<String>> headers, byte[] body) {

        public Optional<String> firstHeader(String name) {
            List<String> values = headers.get(name);
            return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
        }
    }

    private record Script(
            int statusCode, Headers headers, List<Step> steps, boolean truncates, long byteLength) {}

    private sealed interface Step {

        void run(OutputStream body) throws IOException, InterruptedException;

        long byteLength();
    }

    private record WriteBytes(byte[] data) implements Step {

        @Override
        public void run(OutputStream body) throws IOException {
            body.write(data);
        }

        @Override
        public long byteLength() {
            return data.length;
        }
    }

    private record Flush() implements Step {

        @Override
        public void run(OutputStream body) throws IOException {
            body.flush();
        }

        @Override
        public long byteLength() {
            return 0;
        }
    }

    private record StallUntil(CountDownLatch latch) implements Step {

        @Override
        public void run(OutputStream body) throws InterruptedException {
            latch.await();
        }

        @Override
        public long byteLength() {
            return 0;
        }
    }

    private record Heartbeat(String comment) implements Step {

        @Override
        public void run(OutputStream body) throws IOException {
            body.write((": " + comment + "\n\n").getBytes(StandardCharsets.UTF_8));
            body.flush();
        }

        @Override
        public long byteLength() {
            return (": " + comment + "\n\n").getBytes(StandardCharsets.UTF_8).length;
        }
    }

    /**
     * Keeps writing the chunk until the connection breaks: one write into a freshly closed
     * connection can still succeed locally before the peer's reset arrives, so detecting a
     * client disconnect needs repeated writes rather than a single one.
     */
    private record WriteBytesUntilClosed(byte[] data) implements Step {

        @Override
        public void run(OutputStream body) throws IOException, InterruptedException {
            while (true) {
                body.write(data);
                body.flush();
                Thread.sleep(10);
            }
        }

        @Override
        public long byteLength() {
            return 0;
        }
    }

    /**
     * Streams an endless gzip member of pseudorandom noise until the connection breaks: the
     * noise keeps the member poorly compressible, so the wire volume the client counts climbs
     * toward its compressed ceiling while the member's trailer never arrives — the transfer is
     * provably still in flight whenever the client cancels, and the next handler-side write
     * after the cancel observes the broken connection.
     */
    private record WriteGzippedUntilClosed() implements Step {

        /** Noise per iteration; also the deflate buffer, so each body write fills whole segments. */
        private static final int NOISE_BYTES = 64 * 1024;

        @Override
        public void run(OutputStream body) throws IOException, InterruptedException {
            byte[] noise = new byte[NOISE_BYTES];
            new java.util.Random(20260901L).nextBytes(noise);
            // syncFlush keeps each flush pushing deflate bytes onto the wire; the member is
            // never closed, so no trailer ever completes it. The deflate buffer matches the
            // noise size, so the body receives whole-segment writes: sub-segment sync-flushed
            // pieces each wait out one delayed-ack cycle under Nagle and cross the loopback at
            // kilobytes per second, far below the volume a contract test's cancel budget needs
            try (java.util.zip.GZIPOutputStream gzip =
                    new java.util.zip.GZIPOutputStream(body, NOISE_BYTES, true)) {
                while (true) {
                    gzip.write(noise);
                    gzip.flush();
                    Thread.sleep(10);
                }
            }
        }

        @Override
        public long byteLength() {
            return 0;
        }
    }

    /**
     * Keeps writing comment lines until the connection breaks: one write into a freshly closed
     * connection can still succeed locally before the peer's reset arrives, so detecting a
     * client disconnect needs repeated writes rather than a single one.
     */
    private record HeartbeatUntilClosed(String comment) implements Step {

        @Override
        public void run(OutputStream body) throws IOException, InterruptedException {
            byte[] line = (": " + comment + "\n\n").getBytes(StandardCharsets.UTF_8);
            while (true) {
                body.write(line);
                body.flush();
                Thread.sleep(25);
            }
        }

        @Override
        public long byteLength() {
            return 0;
        }
    }

    /**
     * Marker for cutting the connection: the response framing already promises undelivered bytes,
     * so closing the exchange in {@link #handle} ends the TCP connection without a terminator.
     */
    private record AbruptClose() implements Step {

        @Override
        public void run(OutputStream body) throws IOException {
            body.flush();
        }

        @Override
        public long byteLength() {
            return 0;
        }
    }

    private record Delay(Duration duration) implements Step {

        @Override
        public void run(OutputStream body) throws InterruptedException {
            Thread.sleep(duration);
        }

        @Override
        public long byteLength() {
            return 0;
        }
    }
}

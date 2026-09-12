package io.amscotti.bravesearch.adapter.bravehttp.endpoint;

import io.amscotti.bravesearch.application.port.out.StreamProbePort;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.StreamBodyPublisher;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HTTP transport behind {@link StreamProbePort}: one client, one redirect-refusing GET, and the
 * response body wrapped in the streaming body publisher.
 *
 * <p>Every opened exchange owns exactly two transport resources — a single-thread daemon reader
 * executor and the {@link HttpClient} — and the release action of the returned stream gives both
 * up together with the publisher's own terminal cleanup. The connect timeout bounds how long a
 * refused or unreachable loopback peer can hold the opening phase, because the cancellation
 * latch cannot interrupt a connect attempt itself; the request timeout bounds the rest of the
 * opening phase through the response headers, since a peer that accepts but never answers would
 * otherwise park {@code send} beyond any latch's reach. It follows the run's wall budget when
 * one is given and otherwise a fixed ceiling.
 */
public final class StreamProbeGateway implements StreamProbePort {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** Headers-phase ceiling when the run carries no wall budget of its own. */
    private static final Duration HEADERS_TIMEOUT = Duration.ofSeconds(30);

    @Override
    public ProbeStream open(
            URI url, CancellationContext cancellation, Duration idleTimeout, Duration wallTimeout)
            throws IOException, InterruptedException {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(cancellation, "cancellation");
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(
                    HttpRequest.newBuilder(url)
                            .header("Accept", "text/event-stream")
                            .timeout(wallTimeout == null ? HEADERS_TIMEOUT : wallTimeout)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException openingFailure) {
            client.shutdownNow();
            throw openingFailure;
        }
        int status = response.statusCode();
        if (status < 200 || status > 299) {
            response.body().close();
            client.shutdownNow();
            throw new IOException("stream probe exchange failed with status " + status);
        }
        ExecutorService reader = Executors.newSingleThreadExecutor(StreamProbeGateway::namedDaemon);
        Clock clock = Clock.systemUTC();
        Instant wallDeadline = wallTimeout == null ? null : clock.instant().plus(wallTimeout);
        StreamBodyPublisher publisher =
                new StreamBodyPublisher(response.body(), reader, cancellation, clock, idleTimeout, wallDeadline);
        return new ProbeStream(publisher, () -> {
            publisher.cancel();
            reader.shutdownNow();
            client.shutdownNow();
        });
    }

    private static Thread namedDaemon(Runnable task) {
        Thread thread = new Thread(task, "stream-probe-reader");
        thread.setDaemon(true);
        return thread;
    }
}

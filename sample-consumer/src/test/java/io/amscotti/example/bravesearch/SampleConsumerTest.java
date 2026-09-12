package io.amscotti.example.bravesearch;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.amscotti.bravesearch.api.AnswersResponse;
import io.amscotti.bravesearch.api.AnswersStreamHandle;
import io.amscotti.bravesearch.api.BraveSearchClient;
import io.amscotti.bravesearch.api.PublicStreamFrame;
import io.amscotti.bravesearch.api.WebSearchResponse;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.request.AnswersRequest;
import io.amscotti.bravesearch.domain.request.WebSearchRequest;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Proof that the published artifact works on its own: an independent project builds a
 * client purely against the library jar (plus its transitive Jackson), runs real exchanges
 * against a local fake Brave server, and observes the documented public contracts — the
 * supplied token and the metadata user_id rider on the wire, typed responses with lossless
 * upstream snapshots, snapshot independence, the blocking Answers call, and the streaming
 * Answers surface end to end: projected text and tagged frames with their parsed payload
 * tree, the terminal signal, cancelling both a live and an already-terminated stream, and
 * a clean close.
 */
final class SampleConsumerTest {

  private static final String SSE_FIRST_FRAME =
      "data: {\"choices\":[{\"delta\":{\"content\":\"Hello \"}}]}\n\n";

  private static final String SSE_REMAINDER =
      "data: {\"choices\":[{\"delta\":{\"content\":"
          + "\"<citation>{\\\"url\\\":\\\"https://example.test\\\"}</citation>world\"}}]}\n\n"
          + "data: [DONE]\n\n";

  private HttpServer server;

  private String baseUrl;

  private final AtomicReference<String> subscriptionToken = new AtomicReference<>();

  private final AtomicReference<String> answersRequestBody = new AtomicReference<>();

  /**
   * Set when a streaming exchange must hold its feed open after the first frame, so a
   * consumer can cancel a stream that has delivered a frame but has not terminated.
   */
  private final AtomicBoolean holdStreamOpen = new AtomicBoolean();

  /** Releases the withheld remainder of a held-open streaming feed. */
  private final CountDownLatch heldStreamMayFinish = new CountDownLatch(1);

  @BeforeEach
  void startFakeBrave() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      subscriptionToken.set(exchange.getRequestHeaders().getFirst("X-Subscription-Token"));
      String requestBody = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
      boolean answersCall = exchange.getRequestURI().getPath().endsWith("chat/completions");
      byte[] body;
      String contentType = "application/json";
      if (answersCall) {
        answersRequestBody.set(requestBody);
        if (requestBody.contains("\"stream\":true")) {
          contentType = "text/event-stream";
          if (holdStreamOpen.get()) {
            writeHeldOpenSseFeed(exchange);
            return;
          }
          body = sseBody();
        } else {
          body = "{\"choices\":[{\"message\":{\"content\":\"crisp answer\"}}]}".getBytes(UTF_8);
        }
      } else {
        body = ("{\"web\":{\"results\":[{\"title\":\"Bacon\",\"url\":\"https://example.test\"}],"
                + "\"exact\":0.1000000000000000000001}}")
            .getBytes(UTF_8);
      }
      exchange.getResponseHeaders().set("Content-Type", contentType);
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
      exchange.close();
    });
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stopFakeBrave() {
    heldStreamMayFinish.countDown();
    server.stop(0);
  }

  /**
   * Serves the streaming feed with its remainder withheld: the first frame is flushed
   * immediately so the consumer observes a stream that has delivered a frame but no
   * terminal, and once released the withheld remainder either lands intact or, when the
   * consumer cancelled the live exchange, meets the severed socket that cancellation left.
   */
  private void writeHeldOpenSseFeed(HttpExchange exchange) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
    exchange.sendResponseHeaders(200, 0);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(SSE_FIRST_FRAME.getBytes(UTF_8));
      out.flush();
      try {
        heldStreamMayFinish.await(30, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      out.write(SSE_REMAINDER.getBytes(UTF_8));
      out.flush();
    } catch (IOException socketSeveredByCancellation) {
      // the consumer cancelled the live exchange, so the withheld remainder has nowhere to go
    }
    exchange.close();
  }

  private static byte[] sseBody() {
    return (SSE_FIRST_FRAME + SSE_REMAINDER).getBytes(UTF_8);
  }

  private BraveSearchClient client() {
    return BraveSearchClient.builder()
        .baseUrl(baseUrl)
        .tokenSupplier(() -> Credential.of("sample-consumer-token-1".getBytes(UTF_8)))
        .build();
  }

  @Test
  void aMissingOrBlankTokenEndsTheRunWithAMisconfigurationExitStatus() {
    assertEquals(2, SampleMain.run(null), "a missing token must not look like a successful run");
    assertEquals(2, SampleMain.run("   "), "a blank token must not look like a successful run");
  }

  @Test
  void aWebSearchRunsAgainstThePublishedArtifactWithTheSuppliedToken() {
    try (BraveSearchClient client = client()) {
      Outcome<WebSearchResponse> outcome =
          client.webSearch(WebSearchRequest.builder("bacon").build());

      WebSearchResponse response = switch (outcome) {
        case Outcome.Success<WebSearchResponse> success -> success.value();
        case Outcome.Failure<WebSearchResponse> failure ->
            throw new AssertionError(failure.kind() + ": " + failure.diagnostic());
      };

      assertEquals(200, response.httpStatus());
      assertEquals(1, response.upstream().path("web").path("results").size());
      assertEquals(
          "0.1000000000000000000001",
          response.upstream().path("web").path("exact").decimalValue().toPlainString(),
          "exact-scale decimals survive the published artifact");
      assertEquals(
          "sample-consumer-token-1", subscriptionToken.get(), "the wire carried the supplied token");
    }
  }

  @Test
  void upstreamSnapshotsAreIndependentCallerOwnedTrees() {
    try (BraveSearchClient client = client()) {
      WebSearchResponse response =
          switch (client.webSearch(WebSearchRequest.builder("bacon").build())) {
            case Outcome.Success<WebSearchResponse> success -> success.value();
            case Outcome.Failure<WebSearchResponse> failure ->
                throw new AssertionError(failure.diagnostic());
          };

      ObjectNode first = (ObjectNode) response.upstream();
      ObjectNode second = (ObjectNode) response.upstream();
      first.put("injected", "mine");

      assertTrue(second.path("injected").isMissingNode(), "one snapshot never sees another's mutation");
      assertTrue(
          ((ObjectNode) response.upstream()).path("injected").isMissingNode(),
          "later snapshots stay pristine");
    }
  }

  @Test
  void theBlockingAnswersCallRoundTrips() {
    try (BraveSearchClient client = client()) {
      Outcome<AnswersResponse> outcome =
          client.answers(AnswersRequest.builder("bacon").userId("agent-7").build());

      AnswersResponse response = switch (outcome) {
        case Outcome.Success<AnswersResponse> success -> success.value();
        case Outcome.Failure<AnswersResponse> failure ->
            throw new AssertionError(failure.kind() + ": " + failure.diagnostic());
      };

      assertEquals(200, response.httpStatus());
      assertEquals(
          "crisp answer",
          response.upstream().path("choices").path(0).path("message").path("content").stringValue());
    }
  }

  @Test
  void theMetadataUserIdRiderReachesTheWire() throws Exception {
    try (BraveSearchClient client = client()) {
      assertEquals(
          200,
          switch (client.answers(AnswersRequest.builder("bacon").userId("agent-7").build())) {
            case Outcome.Success<AnswersResponse> success -> success.value().httpStatus();
            case Outcome.Failure<AnswersResponse> failure ->
                throw new AssertionError(failure.kind() + ": " + failure.diagnostic());
          },
          "the exchange whose request body is inspected succeeded");

      ObjectNode request =
          (ObjectNode) tools.jackson.databind.json.JsonMapper.builder()
              .build()
              .readTree(answersRequestBody.get());
      assertEquals(
          "agent-7",
          request.path("metadata").path("user_id").stringValue(),
          "the request body carried exactly the set metadata.user_id");
    }
  }

  @Test
  void theStreamingAnswersCallDeliversProjectedFramesAndEndsCleanly() throws Exception {
    try (BraveSearchClient client = client()) {
      Outcome<AnswersStreamHandle> opened =
          client.answersStream(AnswersRequest.builder("bacon").userId("agent-7").stream(true).build());
      AnswersStreamHandle handle = switch (opened) {
        case Outcome.Success<AnswersStreamHandle> success -> success.value();
        case Outcome.Failure<AnswersStreamHandle> failure ->
            throw new AssertionError(failure.kind() + ": " + failure.diagnostic());
      };

      FrameCollector frames = new FrameCollector();
      handle.frames().subscribe(frames);
      assertTrue(frames.terminal.await(30, TimeUnit.SECONDS), "the stream reached its terminal signal");
      assertNull(frames.failed.get(), "the stream ended by completion, not failure");

      assertEquals(
          List.of(
              new PublicStreamFrame.Text("Hello "),
              new PublicStreamFrame.Tagged(
                  "citation",
                  "{\"url\":\"https://example.test\"}",
                  tools.jackson.databind.json.JsonMapper.builder()
                      .build()
                      .readTree("{\"url\":\"https://example.test\"}")),
              new PublicStreamFrame.Text("world")),
          frames.received,
          "the published artifact projects text and tagged frames with the parsed payload tree");

      handle.cancellation().cancel();
      handle.cancellation().cancel();
      handle.close();
      assertEquals(
          1,
          frames.terminalSignals.get(),
          "cancelling after natural completion is idempotent: no second terminal signal lands");
    }
  }

  @Test
  void cancellingALiveStreamEndsItWithoutAFailureSignalOrTerminal() throws Exception {
    holdStreamOpen.set(true);
    try (BraveSearchClient client = client()) {
      Outcome<AnswersStreamHandle> opened =
          client.answersStream(AnswersRequest.builder("bacon").userId("agent-7").stream(true).build());
      AnswersStreamHandle handle = switch (opened) {
        case Outcome.Success<AnswersStreamHandle> success -> success.value();
        case Outcome.Failure<AnswersStreamHandle> failure ->
            throw new AssertionError(failure.kind() + ": " + failure.diagnostic());
      };

      FrameCollector frames = new FrameCollector();
      handle.frames().subscribe(frames);
      assertTrue(
          aFrameArrived(frames), "the held-open stream delivered a frame while still live");
      assertFalse(
          handle.cancellation().cancelled(),
          "a stream that delivered frames but no terminal yet has no terminal cause latched");

      handle.cancellation().cancel();
      handle.cancellation().cancel();

      assertTrue(
          handle.cancellation().cancelled(),
          "cancelling a live stream latches the terminal cause, idempotently");
      heldStreamMayFinish.countDown();

      assertFalse(
          frames.terminal.await(2, TimeUnit.SECONDS),
          "a cancelled stream never delivers the natural terminal it cancelled");
      assertNull(frames.failed.get(), "cancellation ends the run without a failure signal");
      assertEquals(
          List.of(new PublicStreamFrame.Text("Hello ")),
          frames.received,
          "no withheld frame lands after the cancellation");
      handle.close();
    } finally {
      heldStreamMayFinish.countDown();
    }
  }

  /** Polls until the collector holds a frame or a generous deadline passes. */
  private static boolean aFrameArrived(FrameCollector frames) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (frames.received.isEmpty()) {
      if (System.nanoTime() >= deadline) {
        return false;
      }
      TimeUnit.MILLISECONDS.sleep(10);
    }
    return true;
  }

  private static final class FrameCollector implements Flow.Subscriber<PublicStreamFrame> {

    final List<PublicStreamFrame> received = new CopyOnWriteArrayList<>();

    final CountDownLatch terminal = new CountDownLatch(1);

    final AtomicInteger terminalSignals = new AtomicInteger();

    final AtomicReference<Throwable> failed = new AtomicReference<>();

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(PublicStreamFrame frame) {
      received.add(frame);
    }

    @Override
    public void onError(Throwable failure) {
      failed.set(failure);
      terminalSignals.incrementAndGet();
      terminal.countDown();
    }

    @Override
    public void onComplete() {
      terminalSignals.incrementAndGet();
      terminal.countDown();
    }
  }
}

package io.amscotti.bravesearch.adapter.bravehttp.endpoint;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.bravehttp.BraveHttpClientFactory;
import io.amscotti.bravesearch.adapter.bravehttp.BraveHttpTransport;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.ExpectedUserAgent;
import io.amscotti.bravesearch.application.exchange.ResponseLimits;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.CredentialResolutionException;
import io.amscotti.bravesearch.application.port.out.ResolvedCredential;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.request.Freshness;
import io.amscotti.bravesearch.domain.request.SafeSearch;
import io.amscotti.bravesearch.domain.request.Units;
import io.amscotti.bravesearch.domain.request.WebSearchRequest;
import io.amscotti.bravesearch.domain.result.WebSearchResult;
import io.amscotti.bravesearch.testsupport.ScriptedSseServer;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Loopback contract of the web search gateway: the assembled request reaches the wire with
 * its exact request line, headers, and encoded query; the loopback origin draws its token
 * from the test supplier while the stored credential stays off the wire; success projects
 * the observed exchange (status, lossless body, rate-limit snapshot, usage, request and
 * version identifiers) across the port; failing exchanges keep the classifier's categories;
 * an unresolvable stored credential is a local-configuration failure; and the injected total
 * deadline bounds a stalled body. Every exchange rides its own single-use sentinel token,
 * proven absent from every failure diagnostic.
 */
final class BraveHttpWebSearchGatewayTest {

    private static final Duration GENEROUS_HEADERS_TIMEOUT = Duration.ofSeconds(10);

    /** The pinned full-parameter request line under a loopback origin. */
    private static final String FULL_REQUEST_LINE = "/web/search"
            + "?count=7&country=DE&enable_rich_callback=true&extra_snippets=true"
            + "&freshness=2026-01-01to2026-02-28&include_fetch_metadata=true"
            + "&offset=2&operators=false&q=hello%20world&result_filter=discussions%2Cvideos"
            + "&safesearch=strict&search_lang=de&spellcheck=false&text_decorations=false"
            + "&ui_lang=de-DE&units=imperial";

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void theFullRequestReachesTheWireExactlyAndTheResponseFlowsThrough() throws Exception {
        String loopbackSentinel = "loopback-sentinel-" + UUID.randomUUID();
        String storedSentinel = "stored-sentinel-" + UUID.randomUUID();
        byte[] body = "{\"query\":{\"original\":\"café ☕\"},\"web\":{\"results\":[{}]}}".getBytes(UTF_8);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .header("X-Request-Id", "req-opaque-17")
                .header("Api-Version", "2026-08-01")
                .header("X-RateLimit-Limit", "1")
                .header("X-RateLimit-Policy", "request")
                .header("X-RateLimit-Remaining", "0")
                .header("X-RateLimit-Reset", "7")
                .writeBytes(body)
                .start()) {

            Outcome<WebSearchResult> outcome = gateway(
                            server,
                            new FixedCredentials(Credential.of(storedSentinel.getBytes(UTF_8))),
                            () -> Credential.of(loopbackSentinel.getBytes(UTF_8)),
                            Duration.ofSeconds(10))
                    .search(fullRequest());

            WebSearchResult result = valueOf(outcome);
            assertEquals(200, result.httpStatus());
            assertArrayEquals(body, result.body().toByteArray(), "the lossless body rides the result");
            assertEquals("req-opaque-17", result.requestId());
            assertEquals("2026-08-01", result.apiVersion());
            assertEquals(1, result.rateLimits().windows().size());
            assertEquals("request", result.rateLimits().windows().getFirst().policy());
            // the request-id header belongs to the X-Request-* family, so the usage parser
            // preserves it verbatim as an unknown field alongside the projection above
            assertEquals(
                    java.util.Map.of("x-request-id", "req-opaque-17"),
                    result.usage().unknownFields(),
                    "unrecognized X-Request-* headers ride the usage verbatim");

            ScriptedSseServer.RecordedRequest recorded = server.requests().getFirst();
            assertEquals("GET", recorded.method());
            assertEquals(FULL_REQUEST_LINE, recorded.path());
            assertEquals(
                    loopbackSentinel,
                    recorded.firstHeader("X-Subscription-Token").orElseThrow(),
                    "a loopback exchange draws its token from the test supplier, never the stored credential");
            assertEquals("application/json", recorded.firstHeader("Accept").orElseThrow());
            assertEquals("gzip", recorded.firstHeader("Accept-Encoding").orElseThrow());
            assertEquals(ExpectedUserAgent.fromVersionResource(), recorded.firstHeader("User-Agent").orElseThrow());
            assertEquals("Seattle", recorded.firstHeader("X-Loc-City").orElseThrow());
            assertEquals("US", recorded.firstHeader("X-Loc-Country").orElseThrow());
            assertEquals("47.6062", recorded.firstHeader("X-Loc-Lat").orElseThrow());
            assertEquals("-122.3321", recorded.firstHeader("X-Loc-Long").orElseThrow());
            assertEquals("98101", recorded.firstHeader("X-Loc-Postal-Code").orElseThrow());
            assertEquals("WA", recorded.firstHeader("X-Loc-State").orElseThrow());
            assertEquals("Washington", recorded.firstHeader("X-Loc-State-Name").orElseThrow());
            assertEquals("America/Los_Angeles", recorded.firstHeader("X-Loc-Timezone").orElseThrow());
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aMinimalRequestOmitsEveryUnsuppliedWireField() throws Exception {
        String sentinel = "sentinel-" + UUID.randomUUID();
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .writeBytes("{}".getBytes(UTF_8))
                .start()) {

            gateway(server, new FixedCredentials(null), () -> Credential.of(sentinel.getBytes(UTF_8)), Duration.ofSeconds(10))
                    .search(WebSearchRequest.builder("café ☕").build());

            ScriptedSseServer.RecordedRequest recorded = server.requests().getFirst();
            assertEquals("/web/search?q=caf%C3%A9%20%E2%98%95", recorded.path());
            assertEquals(sentinel, recorded.firstHeader("X-Subscription-Token").orElseThrow());
            assertFalse(recorded.firstHeader("X-Loc-City").isPresent(), "no location was supplied");
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void anUnauthorizedResponseClassifiesAsAuthenticationWithoutLeakingTheToken() throws Exception {
        String sentinel = "sentinel-" + UUID.randomUUID();
        byte[] errorBody = "{\"error\":{\"code\":\"Unauthorized\",\"detail\":\"recovery hint\"}}".getBytes(UTF_8);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(401)
                .header("Content-Type", "application/json")
                .writeBytes(errorBody)
                .start()) {

            Outcome<WebSearchResult> outcome =
                    gateway(server, new FixedCredentials(null), () -> Credential.of(sentinel.getBytes(UTF_8)), Duration.ofSeconds(10))
                            .search(WebSearchRequest.builder("marker-" + UUID.randomUUID()).build());

            Outcome.Failure<WebSearchResult> failure = failureOf(outcome);
            assertEquals(FailureKind.AUTHENTICATION, failure.kind());
            assertEquals(401, failure.httpStatus(), "the machine surface learns the failing status");
            assertEquals("Unauthorized", failure.upstream().code());
            assertFalse(failure.diagnostic().contains(sentinel), "the diagnostic carries no token material");
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aRateLimitedResponseKeepsItsObservedWindows() throws Exception {
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(429)
                .header("Content-Type", "application/json")
                .header("X-RateLimit-Limit", "1,15")
                .header("X-RateLimit-Policy", "request,minute")
                .header("X-RateLimit-Remaining", "0,14")
                .header("X-RateLimit-Reset", "7,42")
                .writeBytes("{\"error\":{\"code\":\"TooManyRequests\"}}".getBytes(UTF_8))
                .start()) {

            Outcome<WebSearchResult> outcome = gateway(
                            server,
                            new FixedCredentials(null),
                            () -> Credential.of(("sentinel-" + UUID.randomUUID()).getBytes(UTF_8)),
                            Duration.ofSeconds(10))
                    .search(WebSearchRequest.builder("q").build());

            Outcome.Failure<WebSearchResult> failure = failureOf(outcome);
            assertEquals(FailureKind.RATE_LIMITED, failure.kind());
            assertEquals(429, failure.httpStatus(), "the failing status survives the gateway mapping");
            assertEquals(2, failure.rateLimits().windows().size(), "a 429 explains itself with its windows");
            assertEquals("request", failure.rateLimits().windows().getFirst().policy());
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void anUnresolvableStoredCredentialFailsAsLocalConfiguration() {
        Outcome<WebSearchResult> outcome = new BraveHttpWebSearchGateway(
                loopbackIndependentTransport(),
                BraveApiOrigin.production(),
                new MissingCredentials(),
                () -> {
                    throw new IllegalStateException("the production origin never consults the loopback supplier");
                },
                Duration.ofSeconds(2),
                null)
                .search(WebSearchRequest.builder("q").build());

        Outcome.Failure<WebSearchResult> failure = failureOf(outcome);
        assertEquals(FailureKind.LOCAL_CONFIG, failure.kind());
        assertTrue(failure.diagnostic().contains("missing credential"), failure.diagnostic());
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aClosedGatewayReportsTheFixedInternalFailureOnLaterSearches() {
        String sentinel = "sentinel-" + UUID.randomUUID();
        BraveHttpWebSearchGateway gateway = new BraveHttpWebSearchGateway(
                loopbackIndependentTransport(),
                BraveApiOrigin.fromOverride(
                        "http://127.0.0.1:1", host -> {
                            throw new AssertionError("literals are never resolved");
                        }),
                Credential.of(sentinel.getBytes(UTF_8)),
                Duration.ofSeconds(2),
                null);
        gateway.close();

        Outcome<WebSearchResult> outcome = gateway.search(WebSearchRequest.builder("q").build());

        Outcome.Failure<WebSearchResult> failure = failureOf(outcome);
        assertEquals(FailureKind.INTERNAL, failure.kind());
        assertEquals("the HTTP client has been closed", failure.diagnostic());
        assertFalse(failure.diagnostic().contains(sentinel), "the diagnostic carries no token material");
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aStalledBodyBreachesTheInjectedTotalDeadlineAsATransportFailure() throws Exception {
        CountDownLatch stalled = new CountDownLatch(1);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .statusCode(200)
                .header("Content-Type", "application/json")
                .writeBytes("{\"web\":{\"results\":[{\"partial\"".getBytes(UTF_8))
                .flush()
                .stallUntil(stalled)
                .start()) {

            long startedAt = System.nanoTime();
            Outcome<WebSearchResult> outcome = gateway(
                            server,
                            new FixedCredentials(null),
                            () -> Credential.of(("sentinel-" + UUID.randomUUID()).getBytes(UTF_8)),
                            Duration.ofMillis(600))
                    .search(WebSearchRequest.builder("q").build());
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

            Outcome.Failure<WebSearchResult> failure = failureOf(outcome);
            assertEquals(FailureKind.TRANSPORT, failure.kind());
            assertEquals("total request deadline exceeded", failure.diagnostic());
            assertTrue(elapsedMs < 5000, "the breach must surface within the deadline, not hang: " + elapsedMs + " ms");
        } finally {
            stalled.countDown();
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void anInterruptedExchangeRestoresTheInterruptFlagAndFailsTransport() throws Exception {
        CountDownLatch neverSendHeaders = new CountDownLatch(1);
        try (ScriptedSseServer server = ScriptedSseServer.builder()
                .holdHeadersUntil(neverSendHeaders)
                .writeBytes("{}".getBytes(UTF_8))
                .start()) {

            BraveHttpWebSearchGateway gateway = gateway(
                    server,
                    new FixedCredentials(null),
                    () -> Credential.of(("sentinel-" + UUID.randomUUID()).getBytes(UTF_8)),
                    Duration.ofSeconds(10));
            AtomicReference<Outcome<WebSearchResult>> outcome = new AtomicReference<>();
            AtomicBoolean interruptRestored = new AtomicBoolean();
            Thread worker = new Thread(() -> {
                outcome.set(gateway.search(WebSearchRequest.builder("q").build()));
                interruptRestored.set(Thread.currentThread().isInterrupted());
            });
            worker.start();
            assertTrue(server.awaitFirstRequest(Duration.ofSeconds(5)), "the peer must have seen the request");
            worker.interrupt();
            worker.join(Duration.ofSeconds(5));

            Outcome.Failure<WebSearchResult> failure = failureOf(outcome.get());
            assertEquals(FailureKind.TRANSPORT, failure.kind());
            assertEquals("upstream exchange interrupted", failure.diagnostic());
            assertTrue(interruptRestored.get(), "the interrupt status is restored, not swallowed");
        } finally {
            neverSendHeaders.countDown();
        }
    }

    private static WebSearchRequest fullRequest() {
        return WebSearchRequest.builder("hello world")
                .country("DE")
                .searchLang("de")
                .uiLang("de-DE")
                .safeSearch(SafeSearch.STRICT)
                .freshness(new Freshness.DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28)))
                .count(7)
                .page(3)
                .spellcheck(false)
                .textDecorations(false)
                .resultFilters(List.of("discussions", "videos"))
                .units(Units.IMPERIAL)
                .extraSnippets(true)
                .includeFetchMetadata(true)
                .operators(false)
                .enableRichCallback(true)
                .location(new WebSearchRequest.Location(
                        47.6062,
                        -122.3321,
                        "Seattle",
                        "WA",
                        "Washington",
                        "US",
                        "98101",
                        "America/Los_Angeles"))
                .build();
    }

    private static BraveHttpWebSearchGateway gateway(
            ScriptedSseServer server,
            CredentialProvider storedCredentials,
            Supplier<Credential> loopbackTestToken,
            Duration totalTimeout) {
        return new BraveHttpWebSearchGateway(
                new BraveHttpTransport(
                        new BraveHttpClientFactory(Duration.ofSeconds(5)).newClient(),
                        GENEROUS_HEADERS_TIMEOUT,
                        ResponseLimits.production(),
                        Clock.systemUTC()),
                loopbackOrigin(server),
                storedCredentials,
                loopbackTestToken,
                totalTimeout,
                null);
    }

    private static BraveHttpTransport loopbackIndependentTransport() {
        return new BraveHttpTransport(
                new BraveHttpClientFactory(Duration.ofSeconds(1)).newClient(),
                Duration.ofSeconds(1),
                ResponseLimits.production(),
                Clock.systemUTC());
    }

    private static BraveApiOrigin loopbackOrigin(ScriptedSseServer server) {
        return BraveApiOrigin.fromOverride(
                server.baseUrl().toString(), host -> { throw new AssertionError("literals are never resolved"); });
    }

    private static WebSearchResult valueOf(Outcome<WebSearchResult> outcome) {
        return switch (outcome) {
            case Outcome.Success<WebSearchResult> success -> success.value();
            case Outcome.Failure<WebSearchResult> failure ->
                    throw new AssertionError("expected a success, saw " + failure.kind() + ": " + failure.diagnostic());
        };
    }

    private static Outcome.Failure<WebSearchResult> failureOf(Outcome<WebSearchResult> outcome) {
        return switch (outcome) {
            case Outcome.Success<WebSearchResult> ignored ->
                    throw new AssertionError("expected a failure, saw a success");
            case Outcome.Failure<WebSearchResult> failure -> failure;
        };
    }

    /** A stored-credential stand-in; a null credential is never consulted by a loopback origin. */
    private static final class FixedCredentials implements CredentialProvider {

        private final Credential credential;

        FixedCredentials(Credential credential) {
            this.credential = credential;
        }

        @Override
        public Credential resolve() {
            if (credential == null) {
                throw new AssertionError("a loopback exchange must never consult the stored credential");
            }
            return credential;
        }

        @Override
        public ResolvedCredential resolveWithProvenance() {
            return new ResolvedCredential(resolve(), "test fixture", false);
        }
    }

    private static final class MissingCredentials implements CredentialProvider {

        @Override
        public Credential resolve() throws CredentialResolutionException {
            throw CredentialResolutionException.missing("nowhere");
        }

        @Override
        public ResolvedCredential resolveWithProvenance() throws CredentialResolutionException {
            throw CredentialResolutionException.missing("nowhere");
        }
    }
}

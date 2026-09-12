package io.amscotti.bravesearch.adapter.cli.command.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.bravehttp.endpoint.WebContinuationProbe;
import io.amscotti.bravesearch.adapter.cli.exit.ExitCodeMapper;
import io.amscotti.bravesearch.adapter.cli.option.CommonSearchOptions;
import io.amscotti.bravesearch.adapter.cli.option.GlobalOptions;
import io.amscotti.bravesearch.adapter.cli.option.GoggleOptions;
import io.amscotti.bravesearch.adapter.cli.option.RemoteOptions;
import io.amscotti.bravesearch.adapter.cli.option.StrictOptionParsing;
import io.amscotti.bravesearch.adapter.cli.presentation.DiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings;
import io.amscotti.bravesearch.adapter.cli.presentation.OutputStreamResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.WebPagedSearchPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.WebSearchPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.WriterDiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.web.WebPagedSearchPresenterImpl;
import io.amscotti.bravesearch.adapter.cli.presentation.web.WebProjectionExtractor;
import io.amscotti.bravesearch.adapter.config.GoggleFileLoader;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.LocalhostResolver;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.CredentialResolutionException;
import io.amscotti.bravesearch.application.port.out.ResolvedCredential;
import io.amscotti.bravesearch.application.port.out.WebSearchDispatch;
import io.amscotti.bravesearch.application.port.out.WebSearchExchange;
import io.amscotti.bravesearch.application.service.PaginationService;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.CancellationRegistry;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.goggles.Goggle;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.output.OutputMode;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.request.Freshness;
import io.amscotti.bravesearch.domain.request.SafeSearch;
import io.amscotti.bravesearch.domain.request.Units;
import io.amscotti.bravesearch.domain.request.WebSearchRequest;
import io.amscotti.bravesearch.domain.result.WebSearchResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Grammar and dispatch contracts of the web command: every documented option reaches the
 * dispatched request exactly once, every local usage rejection fails with exit 2 before the
 * exchange is touched, the loopback credential seam routes independently of the stored
 * credential, and each upstream failure kind keeps its own exit status.
 */
final class WebSearchCommandTest {

    private static final LocalhostResolver STUB_LOCALHOST = host -> List.of(InetAddress.getByName("127.0.0.1"));

    @TempDir
    Path goggleDirectory;

    private GoggleFiles files;

    @org.junit.jupiter.api.BeforeEach
    void goggleFiles() {
        files = new GoggleFiles(goggleDirectory);
    }

    @Test
    void dispatchesTheFullyMappedRequestAndOmitsEveryUnsuppliedBoolean() {
        Harness harness = new Harness();
        WebSearchRequest expected = WebSearchRequest.builder("hello world")
                .country("US")
                .searchLang("en")
                .uiLang("en")
                .safeSearch(SafeSearch.STRICT)
                .freshness(new Freshness.DateRange(LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 3)))
                .count(5)
                .page(3)
                .spellcheck(true)
                .textDecorations(false)
                .resultFilters(List.of("a", "b", "c"))
                .units(Units.METRIC)
                .extraSnippets(true)
                .includeFetchMetadata(true)
                .operators(true)
                .enableRichCallback(false)
                .location(new WebSearchRequest.Location(
                        40.5,
                        -73.5,
                        "Montreal",
                        "QC",
                        "Quebec",
                        "CA",
                        "H2X",
                        "America/Toronto"))
                .build();

        Harness.RunResult result = harness.run(
                "web",
                "--country",
                "US",
                "--search-lang",
                "en",
                "--ui-lang",
                "en",
                "--safe-search",
                "strict",
                "--freshness",
                "2024-01-02to2024-01-03",
                "--count",
                "5",
                "--page",
                "3",
                "--spellcheck",
                "--no-text-decorations",
                "--result-filter",
                "a,b",
                "--result-filter",
                "c",
                "--units",
                "metric",
                "--extra-snippets",
                "--include-fetch-metadata",
                "--operators",
                "--no-enable-rich-callback",
                "--loc-lat",
                "40.5",
                "--loc-long",
                "-73.5",
                "--loc-city",
                "Montreal",
                "--loc-state",
                "QC",
                "--loc-state-name",
                "Quebec",
                "--loc-country",
                "CA",
                "--loc-postal-code",
                "H2X",
                "--loc-timezone",
                "America/Toronto",
                "hello world");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(1, harness.exchange.dispatches.size(), "exactly one exchange is dispatched");
        assertEquals(expected, harness.exchange.dispatches.getFirst().request(), "the request must map every option");
        assertEquals(2, harness.exchange.dispatches.getFirst().request().upstreamOffset(), "page 3 is offset 2");
        assertEquals(1, harness.presenter.outcomes.size(), "the presenter renders exactly the one outcome");
        assertEquals(
                "hello world",
                harness.presenter.requests.getFirst().query(),
                "the renderer echoes the parsed query");
        assertEquals(3, harness.presenter.requests.getFirst().page(), "the renderer echoes the pinned page");
        assertEquals(
                new OutputRequest(false, OutputMode.HUMAN, false, false, false),
                harness.presenter.outputs.getFirst(),
                "no output flag means the human channel with every flag absent");
        assertEquals("", result.stderr(), () -> result.describe());
    }

    @Test
    void dispatchesTheBareQueryUnderTheProductionOriginAndDefaultBudgets() {
        Harness harness = new Harness();

        Harness.RunResult result = harness.run("web", "q");

        assertEquals(0, result.exitCode(), () -> result.describe());
        WebSearchDispatch dispatch = harness.exchange.dispatches.getFirst();
        assertEquals(WebSearchRequest.builder("q").build(), dispatch.request());
        assertEquals(BraveApiOrigin.production(), dispatch.origin());
        assertTrue(dispatch.origin().credentialsAllowed(), "the default origin is the production origin");
        assertEquals(Duration.ofSeconds(30), dispatch.totalTimeout(), "the documented default total budget");
        assertEquals(Duration.ofSeconds(10), dispatch.connectTimeout(), "the documented default connect budget");
        assertNull(dispatch.pinnedApiVersion(), "no Api-Version pin travels unless one was given");
        assertEquals(1, harness.stored.resolutions, "the stored credential source is consulted once");
    }

    @Test
    void carriesParsedBudgetsAndTheVersionPinIntoTheDispatch() {
        Harness harness = new Harness();

        Harness.RunResult result = harness.run(
                "web", "--timeout", "5s", "--connect-timeout", "250ms", "--api-version", "2024-06-01", "q");

        assertEquals(0, result.exitCode(), () -> result.describe());
        WebSearchDispatch dispatch = harness.exchange.dispatches.getFirst();
        assertEquals(Duration.ofSeconds(5), dispatch.totalTimeout());
        assertEquals(Duration.ofMillis(250), dispatch.connectTimeout());
        assertEquals("2024-06-01", dispatch.pinnedApiVersion());
    }

    @Test
    void rawOutputIsAcceptedForTheSingleRequestWebCommand() {
        Harness harness = new Harness();

        Harness.RunResult result = harness.run("web", "--output", "raw", "q");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(1, harness.exchange.dispatches.size());
        assertEquals(OutputMode.RAW, harness.presenter.outputs.getFirst().mode());
    }

    @Test
    void allPagesRejectsRawOutputBeforeAnyDispatch() {
        Harness harness = new Harness();

        Harness.RunResult result = harness.run("web", "--all-pages", "--output", "raw", "q");

        assertEquals(2, result.exitCode(), "raw aggregation is rejected as a usage error");
        assertEquals(0, harness.exchange.dispatches.size(), "no request may leave before the rejection");
        assertEquals(0, harness.recordingPaged.runs.size());
        assertTrue(result.stderr().contains("Usage:") || result.stderr().contains("raw"), () -> result.describe());
        assertEquals("", result.stdout());
    }

    @Test
    void paginationGrammarRejectsEveryInvalidCombinationBeforeDispatch() {
        List<String[]> rejections = List.of(
                new String[] {"web", "--page", "2", "--all-pages", "q"},
                new String[] {"web", "--all-pages", "--page", "2", "q"},
                new String[] {"web", "--max-pages", "3", "q"},
                new String[] {"web", "--all-pages", "--max-pages", "0", "q"},
                new String[] {"web", "--all-pages", "--max-pages", "11", "q"},
                new String[] {"web", "--all-pages", "--max-pages", "x", "q"});

        for (String[] args : rejections) {
            Harness harness = new Harness();
            Harness.RunResult result = harness.run(args);
            assertEquals(2, result.exitCode(), () -> "must be a usage error: " + String.join(" ", args) + " -> " + result.describe());
            assertEquals(0, harness.exchange.dispatches.size(), () -> "no exchange may be dispatched: " + String.join(" ", args));
            assertEquals(0, harness.recordingPaged.runs.size());
            assertEquals("", result.stdout(), () -> String.join(" ", args));
        }
    }

    @Test
    void allPagesWalksThePagesSequentiallyUnderTheDefaultBudgetAndRendersThePagedRun() {
        Harness harness = new Harness();
        harness.exchange.enqueue(pageOutcome(200, "{\"query\":{\"more_results_available\":true},\"web\":{\"results\":[{\"title\":\"One\"}]}}"));
        harness.exchange.enqueue(pageOutcome(200, "{\"query\":{\"more_results_available\":false},\"web\":{\"results\":[{\"title\":\"Two\"}]}}"));

        Harness.RunResult result = harness.run("web", "--all-pages", "q");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(2, harness.exchange.dispatches.size(), "the continuation flag walks exactly two pages");
        assertEquals(1, harness.exchange.dispatches.get(0).request().page(), "the first request is user-facing page one");
        assertEquals(2, harness.exchange.dispatches.get(1).request().page(), "the second request is user-facing page two");
        assertEquals(
                harness.exchange.dispatches.get(0).request().query(),
                harness.exchange.dispatches.get(1).request().query(),
                "every other request member travels unchanged");
        assertEquals(0, harness.presenter.outcomes.size(), "the single-request presenter is not used by a paged run");
        assertEquals(1, harness.recordingPaged.runs.size());
        PaginationService.PagedRun<WebSearchResult> run = harness.recordingPaged.runs.getFirst();
        assertEquals(10, run.requestedPages(), "the default page budget is the documented maximum");
        assertEquals(2, run.receivedPages());
        assertEquals(2, harness.recordingPaged.observedPages.size(), "every completed page reaches the page observer");
    }

    @Test
    void maxPagesBoundsThePaginationRun() {
        Harness harness = new Harness();
        for (int page = 1; page <= 10; page++) {
            harness.exchange.enqueue(pageOutcome(200, "{\"query\":{\"more_results_available\":true}}"));
        }

        Harness.RunResult result = harness.run("web", "--all-pages", "--max-pages", "2", "q");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(2, harness.exchange.dispatches.size(), "the bound stops the walk even while more results stay available");
        assertEquals(2, harness.recordingPaged.runs.getFirst().requestedPages());
    }

    @Test
    void aFailingLaterPageHandsThePagedRunToThePresenter() {
        Harness harness = new Harness();
        harness.exchange.enqueue(pageOutcome(200, "{\"query\":{\"more_results_available\":true}}"));
        harness.exchange.enqueue(
                new Outcome.Failure<>(FailureKind.AUTHENTICATION, "upstream exchange failed with status 401"));

        Harness.RunResult result = harness.run("web", "--all-pages", "--max-pages", "3", "q");

        assertEquals(4, result.exitCode(), "the failed page's kind decides the exit status");
        assertEquals(2, harness.exchange.dispatches.size(), "the walk aborts on the first failed page");
        PaginationService.PagedRun<WebSearchResult> run = harness.recordingPaged.runs.getFirst();
        assertEquals(3, run.requestedPages());
        assertEquals(1, run.receivedPages());
        assertEquals(FailureKind.AUTHENTICATION, run.failure().kind());
    }

    @Test
    void aBrokenPipeMidWalkEndsTheRunAsSilentZero() {
        Harness harness = new Harness();
        harness.pagedPresenter = realPagedPresenter();
        harness.writer = new FailingAfterWrites(1, new IOException("Broken pipe"));
        harness.exchange.enqueue(pageOutcome(
                200,
                "{\"query\":{\"more_results_available\":true},\"web\":{\"results\":["
                        + "{\"title\":\"One\",\"url\":\"https://example.com/one\"},"
                        + "{\"title\":\"Two\",\"url\":\"https://example.com/two\"}]}}"));
        harness.exchange.enqueue(pageOutcome(
                200, "{\"query\":{\"more_results_available\":false},\"web\":{\"results\":[{\"title\":\"Three\"}]}}"));

        Harness.RunResult result = harness.run("web", "--all-pages", "--output", "jsonl", "q");

        assertEquals(
                0,
                result.exitCode(),
                () -> "a consumer closing the pipe mid-walk is successful early termination: " + result.describe());
        assertEquals("", result.stderr(), () -> "early termination stays silent: " + result.describe());
        assertFalse(result.stderr().contains("Exception"), () -> "no stack trace may reach stderr: " + result.describe());
    }

    @Test
    void aNonPipeWriteFailureMidWalkKeepsTheTransportStatusWithOneDiagnosticLine() {
        Harness harness = new Harness();
        harness.pagedPresenter = realPagedPresenter();
        harness.writer = new FailingAfterWrites(1, new IOException("no space left on device"));
        harness.exchange.enqueue(pageOutcome(
                200,
                "{\"query\":{\"more_results_available\":true},\"web\":{\"results\":["
                        + "{\"title\":\"One\",\"url\":\"https://example.com/one\"},"
                        + "{\"title\":\"Two\",\"url\":\"https://example.com/two\"}]}}"));
        harness.exchange.enqueue(pageOutcome(
                200, "{\"query\":{\"more_results_available\":false},\"web\":{\"results\":[{\"title\":\"Three\"}]}}"));

        Harness.RunResult result = harness.run("web", "--all-pages", "--output", "jsonl", "q");

        assertEquals(6, result.exitCode(), () -> "an output-I/O failure keeps the transport status: " + result.describe());
        List<String> lines = result.stderr().lines().toList();
        assertEquals(1, lines.size(), () -> "exactly one diagnostic line: " + result.describe());
        assertTrue(lines.getFirst().contains("writing the result document failed"), () -> result.describe());
        assertFalse(result.stderr().contains("Exception"), () -> "no stack trace may reach stderr: " + result.describe());
    }

    @Test
    void anInterruptLatchedMidWalkRendersTheTransportFailureButExits130() {
        Harness harness = new Harness();
        List<CancellationContext> liveContexts = new CopyOnWriteArrayList<>();
        harness.cancellations =
                context -> {
                    liveContexts.add(context);
                    return () -> liveContexts.remove(context);
                };
        for (int page = 1; page <= 3; page++) {
            harness.exchange.enqueue(
                    pageOutcome(200, "{\"query\":{\"more_results_available\":true},\"web\":{\"results\":[]}}"));
        }
        harness.exchange.onDispatch =
                () -> {
                    if (harness.exchange.dispatches.size() == 2) {
                        liveContexts.forEach(context -> context.latch(CancellationContext.Cause.SIGINT));
                    }
                };

        Harness.RunResult result = harness.run("web", "--all-pages", "--max-pages", "3", "q");

        assertEquals(
                130,
                result.exitCode(),
                () -> "the latched interrupt owns the process status even though the render is the transport"
                        + " failure document: " + result.describe());
        PaginationService.PagedRun<WebSearchResult> run = harness.recordingPaged.runs.getFirst();
        assertEquals(2, run.receivedPages(), "the completed pages stay countable");
        assertEquals(3, run.requestedPages(), "the page budget stays observable");
        assertEquals(FailureKind.TRANSPORT, run.failure().kind(), "the rendered failure stays the transport failure");
        assertEquals(2, harness.exchange.dispatches.size(), "no request leaves after the interrupt latched");
    }

    @Test
    void aTerminationLatchedMidWalkRendersTheTransportFailureButExits143() {
        Harness harness = new Harness();
        List<CancellationContext> liveContexts = new CopyOnWriteArrayList<>();
        harness.cancellations =
                context -> {
                    liveContexts.add(context);
                    return () -> liveContexts.remove(context);
                };
        for (int page = 1; page <= 3; page++) {
            harness.exchange.enqueue(
                    pageOutcome(200, "{\"query\":{\"more_results_available\":true},\"web\":{\"results\":[]}}"));
        }
        harness.exchange.onDispatch =
                () -> {
                    if (harness.exchange.dispatches.size() == 2) {
                        liveContexts.forEach(context -> context.latch(CancellationContext.Cause.SIGTERM));
                    }
                };

        Harness.RunResult result = harness.run("web", "--all-pages", "--max-pages", "3", "q");

        assertEquals(
                143,
                result.exitCode(),
                () -> "the latched termination owns the process status even though the render is the transport"
                        + " failure document: " + result.describe());
        PaginationService.PagedRun<WebSearchResult> run = harness.recordingPaged.runs.getFirst();
        assertEquals(2, run.receivedPages(), "the completed pages stay countable");
        assertEquals(FailureKind.TRANSPORT, run.failure().kind(), "the rendered failure stays the transport failure");
        assertEquals(2, harness.exchange.dispatches.size(), "no request leaves after the termination latched");
    }

    @Test
    void thePresenterExitStatusDecidesTheSuccessfulRun() {
        Harness harness = new Harness();
        harness.presenter.exitCode = 6;

        Harness.RunResult result = harness.run("web", "q");

        assertEquals(6, result.exitCode(), "a rendering failure keeps its own exit status");
    }

    @Test
    void everyUsageRejectionFailsWithExit2BeforeTheExchangeIsTouched() {
        List<String[]> rejections = List.of(
                new String[] {"web", "--count", "21", "q"},
                new String[] {"web", "--count", "0", "q"},
                new String[] {"web", "--page", "0", "q"},
                new String[] {"web", "--page", "11", "q"},
                new String[] {"web", "--freshness", "2026-02-30to2026-03-01", "q"},
                new String[] {"web", "--freshness", "soon", "q"},
                new String[] {"web", "--safe-search", "bogus", "q"},
                new String[] {"web", "--units", "furlongs", "q"},
                new String[] {"web", "--loc-lat", "40.5", "q"},
                new String[] {"web", "--loc-long", "-73.5", "q"},
                new String[] {"web", "--loc-lat", "91", "--loc-long", "0", "q"},
                new String[] {"web", "--loc-lat", "NaN", "--loc-long", "0", "q"},
                new String[] {"web", "--loc-long", "Infinity", "--loc-lat", "0", "q"},
                new String[] {"web", "--loc-timezone", "Not/AZone", "q"},
                new String[] {"web", "--loc-state", "qc", "q"},
                new String[] {"web", "--loc-country", "ca", "q"},
                new String[] {"web", "--result-filter", "a,,b", "q"},
                new String[] {"web", "--country", " ", "q"},
                new String[] {"web", "--country", "ALL", "q"},
                new String[] {"web", fiftyOneWords()},
                new String[] {"web", "a".repeat(401)},
                new String[] {"web", "   "},
                new String[] {"web"},
                new String[] {"web", "--timeout", "0s", "q"},
                new String[] {"web", "--timeout", "PT0S", "q"},
                new String[] {"web", "--timeout", "5x", "q"},
                new String[] {"web", "--timeout", "9223372036854775808ms", "q"},
                new String[] {"web", "--connect-timeout", "-5s", "q"},
                new String[] {"web", "--api-version", "2026-13-99", "q"},
                new String[] {"web", "--api-version", "2026-2-1", "q"},
                new String[] {"web", "--output", "bogus", "q"},
                new String[] {"web", "--output", "json", "--pretty", "--output", "raw", "q"},
                new String[] {"web", "--pretty", "--output", "jsonl", "q"},
                new String[] {"web", "--pretty", "--output", "raw", "q"},
                new String[] {"web", "--verbose", "--quiet", "q"},
                new String[] {"web", "--quiet", "--verbose", "q"},
                new String[] {"--verbose", "--quiet", "web", "q"},
                new String[] {"web", "--count", "1", "--count", "2", "q"},
                new String[] {"web", "--freshness", "pd", "--freshness", "pw", "q"},
                new String[] {"web", "--base-url", "http://127.0.0.1:9", "--base-url", "http://127.0.0.1:9", "q"},
                new String[] {"web", "--base-url", "https://api.search.brave.com", "q"},
                new String[] {"web", "--base-url", "http://user:pw@127.0.0.1:9", "q"},
                new String[] {"--output", "json", "web", "q"},
                new String[] {"--timeout", "5s", "web", "q"},
                new String[] {"--pretty", "web", "q"},
                new String[] {"--verb", "web", "q"},
                new String[] {"web", "--outp", "json", "q"},
                new String[] {"--nope", "web", "q"},
                new String[] {"web", "--nope", "q"},
                new String[] {"web", "one", "two"});

        for (String[] args : rejections) {
            Harness harness = new Harness();
            Harness.RunResult result = harness.run(args);
            assertEquals(2, result.exitCode(), () -> "must be a usage error: " + String.join(" ", args) + " -> " + result.describe());
            assertTrue(
                    result.stderr().contains("Usage:") || result.stderr().contains("Unknown option"),
                    () -> "the rejection must explain itself on stderr: " + String.join(" ", args) + " -> " + result.describe());
            assertEquals(
                    0,
                    harness.exchange.dispatches.size(),
                    () -> "no exchange may be dispatched: " + String.join(" ", args));
            assertEquals("", result.stdout(), () -> "usage errors never write stdout: " + String.join(" ", args));
        }
    }

    @Test
    void eachGoggleStrategyCompilesIntoTheDispatchedRequest() throws IOException {
        Harness urlRefs = new Harness();
        Harness.RunResult urls = urlRefs.run(
                "web", "--goggle", "https://example.com/one", "--goggle", "http://example.org/two", "q");

        assertEquals(0, urls.exitCode(), () -> urls.describe());
        assertEquals(
                List.of(
                        new Goggle.UrlReference("https://example.com/one"),
                        new Goggle.UrlReference("http://example.org/two")),
                urlRefs.exchange.dispatches.getFirst().request().goggles());

        Path file = files.createFile("rules.goggle", "! local rules\n+site:example.com");
        Harness fromFile = new Harness();
        Harness.RunResult loaded = fromFile.run("web", "--goggle-file", file.toString(), "q");

        assertEquals(0, loaded.exitCode(), () -> loaded.describe());
        assertEquals(
                List.of(new Goggle.Inline("! local rules\n+site:example.com")),
                fromFile.exchange.dispatches.getFirst().request().goggles());

        Harness sites = new Harness();
        Harness.RunResult compiled = sites.run(
                "web", "--include-site", "münchen.de", "--exclude-site", "spam.example", "--include-site", "a.example", "q");

        assertEquals(0, compiled.exitCode(), () -> compiled.describe());
        assertEquals(
                List.of(new Goggle.Inline("+site:xn--mnchen-3ya.de\n+site:a.example\n-site:spam.example")),
                sites.exchange.dispatches.getFirst().request().goggles());
    }

    @Test
    void goggleInputStrategiesAreMutuallyExclusiveBeforeAnyDispatch() throws IOException {
        Path file = files.createFile("rules.goggle", "+site:example.com");
        List<String[]> rejections = List.of(
                new String[] {"web", "--goggle", "https://a.example", "--goggle-file", file.toString(), "q"},
                new String[] {"web", "--goggle", "https://a.example", "--include-site", "a.example", "q"},
                new String[] {"web", "--goggle", "https://a.example", "--exclude-site", "a.example", "q"},
                new String[] {"web", "--goggle-file", file.toString(), "--include-site", "a.example", "q"},
                new String[] {"web", "--goggle-file", file.toString(), "--exclude-site", "a.example", "q"},
                new String[] {
                    "web",
                    "--goggle",
                    "https://a.example",
                    "--goggle-file",
                    file.toString(),
                    "--include-site",
                    "a.example",
                    "--exclude-site",
                    "b.example",
                    "q"
                });

        for (String[] args : rejections) {
            Harness harness = new Harness();
            Harness.RunResult result = harness.run(args);
            assertEquals(
                    2,
                    result.exitCode(),
                    () -> "the strategies must be exclusive: " + String.join(" ", args) + " -> " + result.describe());
            assertEquals(
                    0, harness.exchange.dispatches.size(), () -> "no exchange may be dispatched: " + String.join(" ", args));
            assertEquals("", result.stdout(), () -> String.join(" ", args));
        }
    }

    @Test
    void moreThanThreeGogglesOfOneStrategyAreRefusedBeforeAnyDispatch() throws IOException {
        Path file = files.createFile("rules.goggle", "+site:example.com");
        String[][] rejections = {
            {
                "web",
                "--goggle",
                "https://a.example/1",
                "--goggle",
                "https://a.example/2",
                "--goggle",
                "https://a.example/3",
                "--goggle",
                "https://a.example/4",
                "q"
            },
            {"web", "--goggle-file", file.toString(), "--goggle-file", file.toString(), "--goggle-file", file.toString(),
                "--goggle-file", file.toString(), "q"},
        };

        for (String[] args : rejections) {
            Harness harness = new Harness();
            Harness.RunResult result = harness.run(args);
            assertEquals(
                    2,
                    result.exitCode(),
                    () -> "the upstream maximum of three holds: " + String.join(" ", args) + " -> " + result.describe());
            assertEquals(0, harness.exchange.dispatches.size(), () -> String.join(" ", args));
        }
    }

    @Test
    void aFailingGoggleFileKeepsItsContentOutOfEveryDiagnostic() throws IOException {
        Path oversized = files.oversizedFileWithSentinel();
        Harness tooBig = new Harness();
        Harness.RunResult refused = tooBig.run("web", "--goggle-file", oversized.toString(), "q");

        assertEquals(2, refused.exitCode(), () -> refused.describe());
        assertEquals(0, tooBig.exchange.dispatches.size());
        assertFalse(refused.stderr().contains(GoggleFiles.SENTINEL), () -> refused.describe());
        assertTrue(refused.stderr().contains(oversized.getFileName().toString()), () -> refused.describe());

        Path binary = files.invalidUtf8FileWithSentinel();
        Harness notUtf8 = new Harness();
        Harness.RunResult undecodable = notUtf8.run("web", "--goggle-file", binary.toString(), "q");

        assertEquals(2, undecodable.exitCode(), () -> undecodable.describe());
        assertEquals(0, notUtf8.exchange.dispatches.size());
        assertFalse(undecodable.stderr().contains(GoggleFiles.SENTINEL), () -> undecodable.describe());

        Path injectable = files.createFile("sites.goggle", "+site:example.com\u0000");
        Harness injected = new Harness();
        Harness.RunResult invalidRules = injected.run(
                "web", "--goggle", "https://a.example", "--include-site", "example.com$strict", "--goggle-file",
                injectable.toString(), "q");

        assertEquals(2, invalidRules.exitCode(), () -> invalidRules.describe());
        assertEquals(0, injected.exchange.dispatches.size());
        assertFalse(invalidRules.stderr().contains(GoggleFiles.SENTINEL), () -> invalidRules.describe());
        assertFalse(invalidRules.stderr().contains("example.com$strict"), "rejected values never ride diagnostics");
    }

    @Test
    void aGoggleFileThatLoadsButBreaksAnInlineRuleReportsTheRuleTextOnly() throws IOException {
        Path loadsThenFails = files.createFile("nul.goggle", "! " + GoggleFiles.SENTINEL + "\n+site:example.com\u0000");
        Harness harness = new Harness();

        Harness.RunResult refused = harness.run("web", "--goggle-file", loadsThenFails.toString(), "q");

        assertEquals(2, refused.exitCode(), () -> "the broken inline rule is a usage failure: " + refused.describe());
        assertEquals(0, harness.exchange.dispatches.size(), "no exchange may be dispatched");
        assertTrue(
                refused.stderr().contains("control characters"),
                () -> "the stderr names the violated rule: " + refused.describe());
        assertFalse(
                refused.stderr().contains(GoggleFiles.SENTINEL),
                () -> "the loaded file's content never rides the rejection: " + refused.describe());
    }

    @Test
    void verboseRunsReportLoadedGoggleFilesAsPathAndByteLength() throws IOException {
        Path file = files.createFile("rules.goggle", "! " + GoggleFiles.SENTINEL + "\n+site:example.com");
        long bytes = Files.size(file);
        Harness verbose = new Harness();

        Harness.RunResult reported = verbose.run("web", "--goggle-file", file.toString(), "--verbose", "q");

        assertEquals(0, reported.exitCode(), () -> reported.describe());
        List<String> reports =
                reported.stderr().lines().filter(line -> line.contains("goggle file")).toList();
        assertEquals(1, reports.size(), () -> "exactly one goggle-file report line: " + reported.describe());
        assertTrue(
                reports.getFirst().contains(file.toString()),
                () -> "the report names the source path: " + reports.getFirst());
        assertTrue(
                reports.getFirst().contains(Long.toString(bytes)),
                () -> "the report names the byte length: " + reports.getFirst());
        assertFalse(
                reports.getFirst().contains(GoggleFiles.SENTINEL),
                () -> "the report stays content-free: " + reports.getFirst());

        Harness plain = new Harness();
        Harness.RunResult silent = plain.run("web", "--goggle-file", file.toString(), "q");

        assertEquals(0, silent.exitCode(), () -> silent.describe());
        assertFalse(
                silent.stderr().contains("goggle file"),
                () -> "without --verbose no goggle-file report is written: " + silent.describe());
    }

    @Test
    void theEndOfOptionsMarkerPreservesOptionLikeQueryText() {        Harness oddQuery = new Harness();
        Harness.RunResult odd = oddQuery.run("web", "--", "--weird -query");
        assertEquals(0, odd.exitCode(), () -> odd.describe());
        assertEquals("--weird -query", oddQuery.exchange.dispatches.getFirst().request().query());

        Harness flagLike = new Harness();
        Harness.RunResult flag = flagLike.run("web", "--", "--not-a-flag");
        assertEquals(0, flag.exitCode(), () -> flag.describe());
        assertEquals("--not-a-flag", flagLike.exchange.dispatches.getFirst().request().query());

        Harness shortLike = new Harness();
        Harness.RunResult shortFlag = shortLike.run("web", "--", "-x");
        assertEquals(0, shortFlag.exitCode(), () -> shortFlag.describe());
        assertEquals("-x", shortLike.exchange.dispatches.getFirst().request().query());

        Harness unmarked = new Harness();
        Harness.RunResult refused = unmarked.run("web", "--not-a-flag");
        assertEquals(2, refused.exitCode(), "an option-like query without the marker is an unknown option");
        assertEquals(0, unmarked.exchange.dispatches.size());
    }

    @Test
    void verboseAndNoColorAreAcceptedAfterTheSubcommandToken() {
        Harness harness = new Harness();

        Harness.RunResult result = harness.run("--no-color", "web", "--verbose", "q");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(1, harness.exchange.dispatches.size());
        assertEquals(
                new OutputRequest(false, OutputMode.HUMAN, false, true, false),
                harness.presenter.outputs.getFirst(),
                "the globals parsed after the subcommand token reach the output request");
        assertTrue(harness.globals.verbose(), "the shared global options hold the parsed flag");
        assertTrue(harness.globals.noColor());
    }

    @Test
    void missingStoredCredentialKeepsExit3WithOneRedactedLineAndNoDispatch() {
        Harness harness = new Harness();
        harness.stored.failure = CredentialResolutionException.missing("environment BRAVE_API_KEY");

        Harness.RunResult result = harness.run("web", "q");

        assertEquals(3, result.exitCode(), () -> result.describe());
        assertEquals(0, harness.exchange.dispatches.size(), "a run without a credential never dispatches");
        List<String> lines = result.stderr().lines().toList();
        assertEquals(1, lines.size(), () -> "exactly one diagnostic line: " + result.describe());
        assertTrue(lines.getFirst().contains("missing credential"), () -> result.describe());
        assertTrue(lines.getFirst().startsWith("web:"), () -> result.describe());
        assertEquals("", result.stdout(), () -> result.describe());
    }

    @Test
    void aStoredCredentialNoHeaderValueCanCarryKeepsExit3WithOneRedactedLineAndNoDispatch() {
        Harness harness = new Harness();
        harness.stored.credential = credential("ключ-\uD83E\uDDEA-stored");

        Harness.RunResult result = harness.run("web", "q");

        assertEquals(3, result.exitCode(), () -> result.describe());
        assertEquals(
                0,
                harness.exchange.dispatches.size(),
                "a token that cannot travel in the auth header never dispatches");
        List<String> lines = result.stderr().lines().toList();
        assertEquals(1, lines.size(), () -> "exactly one diagnostic line: " + result.describe());
        assertTrue(lines.getFirst().contains("subscription token"), () -> result.describe());
        assertFalse(lines.getFirst().contains("ключ"), "the rejection never echoes token material");
        assertTrue(lines.getFirst().startsWith("web:"), () -> result.describe());
        assertEquals("", result.stdout(), () -> result.describe());
    }

    @Test
    void loopbackBaseUrlsDrawTheirCredentialFromTheTestKeySeamInsteadOfTheStoredOne() {
        Harness missingKey = new Harness();
        missingKey.stored.credential = credential("stored-production-token");
        missingKey.loopback.credential = null;
        Harness.RunResult refused = missingKey.run("web", "--base-url", "http://127.0.0.1:9", "q");

        assertEquals(3, refused.exitCode(), "a loopback run without a test key keeps the configuration status");
        assertEquals(0, missingKey.exchange.dispatches.size());
        assertEquals(
                0,
                missingKey.stored.resolutions,
                "the stored production credential is never consulted for a loopback origin");
        assertTrue(refused.stderr().contains("BRAVE_SEARCH_TEST_KEY"), () -> refused.describe());

        Harness withKey = new Harness();
        withKey.stored.credential = credential("stored-production-token");
        withKey.loopback.credential = credential("loopback-test-key");
        Harness.RunResult dispatched = withKey.run("web", "--base-url", "http://127.0.0.1:9/", "q");

        assertEquals(0, dispatched.exitCode(), () -> dispatched.describe());
        WebSearchDispatch dispatch = withKey.exchange.dispatches.getFirst();
        assertFalse(dispatch.origin().credentialsAllowed(), "the override origin never receives stored credentials");
        assertEquals("http://127.0.0.1:9", dispatch.origin().baseUri().toString(), "trailing slash normalizes away");
        assertEquals(1, withKey.loopback.resolutions);
        assertEquals(0, withKey.stored.resolutions);
    }

    @Test
    void eachUpstreamFailureKindReachesThePresenterAndKeepsItsExitStatus() {
        List<FailureKind> kinds =
                List.of(FailureKind.AUTHENTICATION, FailureKind.RATE_LIMITED, FailureKind.TRANSPORT, FailureKind.UPSTREAM, FailureKind.MALFORMED);
        List<Integer> expected = List.of(4, 5, 6, 7, 8);

        for (int index = 0; index < kinds.size(); index++) {
            Harness harness = new Harness();
            harness.exchange.outcome =
                    new Outcome.Failure<>(kinds.get(index), kinds.get(index) + " upstream explanation");
            final int expectedExit = expected.get(index);
            final String kind = String.valueOf(kinds.get(index));

            Harness.RunResult result = harness.run("web", "q");

            assertEquals(
                    expectedExit,
                    result.exitCode(),
                    () -> kind + " keeps its exit status: " + result.describe());
            assertEquals(1, harness.presenter.outcomes.size(), "the failed exchange is rendered by the presenter");
            assertTrue(
                    harness.presenter.outcomes.getFirst() instanceof Outcome.Failure<WebSearchResult> failure
                            && failure.kind() == kinds.get(index),
                    () -> kind + " reaches the presenter with its kind intact");
            assertEquals("", result.stdout(), () -> result.describe());
        }
    }

    @Test
    void aUsageFailureAfterTheOutputFlagStillReportsInHumanForm() {
        Harness harness = new Harness();

        Harness.RunResult result = harness.run("web", "--output", "json", "--count", "21", "q");

        assertEquals(2, result.exitCode(), () -> result.describe());
        assertTrue(result.stderr().contains("Usage:"), () -> result.describe());
        assertEquals(
                "",
                result.stdout(),
                "a parse-time failure owns no machine document yet: " + result.describe());
        assertEquals(0, harness.exchange.dispatches.size());
    }

    private static String fiftyOneWords() {
        return String.join(" ", java.util.Collections.nCopies(51, "word"));
    }

    /** Goggle-file fixtures under one temporary directory, built around a leak sentinel. */
    private static final class GoggleFiles {

        static final String SENTINEL = "GOOGLE-FILE-SENTINEL-3b7e2a";

        private final Path directory;

        GoggleFiles(Path directory) {
            this.directory = directory;
        }

        Path createFile(String name, String content) throws IOException {
            Path file = directory.resolve(name);
            Files.write(file, content.getBytes(UTF_8));
            return file;
        }

        Path oversizedFileWithSentinel() throws IOException {
            Path file = directory.resolve("oversized.goggle");
            try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                bytes.write(SENTINEL.getBytes(UTF_8));
                bytes.write('\n');
                bytes.write(new byte[GoggleFileLoader.MAX_BYTES]);
                Files.write(file, bytes.toByteArray());
            }
            return file;
        }

        Path invalidUtf8FileWithSentinel() throws IOException {
            Path file = directory.resolve("binary.goggle");
            try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                bytes.write(SENTINEL.getBytes(UTF_8));
                bytes.write('\n');
                bytes.write(0xFF);
                bytes.write(0xFE);
                Files.write(file, bytes.toByteArray());
            }
            return file;
        }
    }

    private static Credential credential(String token) {
        return Credential.of(token.getBytes(UTF_8));
    }

    /** Root fixture shaped like the process root: shared globals plus the web subcommand. */
    @Command(name = "brave-search", description = "Brave Search command line client for humans and autonomous agents.")
    static final class Root implements Runnable {
        @Mixin
        GlobalOptions globals;

        @Spec CommandSpec spec;

        @Override
        public void run() {
            spec.commandLine().usage(spec.commandLine().getOut());
        }
    }

    private static final class Harness {
        final GlobalOptions globals = new GlobalOptions();
        final RecordingExchange exchange = new RecordingExchange();
        final RecordingPresenter presenter = new RecordingPresenter();
        final RecordingPagedPresenter recordingPaged = new RecordingPagedPresenter();
        WebPagedSearchPresenter pagedPresenter = recordingPaged;
        final FakeCredentialProvider stored = new FakeCredentialProvider();
        final FakeCredentialProvider loopback = new FakeCredentialProvider();
        final java.io.ByteArrayOutputStream stdoutBytes = new java.io.ByteArrayOutputStream();
        final java.io.ByteArrayOutputStream stderrBytes = new java.io.ByteArrayOutputStream();
        ResultWriter writer = new OutputStreamResultWriter(stdoutBytes);
        CancellationRegistry cancellations = context -> () -> {};

        Harness() {
            stored.credential = credential("stored-production-token");
            loopback.credential = credential("loopback-test-key");
        }

        RunResult run(String... args) {
            WebSearchCommand web = new WebSearchCommand(
                    globals,
                    new RemoteOptions(STUB_LOCALHOST),
                    new CommonSearchOptions(),
                    new GoggleOptions(new GoggleFileLoader()),
                    stored,
                    loopback,
                    exchange,
                    presenter,
                    pagedPresenter,
                    new PaginationService<>(new WebContinuationProbe(), (resetAt, cancellation) -> Duration.ZERO),
                    cancellations,
                    writer,
                    WriterDiagnosticsSink::new);
            Root root = new Root();
            root.globals = globals;
            CommandLine commandLine = new CommandLine(root);
            commandLine.addSubcommand("web", new CommandLine(web));
            StrictOptionParsing.apply(commandLine);
            commandLine.setOut(new PrintWriter(new java.io.OutputStreamWriter(stdoutBytes, UTF_8), true));
            commandLine.setErr(new PrintWriter(new java.io.OutputStreamWriter(stderrBytes, UTF_8), true));
            int exitCode = commandLine.execute(args);
            return new RunResult(exitCode, stdoutBytes.toString(UTF_8), stderrBytes.toString(UTF_8));
        }

        record RunResult(int exitCode, String stdout, String stderr) {
            String describe() {
                return "exitCode=" + exitCode + ", stdout=<" + stdout + ">, stderr=<" + stderr + ">";
            }
        }
    }

    /** The production paged presenter over the shared codecs, for walk scenarios that render for real. */
    private static WebPagedSearchPresenter realPagedPresenter() {
        JsonMappers mappers = new JsonMappers();
        JsonlCodec jsonl = new JsonlCodec(mappers);
        return new WebPagedSearchPresenterImpl(
                new EnvelopeCodec(mappers),
                jsonl,
                new WebProjectionExtractor(mappers),
                (mode, diagnostics, results, quiet) -> new ModeAwareWarnings(
                        mode, WebPagedSearchPresenter.COMMAND, diagnostics, results, jsonl, quiet));
    }

    /** A writer that succeeds a fixed number of times, then fails every further write with one cause. */
    private static final class FailingAfterWrites implements ResultWriter {

        private final int successfulWrites;
        private final IOException failure;
        private int writes;

        FailingAfterWrites(int successfulWrites, IOException failure) {
            this.successfulWrites = successfulWrites;
            this.failure = failure;
        }

        @Override
        public void write(byte[] document) {
            if (writes++ < successfulWrites) {
                return;
            }
            throw new UncheckedIOException(failure);
        }
    }

    private static Outcome.Success<WebSearchResult> pageOutcome(int status, String body) {
        return new Outcome.Success<>(
                new WebSearchResult(status, new UpstreamPayload(body.getBytes(UTF_8)), null, null, null, null));
    }

    private static final class RecordingExchange implements WebSearchExchange {
        final List<WebSearchDispatch> dispatches = new ArrayList<>();
        final java.util.ArrayDeque<Outcome<WebSearchResult>> queued = new java.util.ArrayDeque<>();
        Runnable onDispatch = () -> {};
        Outcome<WebSearchResult> outcome = new Outcome.Success<>(
                new WebSearchResult(200, new UpstreamPayload("{}".getBytes(UTF_8)), null, null, null, null));

        void enqueue(Outcome<WebSearchResult> queued) {
            this.queued.add(queued);
        }

        @Override
        public Outcome<WebSearchResult> dispatch(WebSearchDispatch invocation) {
            dispatches.add(invocation);
            onDispatch.run();
            return queued.isEmpty() ? outcome : queued.poll();
        }
    }

    private static final class RecordingPagedPresenter implements WebPagedSearchPresenter {
        final List<PaginationService.PagedRun<WebSearchResult>> runs = new ArrayList<>();
        final List<WebSearchRequest> requests = new ArrayList<>();
        final List<OutputRequest> outputs = new ArrayList<>();
        final List<io.amscotti.bravesearch.domain.result.PagedSearch.Page<WebSearchResult>> observedPages =
                new ArrayList<>();
        int exitCode;

        @Override
        public int present(
                PaginationService.PagedRun<WebSearchResult> run,
                WebSearchRequest request,
                OutputRequest output,
                ResultWriter results,
                DiagnosticsSink diagnostics) {
            runs.add(run);
            requests.add(request);
            outputs.add(output);
            return run.succeeded() ? exitCode : ExitCodeMapper.forKind(run.failure().kind());
        }

        @Override
        public java.util.function.Consumer<io.amscotti.bravesearch.domain.result.PagedSearch.Page<WebSearchResult>> pageObserver(
                OutputRequest output, ResultWriter results, DiagnosticsSink diagnostics) {
            return observedPages::add;
        }
    }

    private static final class RecordingPresenter implements WebSearchPresenter {
        final List<Outcome<WebSearchResult>> outcomes = new ArrayList<>();
        final List<WebSearchRequest> requests = new ArrayList<>();
        final List<OutputRequest> outputs = new ArrayList<>();
        int exitCode;

        @Override
        public int present(
                Outcome<WebSearchResult> outcome,
                WebSearchRequest request,
                OutputRequest output,
                ResultWriter results,
                DiagnosticsSink diagnostics) {
            outcomes.add(outcome);
            requests.add(request);
            outputs.add(output);
            return switch (outcome) {
                case Outcome.Success<WebSearchResult> ignored -> exitCode;
                case Outcome.Failure<WebSearchResult> failure -> ExitCodeMapper.forKind(failure.kind());
            };
        }
    }

    private static final class FakeCredentialProvider implements CredentialProvider {
        int resolutions;
        Credential credential;
        CredentialResolutionException failure;

        @Override
        public Credential resolve() throws CredentialResolutionException {
            resolutions++;
            if (failure != null) {
                throw failure;
            }
            if (credential == null) {
                throw CredentialResolutionException.missing("environment BRAVE_SEARCH_TEST_KEY");
            }
            return credential;
        }

        @Override
        public ResolvedCredential resolveWithProvenance() throws CredentialResolutionException {
            return new ResolvedCredential(resolve(), "test source", false);
        }
    }
}

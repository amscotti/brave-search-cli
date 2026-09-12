package io.amscotti.bravesearch.adapter.cli.command.news;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.option.CommonSearchOptions;
import io.amscotti.bravesearch.adapter.cli.option.GlobalOptions;
import io.amscotti.bravesearch.adapter.cli.option.GoggleOptions;
import io.amscotti.bravesearch.adapter.cli.option.RemoteOptions;
import io.amscotti.bravesearch.adapter.cli.option.StrictOptionParsing;
import io.amscotti.bravesearch.adapter.cli.presentation.NewsPagedSearchPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.NewsSearchPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.OutputStreamResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.WriterDiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.news.NewsPagedSearchPresenterImpl;
import io.amscotti.bravesearch.adapter.cli.presentation.news.NewsProjectionExtractor;
import io.amscotti.bravesearch.adapter.cli.presentation.news.NewsSearchPresenterImpl;
import io.amscotti.bravesearch.adapter.config.GoggleFileLoader;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.LocalhostResolver;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.CredentialResolutionException;
import io.amscotti.bravesearch.application.port.out.NewsSearchDispatch;
import io.amscotti.bravesearch.application.port.out.NewsSearchExchange;
import io.amscotti.bravesearch.application.port.out.ResolvedCredential;
import io.amscotti.bravesearch.application.service.PaginationService;
import io.amscotti.bravesearch.application.stream.CancellationRegistry;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.goggles.Goggle;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.request.Freshness;
import io.amscotti.bravesearch.domain.request.NewsSearchRequest;
import io.amscotti.bravesearch.domain.request.SafeSearch;
import io.amscotti.bravesearch.domain.result.NewsSearchResult;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Grammar and dispatch contracts of the news command: every documented option reaches the
 * dispatched request exactly once, every local usage rejection fails with exit 2 before the
 * exchange is touched, the endpoint-undocumented spellings of the wider grammar are unknown
 * options here, and the page walk — which no upstream continuation field can stop — walks
 * exactly the budget it was given.
 */
final class NewsSearchCommandTest {

    private static final LocalhostResolver STUB_LOCALHOST = host -> List.of(InetAddress.getByName("127.0.0.1"));

    @TempDir
    Path goggleDirectory;

    private GoggleFiles files;

    @BeforeEach
    void goggleFiles() {
        files = new GoggleFiles(goggleDirectory);
    }

    @Test
    void dispatchesTheFullyMappedRequestAndOmitsEveryUnsuppliedBoolean() {
        Harness harness = new Harness();

        harness.run(
                "news",
                "--country",
                "ALL",
                "--search-lang",
                "de",
                "--ui-lang",
                "de-DE",
                "--safe-search",
                "off",
                "--freshness",
                "2026-01-01to2026-02-28",
                "--count",
                "50",
                "--page",
                "10",
                "--no-spellcheck",
                "--extra-snippets",
                "--no-include-fetch-metadata",
                "--no-operators",
                "--goggle",
                "https://example.com/goggle",
                "three word query");

        NewsSearchDispatch dispatched = harness.exchange.dispatches.getFirst();
        NewsSearchRequest request = dispatched.request();
        assertEquals("three word query", request.query());
        assertEquals("ALL", request.country());
        assertEquals("de", request.searchLang());
        assertEquals("de-DE", request.uiLang());
        assertEquals(SafeSearch.OFF, request.safeSearch());
        assertEquals(
                new Freshness.DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28)), request.freshness());
        assertEquals(50, request.count());
        assertEquals(10, request.page());
        assertEquals(9, request.upstreamOffset());
        assertEquals(Boolean.FALSE, request.spellcheck());
        assertEquals(Boolean.TRUE, request.extraSnippets());
        assertEquals(Boolean.FALSE, request.includeFetchMetadata());
        assertEquals(Boolean.FALSE, request.operators());
        assertEquals(
                List.of(new Goggle.UrlReference("https://example.com/goggle")), request.goggles());
        assertEquals(BraveApiOrigin.production(), dispatched.origin());
    }

    @Test
    void dispatchesTheBareQueryUnderTheProductionOrigin() {
        Harness harness = new Harness();

        harness.run("news", "café query");

        NewsSearchRequest request = harness.exchange.dispatches.getFirst().request();
        assertEquals("café query", request.query());
        assertNull(request.country());
        assertNull(request.count());
        assertNull(request.page());
        assertNull(request.spellcheck());
        assertNull(request.extraSnippets());
        assertNull(request.includeFetchMetadata());
        assertNull(request.operators());
        assertEquals(List.of(), request.goggles());
    }

    @Test
    void countBeyondTheDocumentedNewsBoundIsRejectedBeforeAnyDispatch() {
        Harness harness = new Harness();

        Harness.RunResult result = harness.run("news", "--count", "51", "q");

        assertEquals(2, result.exitCode(), () -> result.describe());
        assertTrue(result.stderr().contains("Usage:"), () -> result.describe());
        assertEquals(0, harness.exchange.dispatches.size(), "an invalid request must never be dispatched");
    }

    @Test
    void webOnlySpellingsAreUnknownOptionsOfTheNewsCommand() {
        Harness harness = new Harness();

        for (String webOnly : new String[] {"--text-decorations", "--result-filter", "videos", "--units", "metric",
                "--enable-rich-callback", "--loc-lat", "47.6", "--loc-timezone", "Europe/Berlin"}) {
            Harness.RunResult result = harness.run("news", webOnly, "q");
            assertEquals(2, result.exitCode(), () -> "the web-only spelling must be unknown: " + result.describe());
            assertTrue(result.stderr().contains("Unknown option"), () -> result.describe());
        }
        assertEquals(0, harness.exchange.dispatches.size());
    }

    @Test
    void rawOutputIsAcceptedForTheSingleRequestNewsCommand() {
        Harness harness = new Harness();

        Harness.RunResult result = harness.run("news", "--output", "raw", "q");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(1, harness.exchange.dispatches.size());
    }

    @Test
    void allPagesRejectsRawOutputBeforeAnyDispatch() {
        Harness harness = new Harness();

        Harness.RunResult result = harness.run("news", "--all-pages", "--output", "raw", "q");

        assertEquals(2, result.exitCode(), () -> result.describe());
        assertTrue(result.stderr().contains("Usage:"), () -> result.describe());
        assertEquals(0, harness.exchange.dispatches.size());
    }

    @Test
    void paginationGrammarRejectsEveryInvalidCombinationBeforeDispatch() {
        Harness harness = new Harness();

        assertEquals(2, harness.run("news", "--all-pages", "--page", "3", "q").exitCode());
        assertEquals(2, harness.run("news", "--max-pages", "3", "q").exitCode());
        assertEquals(2, harness.run("news", "--all-pages", "--max-pages", "0", "q").exitCode());
        assertEquals(2, harness.run("news", "--all-pages", "--max-pages", "11", "q").exitCode());
        assertEquals(0, harness.exchange.dispatches.size());
    }

    @Test
    void allPagesWalksExactlyTheDocumentedMaximumOfTenRequests() {
        Harness harness = new Harness();
        harness.exchange.outcome = pageOutcome("{\"news\":{\"results\":[]}}");

        Harness.RunResult result = harness.run("news", "--all-pages", "--output", "jsonl", "q");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(
                10,
                harness.exchange.dispatches.size(),
                "the news walk has no continuation field: the budget of ten pages is its sole terminator");
        for (int page = 1; page <= 10; page++) {
            assertEquals(page, harness.exchange.dispatches.get(page - 1).request().page());
        }
        assertTrue(
                result.stdout().contains("\"requested_pages\":10"),
                "the summary carries the full budget: " + result.stdout());
        assertTrue(result.stdout().contains("\"received_pages\":10"), result.stdout());
    }

    @Test
    void maxPagesBoundsTheWalk() {
        Harness harness = new Harness();
        harness.exchange.outcome = pageOutcome("{\"news\":{\"results\":[]}}");

        Harness.RunResult result = harness.run("news", "--all-pages", "--max-pages", "3", "--output", "jsonl", "q");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(3, harness.exchange.dispatches.size());
        assertTrue(result.stdout().contains("\"requested_pages\":3"), result.stdout());
        assertTrue(result.stdout().contains("\"received_pages\":3"), result.stdout());
    }

    @Test
    void aShortPageNeverStopsTheWalkEarly() {
        Harness harness = new Harness();
        harness.exchange.outcome = pageOutcome(
                "{\"news\":{\"results\":[{\"title\":\"Only One\",\"url\":\"https://example.com/one\"}]}}");

        Harness.RunResult result = harness.run("news", "--all-pages", "--max-pages", "2", "--output", "jsonl", "q");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(
                2, harness.exchange.dispatches.size(), "a short page is never read as exhaustion");
        assertTrue(result.stdout().contains("\"duplicates_removed\":1"), result.stdout());
    }

    @Test
    void aFailingFirstPageHandsTheFailureKindToThePresenterStatus() {
        Harness harness = new Harness();
        harness.exchange.outcome =
                new Outcome.Failure<>(FailureKind.AUTHENTICATION, "upstream exchange failed with status 401");

        Harness.RunResult result = harness.run("news", "q");

        assertEquals(4, result.exitCode(), () -> result.describe());
    }

    @Test
    void verboseRunsReportLoadedGoggleFilesAsPathAndByteLength() throws IOException {
        Path file = files.createFile("rules.goggle", "! " + GoggleFiles.SENTINEL + "\n+site:example.com");
        long bytes = Files.size(file);
        Harness verbose = new Harness();

        Harness.RunResult reported = verbose.run("news", "--goggle-file", file.toString(), "--verbose", "q");

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
        Harness.RunResult silent = plain.run("news", "--goggle-file", file.toString(), "q");

        assertEquals(0, silent.exitCode(), () -> silent.describe());
        assertFalse(
                silent.stderr().contains("goggle file"),
                () -> "without --verbose no goggle-file report is written: " + silent.describe());
    }

    private static Outcome.Success<NewsSearchResult> pageOutcome(String body) {
        return new Outcome.Success<>(
                new NewsSearchResult(200, new UpstreamPayload(body.getBytes(UTF_8)), null, null, null, null));
    }

    /** Root fixture shaped like the process root: shared globals plus the news subcommand. */
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
            JsonMappers mappers = new JsonMappers();
            JsonlCodec jsonl = new JsonlCodec(mappers);
            NewsSearchPresenter presenter = new NewsSearchPresenterImpl(
                    new EnvelopeCodec(mappers),
                    jsonl,
                    new io.amscotti.bravesearch.adapter.cli.presentation.json.RawCodec(),
                    new NewsProjectionExtractor(mappers),
                    (mode, diagnostics, results, quiet) ->
                            new io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings(
                                    mode, NewsSearchPresenter.COMMAND, diagnostics, results, jsonl, quiet));
            NewsPagedSearchPresenter pagedPresenter = new NewsPagedSearchPresenterImpl(
                    new EnvelopeCodec(mappers),
                    jsonl,
                    new NewsProjectionExtractor(mappers),
                    (mode, diagnostics, results, quiet) ->
                            new io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings(
                                    mode, NewsPagedSearchPresenter.COMMAND, diagnostics, results, jsonl, quiet));
            NewsSearchCommand news = new NewsSearchCommand(
                    globals,
                    new RemoteOptions(STUB_LOCALHOST),
                    new CommonSearchOptions(),
                    new GoggleOptions(new GoggleFileLoader()),
                    stored,
                    loopback,
                    exchange,
                    presenter,
                    pagedPresenter,
                    new PaginationService<>(body -> true, (resetAt, cancellation) -> Duration.ZERO),
                    cancellations,
                    writer,
                    WriterDiagnosticsSink::new);
            Root root = new Root();
            root.globals = globals;
            CommandLine commandLine = new CommandLine(root);
            commandLine.addSubcommand("news", new CommandLine(news));
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

    private static final class RecordingExchange implements NewsSearchExchange {
        final List<NewsSearchDispatch> dispatches = new ArrayList<>();
        Outcome<NewsSearchResult> outcome = new Outcome.Success<>(
                new NewsSearchResult(200, new UpstreamPayload("{}".getBytes(UTF_8)), null, null, null, null));

        @Override
        public Outcome<NewsSearchResult> dispatch(NewsSearchDispatch invocation) {
            dispatches.add(invocation);
            return outcome;
        }
    }

    private static final class FakeCredentialProvider implements CredentialProvider {
        Credential credential;

        @Override
        public Credential resolve() throws CredentialResolutionException {
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

    /** Goggle-file fixtures under one temporary directory, built around a leak sentinel. */
    private static final class GoggleFiles {

        static final String SENTINEL = "NEWS-GOGGLE-SENTINEL-5c1d9f";

        private final Path directory;

        GoggleFiles(Path directory) {
            this.directory = directory;
        }

        Path createFile(String name, String content) throws IOException {
            Path file = directory.resolve(name);
            Files.write(file, content.getBytes(UTF_8));
            return file;
        }
    }

    private static Credential credential(String token) {
        return Credential.of(token.getBytes(UTF_8));
    }
}

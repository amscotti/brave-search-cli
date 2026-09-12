package io.amscotti.bravesearch.adapter.cli.command.videos;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.option.CommonSearchOptions;
import io.amscotti.bravesearch.adapter.cli.option.GlobalOptions;
import io.amscotti.bravesearch.adapter.cli.option.RemoteOptions;
import io.amscotti.bravesearch.adapter.cli.option.StrictOptionParsing;
import io.amscotti.bravesearch.adapter.cli.presentation.OutputStreamResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.VideoPagedSearchPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.VideoSearchPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.WriterDiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.videos.VideoPagedSearchPresenterImpl;
import io.amscotti.bravesearch.adapter.cli.presentation.videos.VideoProjectionExtractor;
import io.amscotti.bravesearch.adapter.cli.presentation.videos.VideoSearchPresenterImpl;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.LocalhostResolver;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.CredentialResolutionException;
import io.amscotti.bravesearch.application.port.out.ResolvedCredential;
import io.amscotti.bravesearch.application.port.out.VideoSearchDispatch;
import io.amscotti.bravesearch.application.port.out.VideoSearchExchange;
import io.amscotti.bravesearch.application.service.PaginationService;
import io.amscotti.bravesearch.application.stream.CancellationRegistry;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.request.Freshness;
import io.amscotti.bravesearch.domain.request.SafeSearch;
import io.amscotti.bravesearch.domain.request.VideoSearchRequest;
import io.amscotti.bravesearch.domain.result.VideoSearchResult;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Grammar and dispatch contracts of the videos command: every documented option reaches
 * the dispatched request exactly once, every local usage rejection fails with exit 2
 * before the exchange is touched, the endpoint-undocumented spellings of the wider
 * grammar — the whole goggle family included, because the videos endpoint documents no
 * Goggles — are unknown options here, and the page walk, which no upstream continuation
 * field can stop, walks exactly the budget it was given.
 */
final class VideoSearchCommandTest {

    private static final LocalhostResolver STUB_LOCALHOST = host -> List.of(InetAddress.getByName("127.0.0.1"));

    @Test
    void dispatchesTheFullyMappedRequestAndOmitsEveryUnsuppliedBoolean() {
        Harness harness = new Harness();

        harness.run(
                "videos",
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
                "--no-include-fetch-metadata",
                "--operators",
                "three word query");

        VideoSearchDispatch dispatched = harness.exchange.dispatches.getFirst();
        VideoSearchRequest request = dispatched.request();
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
        assertEquals(Boolean.FALSE, request.includeFetchMetadata());
        assertEquals(Boolean.TRUE, request.operators());
        assertEquals(BraveApiOrigin.production(), dispatched.origin());
    }

    @Test
    void dispatchesTheBareQueryUnderTheProductionOrigin() {
        Harness harness = new Harness();

        harness.run("videos", "café query");

        VideoSearchRequest request = harness.exchange.dispatches.getFirst().request();
        assertEquals("café query", request.query());
        assertNull(request.country());
        assertNull(request.count());
        assertNull(request.page());
        assertNull(request.spellcheck());
        assertNull(request.includeFetchMetadata());
        assertNull(request.operators());
    }

    @Test
    void countBeyondTheDocumentedVideosBoundIsRejectedBeforeAnyDispatch() {
        Harness harness = new Harness();

        Harness.RunResult result = harness.run("videos", "--count", "51", "q");

        assertEquals(2, result.exitCode(), () -> result.describe());
        assertTrue(result.stderr().contains("Usage:"), () -> result.describe());
        assertEquals(0, harness.exchange.dispatches.size(), "an invalid request must never be dispatched");
    }

    @Test
    void goggleSpellingsAreUnknownOptionsOfTheVideosCommand() {
        Harness harness = new Harness();

        for (String goggleSpelling : new String[] {"--goggle", "--goggle-file", "--include-site", "--exclude-site"}) {
            Harness.RunResult result = harness.run("videos", goggleSpelling, "a.example", "q");
            assertEquals(
                    2,
                    result.exitCode(),
                    () -> "the videos endpoint documents no goggles, so the spelling must be unknown: "
                            + result.describe());
            assertTrue(result.stderr().contains("Unknown option"), () -> result.describe());
        }
        assertEquals(0, harness.exchange.dispatches.size());
    }

    @Test
    void undocumentedSharedAndWebOnlySpellingsAreUnknownOrRejectedHere() {
        Harness harness = new Harness();

        for (String webOnly : new String[] {"--text-decorations", "--result-filter", "videos", "--units", "metric",
                "--enable-rich-callback", "--loc-lat", "47.6", "--loc-timezone", "Europe/Berlin", "--extra-snippets"}) {
            Harness.RunResult result = harness.run("videos", webOnly, "q");
            assertEquals(2, result.exitCode(), () -> "the spelling must be unknown: " + result.describe());
            assertTrue(result.stderr().contains("Unknown option"), () -> result.describe());
        }
        assertEquals(0, harness.exchange.dispatches.size());
    }

    @Test
    void rawOutputIsAcceptedForTheSingleRequestVideosCommand() {
        Harness harness = new Harness();

        Harness.RunResult result = harness.run("videos", "--output", "raw", "q");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(1, harness.exchange.dispatches.size());
    }

    @Test
    void allPagesRejectsRawOutputBeforeAnyDispatch() {
        Harness harness = new Harness();

        Harness.RunResult result = harness.run("videos", "--all-pages", "--output", "raw", "q");

        assertEquals(2, result.exitCode(), () -> result.describe());
        assertTrue(result.stderr().contains("Usage:"), () -> result.describe());
        assertEquals(0, harness.exchange.dispatches.size());
    }

    @Test
    void paginationGrammarRejectsEveryInvalidCombinationBeforeDispatch() {
        Harness harness = new Harness();

        assertEquals(2, harness.run("videos", "--all-pages", "--page", "3", "q").exitCode());
        assertEquals(2, harness.run("videos", "--max-pages", "3", "q").exitCode());
        assertEquals(2, harness.run("videos", "--all-pages", "--max-pages", "0", "q").exitCode());
        assertEquals(2, harness.run("videos", "--all-pages", "--max-pages", "11", "q").exitCode());
        assertEquals(0, harness.exchange.dispatches.size());
    }

    @Test
    void allPagesWalksExactlyTheDocumentedMaximumOfTenRequests() {
        Harness harness = new Harness();
        harness.exchange.outcome = pageOutcome("{\"results\":[]}");

        Harness.RunResult result = harness.run("videos", "--all-pages", "--output", "jsonl", "q");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(
                10,
                harness.exchange.dispatches.size(),
                "the videos walk has no continuation field: the budget of ten pages is its sole terminator");
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
        harness.exchange.outcome = pageOutcome("{\"results\":[]}");

        Harness.RunResult result = harness.run("videos", "--all-pages", "--max-pages", "3", "--output", "jsonl", "q");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(3, harness.exchange.dispatches.size());
        assertTrue(result.stdout().contains("\"requested_pages\":3"), result.stdout());
        assertTrue(result.stdout().contains("\"received_pages\":3"), result.stdout());
    }

    @Test
    void aShortPageNeverStopsTheWalkEarly() {
        Harness harness = new Harness();
        harness.exchange.outcome = pageOutcome(
                "{\"results\":[{\"title\":\"Only One\",\"url\":\"https://example.com/one\"}]}");

        Harness.RunResult result = harness.run("videos", "--all-pages", "--max-pages", "2", "--output", "jsonl", "q");

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

        Harness.RunResult result = harness.run("videos", "q");

        assertEquals(4, result.exitCode(), () -> result.describe());
    }

    private static Outcome.Success<VideoSearchResult> pageOutcome(String body) {
        return new Outcome.Success<>(
                new VideoSearchResult(200, new UpstreamPayload(body.getBytes(UTF_8)), null, null, null, null));
    }

    /** Root fixture shaped like the process root: shared globals plus the videos subcommand. */
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
            VideoSearchPresenter presenter = new VideoSearchPresenterImpl(
                    new EnvelopeCodec(mappers),
                    jsonl,
                    new io.amscotti.bravesearch.adapter.cli.presentation.json.RawCodec(),
                    new VideoProjectionExtractor(mappers),
                    (mode, diagnostics, results, quiet) ->
                            new io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings(
                                    mode, VideoSearchPresenter.COMMAND, diagnostics, results, jsonl, quiet));
            VideoPagedSearchPresenter pagedPresenter = new VideoPagedSearchPresenterImpl(
                    new EnvelopeCodec(mappers),
                    jsonl,
                    new VideoProjectionExtractor(mappers),
                    (mode, diagnostics, results, quiet) ->
                            new io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings(
                                    mode, VideoPagedSearchPresenter.COMMAND, diagnostics, results, jsonl, quiet));
            VideoSearchCommand videos = new VideoSearchCommand(
                    globals,
                    new RemoteOptions(STUB_LOCALHOST),
                    new CommonSearchOptions(),
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
            commandLine.addSubcommand("videos", new CommandLine(videos));
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

    private static final class RecordingExchange implements VideoSearchExchange {
        final List<VideoSearchDispatch> dispatches = new ArrayList<>();
        Outcome<VideoSearchResult> outcome = new Outcome.Success<>(
                new VideoSearchResult(200, new UpstreamPayload("{}".getBytes(UTF_8)), null, null, null, null));

        @Override
        public Outcome<VideoSearchResult> dispatch(VideoSearchDispatch invocation) {
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

    private static Credential credential(String token) {
        return Credential.of(token.getBytes(UTF_8));
    }
}

package io.amscotti.bravesearch.adapter.cli.command.images;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.option.CommonSearchOptions;
import io.amscotti.bravesearch.adapter.cli.option.GlobalOptions;
import io.amscotti.bravesearch.adapter.cli.option.RemoteOptions;
import io.amscotti.bravesearch.adapter.cli.option.StrictOptionParsing;
import io.amscotti.bravesearch.adapter.cli.presentation.ImageSearchPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.OutputStreamResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.WriterDiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.images.ImageProjectionExtractor;
import io.amscotti.bravesearch.adapter.cli.presentation.images.ImageSearchPresenterImpl;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.LocalhostResolver;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.CredentialResolutionException;
import io.amscotti.bravesearch.application.port.out.ImageSearchDispatch;
import io.amscotti.bravesearch.application.port.out.ImageSearchExchange;
import io.amscotti.bravesearch.application.port.out.ResolvedCredential;
import io.amscotti.bravesearch.bootstrap.ExchangeRegistry;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.request.ImageSafeSearch;
import io.amscotti.bravesearch.domain.request.ImageSearchRequest;
import io.amscotti.bravesearch.domain.result.ImageSearchResult;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Grammar and dispatch contracts of the images command: every documented option reaches
 * the dispatched request exactly once, every local usage rejection — the count bound of
 * 200, the moderate SafeSearch level the endpoint does not document, and the shared
 * spellings the images endpoint leaves undocumented — fails with exit 2 before the
 * exchange is touched, and the endpoint-external spellings (Goggles, the whole page-walk
 * family, every web-only option) are unknown options here, because the images endpoint is
 * one single non-paginated request.
 */
final class ImageSearchCommandTest {

    private static final LocalhostResolver STUB_LOCALHOST = host -> List.of(InetAddress.getByName("127.0.0.1"));

    @Test
    void dispatchesTheFullyMappedRequestAndOmitsEveryUnsuppliedBoolean() {
        Harness harness = new Harness();

        harness.run(
                "images",
                "--country",
                "ALL",
                "--search-lang",
                "de",
                "--safe-search",
                "strict",
                "--count",
                "200",
                "--no-spellcheck",
                "three word query");

        ImageSearchDispatch dispatched = harness.exchange.dispatches.getFirst();
        ImageSearchRequest request = dispatched.request();
        assertEquals("three word query", request.query());
        assertEquals("ALL", request.country());
        assertEquals("de", request.searchLang());
        assertEquals(ImageSafeSearch.STRICT, request.safeSearch());
        assertEquals(200, request.count());
        assertEquals(Boolean.FALSE, request.spellcheck());
        assertEquals(BraveApiOrigin.production(), dispatched.origin());
    }

    @Test
    void dispatchesTheBareQueryUnderTheProductionOrigin() {
        Harness harness = new Harness();

        harness.run("images", "café query");

        ImageSearchRequest request = harness.exchange.dispatches.getFirst().request();
        assertEquals("café query", request.query());
        assertNull(request.country());
        assertNull(request.searchLang());
        assertNull(request.safeSearch());
        assertNull(request.count());
        assertNull(request.spellcheck());
    }

    @Test
    void countBeyondTheDocumentedImagesBoundIsRejectedBeforeAnyDispatch() {
        Harness harness = new Harness();

        Harness.RunResult result = harness.run("images", "--count", "201", "q");

        assertEquals(2, result.exitCode(), () -> result.describe());
        assertTrue(result.stderr().contains("Usage:"), () -> result.describe());
        assertEquals(0, harness.exchange.dispatches.size(), "an invalid request must never be dispatched");
    }

    @Test
    void moderateSafeSearchIsRejectedBeforeAnyDispatch() {
        Harness harness = new Harness();

        Harness.RunResult result = harness.run("images", "--safe-search", "moderate", "q");

        assertEquals(2, result.exitCode(), () -> result.describe());
        assertTrue(
                result.stderr().contains("safe-search must be off or strict for images"), () -> result.describe());
        assertEquals(0, harness.exchange.dispatches.size(), "an invalid request must never be dispatched");
    }

    @Test
    void paginationSpellingsAreRejectedBeforeAnyDispatch() {
        Harness harness = new Harness();

        Harness.RunResult page = harness.run("images", "--page", "2", "q");
        assertEquals(2, page.exitCode(), () -> page.describe());
        assertTrue(page.stderr().contains("--page is not accepted by this command"), () -> page.describe());

        for (String walkSpelling : new String[] {"--all-pages", "--max-pages"}) {
            Harness.RunResult walk = harness.run("images", walkSpelling, "3", "q");
            assertEquals(
                    2,
                    walk.exitCode(),
                    () -> "the images endpoint documents no pagination, so the spelling must be unknown: "
                            + walk.describe());
            assertTrue(walk.stderr().contains("Unknown option"), () -> walk.describe());
        }
        assertEquals(0, harness.exchange.dispatches.size());
    }

    @Test
    void undocumentedSharedSpellingsAreRejectedHere() {
        Harness harness = new Harness();

        for (String undocumented : new String[] {"--ui-lang", "--freshness"}) {
            Harness.RunResult result = harness.run("images", undocumented, "en", "q");
            assertEquals(2, result.exitCode(), () -> "the spelling must be refused: " + result.describe());
            assertTrue(result.stderr().contains("is not accepted by this command"), () -> result.describe());
        }
        assertEquals(0, harness.exchange.dispatches.size());
    }

    @Test
    void goggleAndWebOnlySpellingsAreUnknownOptionsOfTheImagesCommand() {
        Harness harness = new Harness();

        for (String spelling : new String[] {"--goggle", "--goggle-file", "--include-site", "--exclude-site",
                "--text-decorations", "--units", "metric", "--enable-rich-callback", "--loc-lat", "47.6",
                "--extra-snippets", "--operators"}) {
            Harness.RunResult result = harness.run("images", spelling, "a.example", "q");
            assertEquals(2, result.exitCode(), () -> "the spelling must be unknown: " + result.describe());
            assertTrue(result.stderr().contains("Unknown option"), () -> result.describe());
        }
        assertEquals(0, harness.exchange.dispatches.size());
    }

    @Test
    void rawOutputIsAcceptedForTheSingleRequestImagesCommand() {
        Harness harness = new Harness();

        Harness.RunResult result = harness.run("images", "--output", "raw", "q");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(1, harness.exchange.dispatches.size());
    }

    @Test
    void aFailingExchangeHandsTheFailureKindToThePresenterStatus() {
        Harness harness = new Harness();
        harness.exchange.outcome =
                new Outcome.Failure<>(FailureKind.AUTHENTICATION, "upstream exchange failed with status 401");

        Harness.RunResult result = harness.run("images", "q");

        assertEquals(4, result.exitCode(), () -> result.describe());
    }

    /** Root fixture shaped like the process root: shared globals plus the images subcommand. */
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

        Harness() {
            stored.credential = credential("stored-production-token");
            loopback.credential = credential("loopback-test-key");
        }

        RunResult run(String... args) {
            JsonMappers mappers = new JsonMappers();
            JsonlCodec jsonl = new JsonlCodec(mappers);
            ImageSearchPresenter presenter = new ImageSearchPresenterImpl(
                    new EnvelopeCodec(mappers),
                    jsonl,
                    new io.amscotti.bravesearch.adapter.cli.presentation.json.RawCodec(),
                    new ImageProjectionExtractor(mappers),
                    (mode, diagnostics, results, quiet) ->
                            new io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings(
                                    mode, ImageSearchPresenter.COMMAND, diagnostics, results, jsonl, quiet));
            ImageSearchCommand images = new ImageSearchCommand(
                    globals,
                    new RemoteOptions(STUB_LOCALHOST),
                    new CommonSearchOptions(),
                    stored,
                    loopback,
                    exchange,
                    presenter,
                    new ExchangeRegistry(),
                    new OutputStreamResultWriter(stdoutBytes),
                    WriterDiagnosticsSink::new);
            Root root = new Root();
            root.globals = globals;
            CommandLine commandLine = new CommandLine(root);
            commandLine.addSubcommand("images", new CommandLine(images));
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

    private static final class RecordingExchange implements ImageSearchExchange {
        final List<ImageSearchDispatch> dispatches = new ArrayList<>();
        Outcome<ImageSearchResult> outcome = new Outcome.Success<>(
                new ImageSearchResult(200, new UpstreamPayload("{}".getBytes(UTF_8)), null, null, null, null));

        @Override
        public Outcome<ImageSearchResult> dispatch(ImageSearchDispatch invocation) {
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

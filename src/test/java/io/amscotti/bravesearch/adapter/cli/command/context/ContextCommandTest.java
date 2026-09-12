package io.amscotti.bravesearch.adapter.cli.command.context;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.exit.ExitCodeMapper;
import io.amscotti.bravesearch.adapter.cli.option.CommonSearchOptions;
import io.amscotti.bravesearch.adapter.cli.option.GlobalOptions;
import io.amscotti.bravesearch.adapter.cli.option.RemoteOptions;
import io.amscotti.bravesearch.adapter.cli.option.StrictOptionParsing;
import io.amscotti.bravesearch.adapter.cli.presentation.ContextPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.DiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.OutputStreamResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.WriterDiagnosticsSink;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.LocalhostResolver;
import io.amscotti.bravesearch.application.port.out.ContextDispatch;
import io.amscotti.bravesearch.application.port.out.ContextExchange;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.CredentialResolutionException;
import io.amscotti.bravesearch.application.port.out.ResolvedCredential;
import io.amscotti.bravesearch.bootstrap.ExchangeRegistry;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.output.OutputMode;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.request.ContextRequest;
import io.amscotti.bravesearch.domain.request.ContextThreshold;
import io.amscotti.bravesearch.domain.request.Freshness;
import io.amscotti.bravesearch.domain.request.SafeSearch;
import io.amscotti.bravesearch.domain.result.ContextResult;
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
 * Grammar and dispatch contracts of the context command: every documented option reaches
 * the dispatched request exactly once, every local usage rejection — the shared web-only
 * spellings this endpoint does not document, the timezone location flag, every numeric
 * bound, the enum tokens — fails with exit 2 before the exchange is touched, the loopback
 * credential seam routes independently of the stored credential, and each upstream failure
 * kind keeps its own exit status. Context is one single non-paginated request: the walk
 * flags of the web command are rejected as unknown options, never silently ignored.
 */
final class ContextCommandTest {

    private static final LocalhostResolver STUB_LOCALHOST = host -> List.of(InetAddress.getByName("127.0.0.1"));

    @Test
    void dispatchesTheFullyMappedRequestAndOmitsEveryUnsuppliedOptional() {
        Harness harness = new Harness();
        ContextRequest expected = ContextRequest.builder("hello world")
                .country("DE")
                .searchLang("de")
                .safeSearch(SafeSearch.STRICT)
                .freshness(new Freshness.DateRange(LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 3)))
                .count(7)
                .maxUrls(10)
                .maxTokens(4096)
                .maxSnippets(100)
                .maxTokensPerUrl(2048)
                .maxSnippetsPerUrl(50)
                .threshold(ContextThreshold.BALANCED)
                .sourceMetadata(true)
                .enableLocal(false)
                .location(new ContextRequest.Location(
                        40.5, -73.5, "Montreal", "QC", "Quebec", "CA", "H2X"))
                .build();

        Harness.RunResult result = harness.run(
                "context",
                "--country",
                "DE",
                "--search-lang",
                "de",
                "--safe-search",
                "strict",
                "--freshness",
                "2024-01-02to2024-01-03",
                "--count",
                "7",
                "--max-urls",
                "10",
                "--max-tokens",
                "4096",
                "--max-snippets",
                "100",
                "--max-tokens-per-url",
                "2048",
                "--max-snippets-per-url",
                "50",
                "--threshold",
                "balanced",
                "--source-metadata",
                "--local",
                "off",
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
                "hello world");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(1, harness.exchange.dispatches.size(), "exactly one exchange is dispatched");
        assertEquals(expected, harness.exchange.dispatches.getFirst().request(), "the request must map every option");
        assertEquals(1, harness.presenter.outcomes.size(), "the presenter renders exactly the one outcome");
        assertEquals("hello world", harness.presenter.requests.getFirst().query());
        assertEquals(
                new OutputRequest(false, OutputMode.HUMAN, false, false, false),
                harness.presenter.outputs.getFirst(),
                "no output flag means the human channel with every flag absent");
        assertEquals("", result.stderr(), () -> result.describe());
    }

    @Test
    void localAutoAndAnAbsentLocalFlagBothStayOmittedFromTheRequest() {
        Harness auto = new Harness();
        Harness.RunResult explicitAuto = auto.run("context", "--local", "auto", "q");
        assertEquals(0, explicitAuto.exitCode(), () -> explicitAuto.describe());
        assertNull(auto.exchange.dispatches.getFirst().request().enableLocal(), "auto is the omitted wire state");

        Harness absent = new Harness();
        Harness.RunResult noFlag = absent.run("context", "q");
        assertEquals(0, noFlag.exitCode(), () -> noFlag.describe());
        assertNull(absent.exchange.dispatches.getFirst().request().enableLocal(), "no flag is the omitted wire state");

        Harness on = new Harness();
        Harness.RunResult pinned = on.run("context", "--local", "on", "q");
        assertEquals(0, pinned.exitCode(), () -> pinned.describe());
        assertEquals(
                Boolean.TRUE, on.exchange.dispatches.getFirst().request().enableLocal(), "on pins the true wire value");
    }

    @Test
    void dispatchesTheBareQueryUnderTheProductionOriginAndDefaultBudgets() {
        Harness harness = new Harness();

        Harness.RunResult result = harness.run("context", "q");

        assertEquals(0, result.exitCode(), () -> result.describe());
        ContextDispatch dispatch = harness.exchange.dispatches.getFirst();
        assertEquals(ContextRequest.builder("q").build(), dispatch.request());
        assertEquals(BraveApiOrigin.production(), dispatch.origin());
        assertTrue(dispatch.origin().credentialsAllowed(), "the default origin is the production origin");
        assertEquals(Duration.ofSeconds(30), dispatch.totalTimeout(), "the documented default total budget");
        assertEquals(Duration.ofSeconds(10), dispatch.connectTimeout(), "the documented default connect budget");
        assertNull(dispatch.pinnedApiVersion(), "no Api-Version pin travels unless one was given");
        assertEquals(1, harness.stored.resolutions, "the stored credential source is consulted once");
    }

    @Test
    void rawOutputIsAcceptedForTheSingleRequestContextCommand() {
        Harness harness = new Harness();

        Harness.RunResult result = harness.run("context", "--output", "raw", "q");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(1, harness.exchange.dispatches.size());
        assertEquals(OutputMode.RAW, harness.presenter.outputs.getFirst().mode());
    }

    @Test
    void webOnlySharedOptionsAreRejectedBeforeAnyDispatchNamingEverySuppliedSpelling() {
        assertUsageRejection("--page is named", new String[] {"context", "--page", "2", "q"}, "--page");
        assertUsageRejection("--ui-lang is named", new String[] {"context", "--ui-lang", "en-US", "q"}, "--ui-lang");
        assertUsageRejection(
                "--spellcheck is named", new String[] {"context", "--spellcheck", "q"}, "--spellcheck");
        assertUsageRejection(
                "the negated spellcheck form is named too",
                new String[] {"context", "--no-spellcheck", "q"},
                "--spellcheck");
        assertUsageRejection(
                "every supplied spelling is named together",
                new String[] {"context", "--page", "2", "--ui-lang", "en-US", "--spellcheck", "q"},
                "--page");
    }

    @Test
    void webOnlyCommandOptionsAreUnknownSpellingsForContext() {
        List<String[]> rejections = List.of(
                new String[] {"context", "--all-pages", "q"},
                new String[] {"context", "--max-pages", "3", "q"},
                new String[] {"context", "--goggle", "https://example.com/rules.goggle", "q"},
                new String[] {"context", "--goggle-file", "/tmp/rules.goggle", "q"},
                new String[] {"context", "--include-site", "example.com", "q"},
                new String[] {"context", "--exclude-site", "example.com", "q"},
                new String[] {"context", "--text-decorations", "q"},
                new String[] {"context", "--result-filter", "news", "q"},
                new String[] {"context", "--units", "metric", "q"},
                new String[] {"context", "--extra-snippets", "q"},
                new String[] {"context", "--include-fetch-metadata", "q"},
                new String[] {"context", "--operators", "q"},
                new String[] {"context", "--enable-rich-callback", "q"});

        for (String[] args : rejections) {
            assertUsageRejection("unknown spelling: " + String.join(" ", args), args, null);
        }
    }

    @Test
    void supplyingLocTimezoneIsAUsageErrorBeforeAnyDispatch() {
        Harness harness = new Harness();

        Harness.RunResult result = harness.run("context", "--loc-timezone", "America/Toronto", "q");

        assertEquals(2, result.exitCode(), "the undocumented timezone header is refused locally");
        assertTrue(result.stderr().contains("--loc-timezone"), () -> result.describe());
        assertEquals(0, harness.exchange.dispatches.size(), "no request may leave before the rejection");
        assertEquals("", result.stdout());
    }

    @Test
    void everyUsageRejectionFailsWithExit2BeforeTheExchangeIsTouched() {
        List<String[]> rejections = List.of(
                new String[] {"context", "--count", "0", "q"},
                new String[] {"context", "--count", "51", "q"},
                new String[] {"context", "--max-urls", "0", "q"},
                new String[] {"context", "--max-urls", "51", "q"},
                new String[] {"context", "--max-tokens", "1023", "q"},
                new String[] {"context", "--max-tokens", "32769", "q"},
                new String[] {"context", "--max-snippets", "0", "q"},
                new String[] {"context", "--max-snippets", "257", "q"},
                new String[] {"context", "--max-tokens-per-url", "511", "q"},
                new String[] {"context", "--max-tokens-per-url", "8193", "q"},
                new String[] {"context", "--max-snippets-per-url", "0", "q"},
                new String[] {"context", "--max-snippets-per-url", "101", "q"},
                new String[] {"context", "--max-tokens", "many", "q"},
                new String[] {"context", "--threshold", "aggressive", "q"},
                new String[] {"context", "--threshold", "STRICT", "q"},
                new String[] {"context", "--local", "sometimes", "q"},
                new String[] {"context", "--country", "ALL", "q"},
                new String[] {"context", "--country", "DEU", "q"},
                new String[] {"context", "--search-lang", "e", "q"},
                new String[] {"context", "--freshness", "2026-02-30to2026-03-01", "q"},
                new String[] {"context", "--safe-search", "bogus", "q"},
                new String[] {"context", "--loc-lat", "40.5", "q"},
                new String[] {"context", "--loc-long", "-73.5", "q"},
                new String[] {"context", "--loc-lat", "91", "--loc-long", "0", "q"},
                new String[] {"context", "--loc-lat", "NaN", "--loc-long", "0", "q"},
                new String[] {"context", "--loc-long", "Infinity", "--loc-lat", "0", "q"},
                new String[] {"context", "--loc-state", "qc", "q"},
                new String[] {"context", "--loc-country", "ca", "q"},
                new String[] {"context", "--loc-city", " ", "q"},
                new String[] {"context", fiftyOneWords()},
                new String[] {"context", "a".repeat(401)},
                new String[] {"context", "   "},
                new String[] {"context"},
                new String[] {"context", "--output", "bogus", "q"},
                new String[] {"context", "--pretty", "--output", "raw", "q"},
                new String[] {"context", "--verbose", "--quiet", "q"},
                new String[] {"context", "one", "two"});

        for (String[] args : rejections) {
            assertUsageRejection("must be a usage error: " + String.join(" ", args), args, null);
        }
    }

    @Test
    void theEndOfOptionsMarkerPreservesOptionLikeQueryText() {
        Harness oddQuery = new Harness();
        Harness.RunResult odd = oddQuery.run("context", "--", "--weird -query");
        assertEquals(0, odd.exitCode(), () -> odd.describe());
        assertEquals("--weird -query", oddQuery.exchange.dispatches.getFirst().request().query());
    }

    @Test
    void missingStoredCredentialKeepsExit3WithOneRedactedLineAndNoDispatch() {
        Harness harness = new Harness();
        harness.stored.failure = CredentialResolutionException.missing("environment BRAVE_API_KEY");

        Harness.RunResult result = harness.run("context", "q");

        assertEquals(3, result.exitCode(), () -> result.describe());
        assertEquals(0, harness.exchange.dispatches.size(), "a run without a credential never dispatches");
        List<String> lines = result.stderr().lines().toList();
        assertEquals(1, lines.size(), () -> "exactly one diagnostic line: " + result.describe());
        assertTrue(lines.getFirst().startsWith("context:"), () -> result.describe());
        assertEquals("", result.stdout(), () -> result.describe());
    }

    @Test
    void loopbackBaseUrlsDrawTheirCredentialFromTheTestKeySeamInsteadOfTheStoredOne() {
        Harness missingKey = new Harness();
        missingKey.stored.credential = credential("stored-production-token");
        missingKey.loopback.credential = null;
        Harness.RunResult refused = missingKey.run("context", "--base-url", "http://127.0.0.1:9", "q");

        assertEquals(3, refused.exitCode(), "a loopback run without a test key keeps the configuration status");
        assertEquals(0, missingKey.exchange.dispatches.size());
        assertEquals(
                0, missingKey.stored.resolutions, "the stored production credential is never consulted for a loopback origin");
        assertTrue(refused.stderr().contains("BRAVE_SEARCH_TEST_KEY"), () -> refused.describe());

        Harness withKey = new Harness();
        withKey.stored.credential = credential("stored-production-token");
        withKey.loopback.credential = credential("loopback-test-key");
        Harness.RunResult dispatched = withKey.run("context", "--base-url", "http://127.0.0.1:9/", "q");

        assertEquals(0, dispatched.exitCode(), () -> dispatched.describe());
        ContextDispatch dispatch = withKey.exchange.dispatches.getFirst();
        assertFalse(dispatch.origin().credentialsAllowed(), "the override origin never receives stored credentials");
        assertEquals(1, withKey.loopback.resolutions);
        assertEquals(0, withKey.stored.resolutions);
    }

    @Test
    void eachUpstreamFailureKindReachesThePresenterAndKeepsItsExitStatus() {
        List<FailureKind> kinds = List.of(
                FailureKind.AUTHENTICATION, FailureKind.RATE_LIMITED, FailureKind.TRANSPORT, FailureKind.UPSTREAM, FailureKind.MALFORMED);
        List<Integer> expected = List.of(4, 5, 6, 7, 8);

        for (int index = 0; index < kinds.size(); index++) {
            Harness harness = new Harness();
            harness.exchange.outcome =
                    new Outcome.Failure<>(kinds.get(index), kinds.get(index) + " upstream explanation");
            final int expectedExit = expected.get(index);
            final String kind = String.valueOf(kinds.get(index));

            Harness.RunResult result = harness.run("context", "q");

            assertEquals(expectedExit, result.exitCode(), () -> kind + " keeps its exit status: " + result.describe());
            assertEquals(1, harness.presenter.outcomes.size(), "the failed exchange is rendered by the presenter");
            assertEquals("", result.stdout(), () -> result.describe());
        }
    }

    @Test
    void thePresenterExitStatusDecidesTheSuccessfulRun() {
        Harness harness = new Harness();
        harness.presenter.exitCode = 6;

        Harness.RunResult result = harness.run("context", "q");

        assertEquals(6, result.exitCode(), "a rendering failure keeps its own exit status");
    }

    private static void assertUsageRejection(String because, String[] args, String namedOption) {
        Harness harness = new Harness();
        Harness.RunResult result = harness.run(args);
        assertEquals(2, result.exitCode(), () -> because + ": " + result.describe());
        assertTrue(
                result.stderr().contains("Usage:")
                        || result.stderr().contains("Unknown option")
                        || (namedOption != null && result.stderr().contains(namedOption)),
                () -> because + ", stderr must explain itself: " + result.describe());
        assertEquals(0, harness.exchange.dispatches.size(), () -> because + ": no exchange may be dispatched");
        assertEquals("", result.stdout(), () -> because + ": usage errors never write stdout");
    }

    private static String fiftyOneWords() {
        return String.join(" ", java.util.Collections.nCopies(51, "word"));
    }

    private static Credential credential(String token) {
        return Credential.of(token.getBytes(UTF_8));
    }

    /** Root fixture shaped like the process root: shared globals plus the context subcommand. */
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
        final FakeCredentialProvider stored = new FakeCredentialProvider();
        final FakeCredentialProvider loopback = new FakeCredentialProvider();
        final java.io.ByteArrayOutputStream stdoutBytes = new java.io.ByteArrayOutputStream();
        final java.io.ByteArrayOutputStream stderrBytes = new java.io.ByteArrayOutputStream();
        ResultWriter writer = new OutputStreamResultWriter(stdoutBytes);

        Harness() {
            stored.credential = credential("stored-production-token");
            loopback.credential = credential("loopback-test-key");
        }

        RunResult run(String... args) {
            ContextCommand context = new ContextCommand(
                    globals,
                    new RemoteOptions(STUB_LOCALHOST),
                    new CommonSearchOptions(),
                    stored,
                    loopback,
                    exchange,
                    presenter,
                    new ExchangeRegistry(),
                    writer,
                    WriterDiagnosticsSink::new);
            Root root = new Root();
            root.globals = globals;
            CommandLine commandLine = new CommandLine(root);
            commandLine.addSubcommand("context", new CommandLine(context));
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

    private static final class RecordingExchange implements ContextExchange {
        final List<ContextDispatch> dispatches = new ArrayList<>();
        Outcome<ContextResult> outcome = new Outcome.Success<>(
                new ContextResult(200, new UpstreamPayload("{}".getBytes(UTF_8)), null, null, null, null));

        @Override
        public Outcome<ContextResult> dispatch(ContextDispatch invocation) {
            dispatches.add(invocation);
            return outcome;
        }
    }

    private static final class RecordingPresenter implements ContextPresenter {
        final List<Outcome<ContextResult>> outcomes = new ArrayList<>();
        final List<ContextRequest> requests = new ArrayList<>();
        final List<OutputRequest> outputs = new ArrayList<>();
        int exitCode;

        @Override
        public int present(
                Outcome<ContextResult> outcome,
                ContextRequest request,
                OutputRequest output,
                ResultWriter results,
                DiagnosticsSink diagnostics) {
            outcomes.add(outcome);
            requests.add(request);
            outputs.add(output);
            return switch (outcome) {
                case Outcome.Success<ContextResult> ignored -> exitCode;
                case Outcome.Failure<ContextResult> failure -> ExitCodeMapper.forKind(failure.kind());
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

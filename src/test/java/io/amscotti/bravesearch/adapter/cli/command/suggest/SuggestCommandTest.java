package io.amscotti.bravesearch.adapter.cli.command.suggest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.option.CommonSearchOptions;
import io.amscotti.bravesearch.adapter.cli.option.GlobalOptions;
import io.amscotti.bravesearch.adapter.cli.option.RemoteOptions;
import io.amscotti.bravesearch.adapter.cli.option.StrictOptionParsing;
import io.amscotti.bravesearch.adapter.cli.presentation.OutputStreamResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.SuggestPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.WriterDiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.suggest.SuggestPresenterImpl;
import io.amscotti.bravesearch.adapter.cli.presentation.suggest.SuggestProjectionExtractor;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.LocalhostResolver;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.CredentialResolutionException;
import io.amscotti.bravesearch.application.port.out.ResolvedCredential;
import io.amscotti.bravesearch.application.port.out.SuggestDispatch;
import io.amscotti.bravesearch.application.port.out.SuggestExchange;
import io.amscotti.bravesearch.bootstrap.ExchangeRegistry;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.request.SuggestRequest;
import io.amscotti.bravesearch.domain.result.SuggestSearchResult;
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
 * Grammar and dispatch contracts of the suggest command: the shared {@code --search-lang}
 * spelling is this endpoint's accepted alias for its language option and binds onto the
 * request's own {@code lang} member — {@code --lang} itself is not a spelling this CLI
 * offers anywhere — the suggest-only {@code --count} bound and the tri-state {@code
 * --rich} reach the dispatched request exactly once, every local usage rejection — the
 * count bound of 20 and the shared spellings the suggest endpoint leaves undocumented —
 * fails with exit 2 before the exchange is touched, and the endpoint-external spellings
 * (Goggles, the page-walk family, SafeSearch, freshness) are unknown or refused here,
 * because the suggest endpoint is one single non-paginated request.
 */
final class SuggestCommandTest {

    private static final LocalhostResolver STUB_LOCALHOST = host -> List.of(InetAddress.getByName("127.0.0.1"));

    @Test
    void dispatchesTheFullyMappedRequestWithTheLanguageAliasOnTheLangMember() {
        Harness harness = new Harness();

        harness.run(
                "suggest",
                "--country",
                "ALL",
                "--search-lang",
                "de",
                "--count",
                "20",
                "--rich",
                "three word query");

        SuggestDispatch dispatched = harness.exchange.dispatches.getFirst();
        SuggestRequest request = dispatched.request();
        assertEquals("three word query", request.query());
        assertEquals("ALL", request.country());
        assertEquals("de", request.lang(), "the shared alias binds onto the suggest language member");
        assertEquals(20, request.count());
        assertEquals(Boolean.TRUE, request.rich());
        assertEquals(BraveApiOrigin.production(), dispatched.origin());
    }

    @Test
    void dispatchesTheBareQueryWithEveryOptionalOmitted() {
        Harness harness = new Harness();

        harness.run("suggest", "café query");

        SuggestRequest request = harness.exchange.dispatches.getFirst().request();
        assertEquals("café query", request.query());
        assertNull(request.country());
        assertNull(request.lang());
        assertNull(request.count());
        assertNull(request.rich());
    }

    @Test
    void theNegatableRichPairBindsFalseAndUnsuppliedStaysNull() {
        Harness harness = new Harness();

        harness.run("suggest", "--no-rich", "q");

        assertEquals(Boolean.FALSE, harness.exchange.dispatches.getFirst().request().rich());
    }

    @Test
    void countBeyondTheDocumentedSuggestBoundIsRejectedBeforeAnyDispatch() {
        Harness harness = new Harness();

        Harness.RunResult result = harness.run("suggest", "--count", "21", "q");

        assertEquals(2, result.exitCode(), () -> result.describe());
        assertTrue(result.stderr().contains("Usage:"), () -> result.describe());
        assertEquals(0, harness.exchange.dispatches.size(), "an invalid request must never be dispatched");
    }

    @Test
    void theBareLangSpellingIsAnUnknownOptionBecauseOnlyTheAliasExists() {
        Harness harness = new Harness();

        Harness.RunResult result = harness.run("suggest", "--lang", "de", "q");

        assertEquals(2, result.exitCode(), () -> result.describe());
        assertTrue(result.stderr().contains("Unknown option"), () -> result.describe());
        assertEquals(0, harness.exchange.dispatches.size());
    }

    @Test
    void undocumentedSharedSpellingsAreRejectedHere() {
        Harness harness = new Harness();

        for (String undocumented : new String[] {"--ui-lang", "--freshness", "--safe-search", "--page",
            "--spellcheck"}) {
            Harness.RunResult result = harness.run("suggest", undocumented, "en", "q");
            assertEquals(2, result.exitCode(), () -> "the spelling must be refused: " + result.describe());
            assertTrue(result.stderr().contains("is not accepted by this command"), () -> result.describe());
        }
        assertEquals(0, harness.exchange.dispatches.size());
    }

    @Test
    void goggleAndWebOnlySpellingsAreUnknownOptionsOfTheSuggestCommand() {
        Harness harness = new Harness();

        for (String spelling : new String[] {"--goggle", "--goggle-file", "--include-site", "--exclude-site",
            "--all-pages", "--max-pages", "--operators", "--text-decorations"}) {
            Harness.RunResult result = harness.run("suggest", spelling, "a.example", "q");
            assertEquals(2, result.exitCode(), () -> "the spelling must be unknown: " + result.describe());
            assertTrue(result.stderr().contains("Unknown option"), () -> result.describe());
        }
        assertEquals(0, harness.exchange.dispatches.size());
    }

    @Test
    void rawOutputIsAcceptedForTheSingleRequestSuggestCommand() {
        Harness harness = new Harness();

        Harness.RunResult result = harness.run("suggest", "--output", "raw", "q");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(1, harness.exchange.dispatches.size());
    }

    @Test
    void aFailingExchangeHandsTheFailureKindToThePresenterStatus() {
        Harness harness = new Harness();
        harness.exchange.outcome =
                new Outcome.Failure<>(FailureKind.AUTHENTICATION, "upstream exchange failed with status 401");

        Harness.RunResult result = harness.run("suggest", "q");

        assertEquals(4, result.exitCode(), () -> result.describe());
    }

    /** Root fixture shaped like the process root: shared globals plus the suggest subcommand. */
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
            SuggestPresenter presenter = new SuggestPresenterImpl(
                    new EnvelopeCodec(mappers),
                    jsonl,
                    new io.amscotti.bravesearch.adapter.cli.presentation.json.RawCodec(),
                    new SuggestProjectionExtractor(mappers),
                    (mode, diagnostics, results, quiet) ->
                            new io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings(
                                    mode, SuggestPresenter.COMMAND, diagnostics, results, jsonl, quiet));
            SuggestCommand suggest = new SuggestCommand(
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
            commandLine.addSubcommand("suggest", new CommandLine(suggest));
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

    private static final class RecordingExchange implements SuggestExchange {
        final List<SuggestDispatch> dispatches = new ArrayList<>();
        Outcome<SuggestSearchResult> outcome = new Outcome.Success<>(
                new SuggestSearchResult(200, new UpstreamPayload("{}".getBytes(UTF_8)), null, null, null, null));

        @Override
        public Outcome<SuggestSearchResult> dispatch(SuggestDispatch invocation) {
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

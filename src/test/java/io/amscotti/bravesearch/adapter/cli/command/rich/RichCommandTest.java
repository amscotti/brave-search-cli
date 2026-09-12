package io.amscotti.bravesearch.adapter.cli.command.rich;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.option.GlobalOptions;
import io.amscotti.bravesearch.adapter.cli.option.RemoteOptions;
import io.amscotti.bravesearch.adapter.cli.option.StrictOptionParsing;
import io.amscotti.bravesearch.adapter.cli.presentation.OutputStreamResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.RichPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.WriterDiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.rich.RichPresenterImpl;
import io.amscotti.bravesearch.adapter.cli.presentation.rich.RichProjectionExtractor;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.LocalhostResolver;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.CredentialResolutionException;
import io.amscotti.bravesearch.application.port.out.ResolvedCredential;
import io.amscotti.bravesearch.application.port.out.RichDispatch;
import io.amscotti.bravesearch.application.port.out.RichExchange;
import io.amscotti.bravesearch.bootstrap.ExchangeRegistry;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.request.RichRequest;
import io.amscotti.bravesearch.domain.result.RichResult;
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
 * Grammar and dispatch contracts of the rich command: the one positional is an opaque
 * callback key that travels verbatim — {@code --} hands over keys that start like an
 * option — a blank or missing key is a usage failure before any dispatch, the endpoint
 * documents no search options so every shared spelling is an unknown option here, and
 * the single non-paginated request keeps the raw channel valid.
 */
final class RichCommandTest {

    private static final LocalhostResolver STUB_LOCALHOST = host -> List.of(InetAddress.getByName("127.0.0.1"));

    @Test
    void dispatchesTheOpaqueKeyVerbatimAsTheOnlyRequestMember() {
        Harness harness = new Harness();

        harness.run("rich", "cb/3f9d2A==&q=1");

        RichRequest request = harness.exchange.dispatches.getFirst().request();
        assertEquals("cb/3f9d2A==&q=1", request.callbackKey());
        assertEquals(BraveApiOrigin.production(), harness.exchange.dispatches.getFirst().origin());
        assertEquals(
                credential("stored-production-token").toString(),
                harness.exchange.dispatches.getFirst().credential().toString(),
                "the production origin resolves the stored credential exactly once for the exchange");
    }

    @Test
    void theDoubleDashHandsOverOptionLikeKeysVerbatim() {
        Harness harness = new Harness();

        harness.run("rich", "--", "-opaque-key");

        assertEquals("-opaque-key", harness.exchange.dispatches.getFirst().request().callbackKey());
    }

    @Test
    void aBlankKeyIsRejectedBeforeAnyDispatch() {
        Harness harness = new Harness();

        Harness.RunResult result = harness.run("rich", "  ");

        assertEquals(2, result.exitCode(), () -> result.describe());
        assertTrue(result.stderr().contains("callback key must not be blank"), () -> result.describe());
        assertEquals(0, harness.exchange.dispatches.size(), "an invalid request must never be dispatched");
    }

    @Test
    void theMissingKeyAndExtraPositionalsAreUsageFailures() {
        Harness harness = new Harness();

        Harness.RunResult missing = harness.run("rich");
        assertEquals(2, missing.exitCode(), () -> missing.describe());
        assertTrue(missing.stderr().contains("Usage:"), () -> missing.describe());

        Harness.RunResult extra = harness.run("rich", "cb-1", "cb-2");
        assertEquals(2, extra.exitCode(), () -> extra.describe());
        assertTrue(extra.stderr().contains("Usage:"), () -> extra.describe());
        assertEquals(0, harness.exchange.dispatches.size());
    }

    @Test
    void everySearchOptionSpellingIsAnUnknownOptionOfTheRichCommand() {
        Harness harness = new Harness();

        for (String spelling : new String[] {"--country", "--search-lang", "--count", "--ui-lang", "--safe-search",
            "--freshness", "--page", "--spellcheck", "--text-decorations", "--operators", "--enable-rich-callback",
            "--goggle", "--all-pages"}) {
            Harness.RunResult result = harness.run("rich", spelling, "value", "cb-7f3a2b");
            assertEquals(2, result.exitCode(), () -> "the spelling must be unknown: " + result.describe());
            assertTrue(result.stderr().contains("Unknown option"), () -> result.describe());
        }
        assertEquals(0, harness.exchange.dispatches.size());
    }

    @Test
    void rawOutputIsAcceptedForTheSingleRequestRichCommand() {
        Harness harness = new Harness();

        Harness.RunResult result = harness.run("rich", "--output", "raw", "cb-7f3a2b");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(1, harness.exchange.dispatches.size());
    }

    @Test
    void aFailingExchangeHandsTheFailureKindToThePresenterStatus() {
        Harness harness = new Harness();
        harness.exchange.outcome =
                new Outcome.Failure<>(FailureKind.AUTHENTICATION, "upstream exchange failed with status 401");

        Harness.RunResult result = harness.run("rich", "cb-7f3a2b");

        assertEquals(4, result.exitCode(), () -> result.describe());
    }

    /** Root fixture shaped like the process root: shared globals plus the rich subcommand. */
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
            RichPresenter presenter = new RichPresenterImpl(
                    new EnvelopeCodec(mappers),
                    jsonl,
                    new io.amscotti.bravesearch.adapter.cli.presentation.json.RawCodec(),
                    new RichProjectionExtractor(mappers),
                    (mode, diagnostics, results, quiet) ->
                            new io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings(
                                    mode, RichPresenter.COMMAND, diagnostics, results, jsonl, quiet));
            RichCommand rich = new RichCommand(
                    globals,
                    new RemoteOptions(STUB_LOCALHOST),
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
            commandLine.addSubcommand("rich", new CommandLine(rich));
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

    private static final class RecordingExchange implements RichExchange {
        final List<RichDispatch> dispatches = new ArrayList<>();
        Outcome<RichResult> outcome =
                new Outcome.Success<>(new RichResult(200, new UpstreamPayload("{}".getBytes(UTF_8)), null, null, null, null));

        @Override
        public Outcome<RichResult> dispatch(RichDispatch invocation) {
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

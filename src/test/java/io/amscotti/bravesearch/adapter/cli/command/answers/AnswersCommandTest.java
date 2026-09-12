package io.amscotti.bravesearch.adapter.cli.command.answers;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.option.CommonSearchOptions;
import io.amscotti.bravesearch.adapter.cli.option.GlobalOptions;
import io.amscotti.bravesearch.adapter.cli.option.RemoteOptions;
import io.amscotti.bravesearch.adapter.cli.option.StrictOptionParsing;
import io.amscotti.bravesearch.adapter.cli.presentation.AnswersPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.OutputStreamResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.WriterDiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.answers.AnswersPresenterImpl;
import io.amscotti.bravesearch.adapter.cli.presentation.answers.AnswersProjectionExtractor;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.RawCodec;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.LocalhostResolver;
import io.amscotti.bravesearch.application.port.out.AnswersDispatch;
import io.amscotti.bravesearch.application.port.out.AnswersExchange;
import io.amscotti.bravesearch.application.port.out.AnswersStreamDispatch;
import io.amscotti.bravesearch.application.port.out.AnswersStreamExchange;
import io.amscotti.bravesearch.application.port.out.AnswersStreamPort;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.CredentialResolutionException;
import io.amscotti.bravesearch.application.port.out.ResolvedCredential;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.CancellationRegistry;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.request.AnswersRequest;
import io.amscotti.bravesearch.domain.request.SafeSearch;
import io.amscotti.bravesearch.domain.result.AnswersResult;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Grammar and dispatch contracts of the answers command: the full option grammar binds
 * onto the request exactly once, streaming is the default and {@code --no-stream} the
 * only explicit blocking switch, every local usage rejection — each research member
 * without the research flag, research and enabled citations and entities on a blocking
 * request, the JSONL channel on a blocking request, the research bounds, and the shared
 * spellings the endpoint leaves undocumented — fails with exit 2 before the exchange is
 * touched, and a streaming invocation (default or explicit) today ends in the typed local
 * not-yet-implemented failure with the internal status and zero dispatch.
 */
final class AnswersCommandTest {

    private static final LocalhostResolver STUB_LOCALHOST = host -> List.of(InetAddress.getByName("127.0.0.1"));

    @Test
    void theFullOptionGrammarBindsOntoTheRequestExactlyOnce() {
        Harness harness = new Harness();

        harness.run(
                "answers",
                "--no-stream",
                "--model",
                "future-model-x",
                "--max-completion-tokens",
                "1024",
                "--seed",
                "42",
                "--country",
                "US",
                "--language",
                "en",
                "--safe-search",
                "strict",
                "--idle-timeout",
                "90s",
                "--stream-timeout",
                "330s",
                "what is the brave search api");

        AnswersRequest request = harness.exchange.dispatches.getFirst().request();
        assertEquals("what is the brave search api", request.question());
        assertEquals("future-model-x", request.model());
        assertFalse(request.stream());
        assertEquals(1024, request.maxCompletionTokens());
        assertEquals(42, request.seed());
        assertEquals("US", request.country());
        assertEquals("en", request.language());
        assertEquals(SafeSearch.STRICT, request.safeSearch());
        assertEquals(Duration.ofSeconds(90), request.idleTimeout());
        assertEquals(Duration.ofSeconds(330), request.streamTimeout());
        assertNull(request.citations());
        assertNull(request.entities());
        assertNull(request.research());
        assertEquals(BraveApiOrigin.production(), harness.exchange.dispatches.getFirst().origin());
    }

    @Test
    void theResearchFamilyDispatchesThroughTheStreamingPath() {
        Harness harness = new Harness();
        harness.streamingExit = 0;

        String[] family = {
            "answers",
            "--research",
            "--research-thinking",
            "--research-tokens-per-query",
            "4096",
            "--research-queries",
            "10",
            "--research-iterations",
            "3",
            "--research-seconds",
            "60",
            "--research-results-per-query",
            "30",
            "q",
        };
        Harness.RunResult result = harness.run(family);

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(1, harness.streams.dispatches.size(), "a research run is one streaming dispatch");
        assertEquals(0, harness.exchange.dispatches.size(), "the blocking exchange stays untouched");
        assertTrue(harness.streams.dispatches.getFirst().request().research(), "research travels on the stream request");
        Harness.RunResult outOfBounds = harness.run(
                "answers", "--research", "--research-tokens-per-query", "16385", "q");
        assertEquals(2, outOfBounds.exitCode(), "the bound still rejects before dispatch");
    }

    @Test
    void streamingIsTheDefaultAndExplicitStreamStaysStreaming() {
        Harness harness = new Harness();
        harness.streamingExit = 0;

        harness.run("answers", "q");
        assertEquals(1, harness.streams.dispatches.size(), "the default run opens one streaming exchange");
        assertTrue(harness.exchange.dispatches.isEmpty(), "the blocking exchange stays untouched");

        harness.run("answers", "--stream", "q");
        assertEquals(2, harness.streams.dispatches.size(), "the explicit flag is the same streaming path");
        assertTrue(harness.exchange.dispatches.isEmpty());
    }

    @Test
    void everyResearchMemberWithoutTheResearchFlagIsRejectedBeforeAnyDispatch() {
        Harness harness = new Harness();

        String[][] loneMembers = {
            {"--research-thinking"},
            {"--research-tokens-per-query", "4096"},
            {"--research-queries", "10"},
            {"--research-iterations", "3"},
            {"--research-seconds", "60"},
            {"--research-results-per-query", "30"},
        };
        for (String[] member : loneMembers) {
            String[] invocation = new String[member.length + 2];
            invocation[0] = "answers";
            System.arraycopy(member, 0, invocation, 1, member.length);
            invocation[member.length + 1] = "q";
            Harness.RunResult result = harness.run(invocation);
            assertEquals(2, result.exitCode(), () -> "the lone member must be refused: " + result.describe());
            assertTrue(
                    result.stderr().contains("requires --research"), () -> result.describe());
        }
        assertEquals(0, harness.exchange.dispatches.size());
    }

    @Test
    void researchAndEnabledCitationsAndEntitiesAreRejectedOnBlockingRequests() {
        Harness harness = new Harness();

        assertUsageRefused(harness, "answers", "--no-stream", "--research", "q");
        assertUsageRefused(harness, "answers", "--no-stream", "--citations", "q");
        assertUsageRefused(harness, "answers", "--no-stream", "--entities", "q");
        assertUsageRefused(harness, "answers", "--no-stream", "--research", "--research-thinking", "q");
        assertEquals(0, harness.exchange.dispatches.size());
    }

    @Test
    void theJsonlChannelIsIncompatibleWithBlockingAnswers() {
        Harness harness = new Harness();

        assertUsageRefused(harness, "answers", "--output", "jsonl", "--no-stream", "q");
        assertEquals(0, harness.exchange.dispatches.size());

        // the jsonl channel belongs to the streaming family and dispatches through it
        harness.streamingExit = 0;
        Harness.RunResult streaming = harness.run("answers", "--output", "jsonl", "q");
        assertEquals(0, streaming.exitCode(), () -> streaming.describe());
        assertEquals(1, harness.streams.dispatches.size());
        assertTrue(harness.streamingPresenterInvoked, "the jsonl stream renders through the streaming presenter");
        assertEquals(0, harness.exchange.dispatches.size());
    }

    @Test
    void theResearchBoundsAreRejectedBeforeAnyDispatch() {
        Harness harness = new Harness();

        assertUsageRefused(harness, "answers", "--research", "--research-tokens-per-query", "1023", "q");
        assertUsageRefused(harness, "answers", "--research", "--research-tokens-per-query", "16385", "q");
        assertUsageRefused(harness, "answers", "--research", "--research-queries", "0", "q");
        assertUsageRefused(harness, "answers", "--research", "--research-iterations", "6", "q");
        assertUsageRefused(harness, "answers", "--research", "--research-seconds", "301", "q");
        assertUsageRefused(harness, "answers", "--research", "--research-results-per-query", "61", "q");
        assertEquals(0, harness.exchange.dispatches.size());
    }

    @Test
    void undocumentedSharedSpellingsAreRejectedHere() {
        Harness harness = new Harness();

        for (String undocumented :
                new String[] {"--search-lang", "--ui-lang", "--freshness", "--count", "--page", "--spellcheck"}) {
            Harness.RunResult result = harness.run("answers", undocumented, "en", "q");
            assertEquals(2, result.exitCode(), () -> "the spelling must be refused: " + result.describe());
            assertTrue(result.stderr().contains("is not accepted by this command"), () -> result.describe());
        }
        assertEquals(0, harness.exchange.dispatches.size());
    }

    @Test
    void aStreamingOpenFailureRendersThroughTheSharedFailurePresenter() {
        Harness harness = new Harness();
        harness.streams.outcome = new Outcome.Failure<>(
                FailureKind.AUTHENTICATION, "upstream stream exchange failed with status 401");

        Harness.RunResult human = harness.run("answers", "q");

        assertEquals(4, human.exitCode(), () -> human.describe());
        assertFalse(harness.streamingPresenterInvoked, "an exchange that never opened has nothing to render");
        assertEquals(1, harness.streams.dispatches.size());
        List<String> diagnostics = human.stderr().lines().toList();
        assertEquals(1, diagnostics.size(), () -> "exactly one diagnostic line: " + human.describe());
        assertTrue(diagnostics.getFirst().startsWith("answers: "), () -> human.describe());

        Harness.RunResult json = harness.run("answers", "--output", "json", "q");
        assertEquals(4, json.exitCode(), () -> json.describe());
        assertTrue(json.stdout().contains("\"ok\":false"), "json mode renders its failure envelope: " + json.describe());
    }

    @Test
    void aSignalLatchedDuringTheConnectWindowDecidesAnOpenFailureExit() {
        Harness harness = new Harness();
        List<CancellationContext> liveContexts = new java.util.concurrent.CopyOnWriteArrayList<>();
        harness.cancellations =
                context -> {
                    liveContexts.add(context);
                    return () -> liveContexts.remove(context);
                };
        harness.streams.outcome =
                new Outcome.Failure<>(FailureKind.TRANSPORT, "upstream stream exchange failed to connect");
        harness.streams.onDispatch =
                () -> liveContexts.forEach(context -> context.latch(CancellationContext.Cause.SIGINT));

        Harness.RunResult interrupted = harness.run("answers", "q");
        assertEquals(
                130,
                interrupted.exitCode(),
                () -> "an interrupt that won the latch during the connect window owns the status: "
                        + interrupted.describe());
        assertEquals(
                1,
                interrupted.stderr().lines().count(),
                () -> "the open failure still explains itself once: " + interrupted.describe());

        harness.streams.onDispatch =
                () -> liveContexts.forEach(context -> context.latch(CancellationContext.Cause.SIGTERM));
        Harness.RunResult terminated = harness.run("answers", "q");
        assertEquals(
                143,
                terminated.exitCode(),
                () -> "a termination that won the latch during the connect window owns the status: "
                        + terminated.describe());
    }

    @Test
    void theStreamingPresenterOwnsTheOpenExchangeAndTheCloseFollowsItsRender() {
        Harness harness = new Harness();
        harness.streamingExit = 0;

        Harness.RunResult result = harness.run("answers", "q");

        assertEquals(0, result.exitCode(), () -> "the streaming render owns the exit status: " + result.describe());
        assertTrue(harness.streamingPresenterInvoked, "the open exchange reached the streaming presenter");
        assertTrue(
                harness.streams.scriptedExchange().closed,
                "the command closes the exchange after the render");
    }

    @Test
    void aStreamingDispatchCarriesTheConnectionBudgetAndNeverTheBlockingTotalDeadline() {
        Harness harness = new Harness();
        harness.streamingExit = 0;

        harness.run("answers", "--timeout", "5s", "--connect-timeout", "2s", "q");

        AnswersStreamDispatch dispatch = harness.streams.dispatches.getFirst();
        assertEquals(Duration.ofSeconds(2), dispatch.connectTimeout());
        assertTrue(dispatch.request().stream());
        assertSame(harness.stored.credential, dispatch.credential(), "the stored production key travels once, resolved");
        assertEquals(BraveApiOrigin.production(), dispatch.origin());
        assertNull(dispatch.pinnedApiVersion());
        assertEquals(0, harness.exchange.dispatches.size(), "the blocking deadline never opens a streaming exchange");
    }

    @Test
    void rawOutputIsAcceptedForTheSingleBlockingRequest() {
        Harness harness = new Harness();

        Harness.RunResult result = harness.run("answers", "--output", "raw", "--no-stream", "q");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(1, harness.exchange.dispatches.size());
    }

    @Test
    void aFailingExchangeHandsTheFailureKindToThePresenterStatus() {
        Harness harness = new Harness();
        harness.exchange.outcome =
                new Outcome.Failure<>(FailureKind.AUTHENTICATION, "upstream exchange failed with status 401");

        Harness.RunResult result = harness.run("answers", "--no-stream", "q");

        assertEquals(4, result.exitCode(), () -> result.describe());
    }

    private static void assertUsageRefused(Harness harness, String... invocation) {
        Harness.RunResult result = harness.run(invocation);
        assertEquals(2, result.exitCode(), () -> result.describe());
        assertTrue(result.stderr().contains("Usage:") || result.stderr().contains("requires"), () -> result.describe());
    }

    /** Root fixture shaped like the process root: shared globals plus the answers subcommand. */
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
        final RecordingStreamPort streams = new RecordingStreamPort();
        final FakeCredentialProvider stored = new FakeCredentialProvider();
        final FakeCredentialProvider loopback = new FakeCredentialProvider();
        final java.io.ByteArrayOutputStream stdoutBytes = new java.io.ByteArrayOutputStream();
        final java.io.ByteArrayOutputStream stderrBytes = new java.io.ByteArrayOutputStream();
        CancellationRegistry cancellations = context -> () -> {};
        int streamingExit = 7;
        boolean streamingPresenterInvoked;
        boolean streamingExchangeClosed;

        Harness() {
            stored.credential = credential("stored-production-token");
            loopback.credential = credential("loopback-test-key");
        }

        RunResult run(String... args) {
            JsonMappers mappers = new JsonMappers();
            JsonlCodec jsonl = new JsonlCodec(mappers);
            AnswersPresenter presenter = new AnswersPresenterImpl(
                    new EnvelopeCodec(mappers),
                    jsonl,
                    new RawCodec(),
                    new AnswersProjectionExtractor(mappers),
                    (mode, diagnostics, results, quiet) ->
                            new io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings(
                                    mode, AnswersPresenter.COMMAND, diagnostics, results, jsonl, quiet));
            AnswersCommand answers = new AnswersCommand(
                    globals,
                    new RemoteOptions(STUB_LOCALHOST),
                    new CommonSearchOptions(),
                    stored,
                    loopback,
                    exchange,
                    streams,
                    cancellations,
                    presenter,
                    (open, request, output, results, diagnostics) -> {
                        streamingPresenterInvoked = true;
                        open.cancellation();
                        return streamingExit;
                    },
                    new OutputStreamResultWriter(stdoutBytes),
                    WriterDiagnosticsSink::new);
            Root root = new Root();
            root.globals = globals;
            CommandLine commandLine = new CommandLine(root);
            commandLine.addSubcommand("answers", new CommandLine(answers));
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

    /** Stream-port double: records dispatches and hands back the scripted outcome. */
    private static final class RecordingStreamPort implements AnswersStreamPort {
        final List<AnswersStreamDispatch> dispatches = new ArrayList<>();
        Outcome<AnswersStreamExchange> outcome =
                new Outcome.Success<>(new ScriptedExchange());
        volatile Runnable onDispatch = () -> {};

        @Override
        public Outcome<AnswersStreamExchange> stream(AnswersStreamDispatch invocation) {
            dispatches.add(invocation);
            onDispatch.run();
            return outcome;
        }

        ScriptedExchange scriptedExchange() {
            return (ScriptedExchange) ((Outcome.Success<AnswersStreamExchange>) outcome).value();
        }
    }

    /** Exchange double: a live latch and a witnessed close are all the command touches. */
    private static final class ScriptedExchange implements AnswersStreamExchange {
        final CancellationContext cancellation = new CancellationContext();
        boolean closed;

        @Override
        public io.amscotti.bravesearch.domain.metadata.RequestMeta openMeta() {
            return new io.amscotti.bravesearch.domain.metadata.RequestMeta(null, 200, null, java.util.List.of(), null);
        }

        @Override
        public java.util.concurrent.Flow.Publisher<byte[]> decodedRawFrames() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.concurrent.Flow.Publisher<io.amscotti.bravesearch.domain.answer.AnswerStreamEvent>
                semanticFrames() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CancellationContext cancellation() {
            return cancellation;
        }

        @Override
        public java.time.Instant lastTransportActivity() {
            return java.time.Instant.EPOCH;
        }

        @Override
        public java.time.Instant lastSemanticProgress() {
            return null;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class RecordingExchange implements AnswersExchange {
        final List<AnswersDispatch> dispatches = new ArrayList<>();
        Outcome<AnswersResult> outcome = new Outcome.Success<>(
                new AnswersResult(200, new UpstreamPayload("{}".getBytes(UTF_8)), null, null, null, null));

        @Override
        public Outcome<AnswersResult> dispatch(AnswersDispatch invocation) {
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

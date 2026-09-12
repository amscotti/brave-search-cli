package io.amscotti.bravesearch.adapter.cli.command.places;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.option.GlobalOptions;
import io.amscotti.bravesearch.adapter.cli.option.RemoteOptions;
import io.amscotti.bravesearch.adapter.cli.option.StrictOptionParsing;
import io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings;
import io.amscotti.bravesearch.adapter.cli.presentation.OutputStreamResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.PlaceDescribePresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.PlaceDetailsPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.WriterDiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.places.PlaceDescribePresenterImpl;
import io.amscotti.bravesearch.adapter.cli.presentation.places.PlaceDescriptionsExtractor;
import io.amscotti.bravesearch.adapter.cli.presentation.places.PlaceDetailsExtractor;
import io.amscotti.bravesearch.adapter.cli.presentation.places.PlaceDetailsPresenterImpl;
import io.amscotti.bravesearch.application.exchange.LocalhostResolver;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.CredentialResolutionException;
import io.amscotti.bravesearch.application.port.out.PlaceEnrichmentDispatch;
import io.amscotti.bravesearch.application.port.out.PlaceEnrichmentExchange;
import io.amscotti.bravesearch.application.port.out.ResolvedCredential;
import io.amscotti.bravesearch.application.service.PaginationService;
import io.amscotti.bravesearch.application.service.SlicedRateWaiter;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.bootstrap.ExchangeRegistry;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.request.PlaceEnrichmentRequest;
import io.amscotti.bravesearch.domain.result.PlaceEnrichmentResult;
import java.io.PrintWriter;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * The command grammar and fan-out policy of the two place enrichment commands: the ids
 * are the whole grammar — every search spelling is unknown — the invocation cap rejects
 * the two-hundred-first id before any dispatch, raw output is refused before any
 * dispatch because an invocation fans out, and a multi-chunk invocation dispatches its
 * consecutive chunk slices strictly in input order, duplicates preserved.
 */
final class PlacesEnrichmentCommandTest {

    private static final LocalhostResolver STUB_LOCALHOST = host -> List.of(java.net.InetAddress.getByName("127.0.0.1"));

    @Test
    void fortyFiveIdsDispatchAsThreeConsecutiveChunkSlicesInInputOrder() {
        Harness harness = new Harness();
        List<String> ids = IntStream.rangeClosed(1, 45).mapToObj(number -> "poi-" + number).toList();

        Harness.RunResult result = harness.runDetails(ids.toArray(String[]::new));

        assertEquals(0, result.exitCode(), () -> result.describe());
        List<PlaceEnrichmentDispatch> dispatches = harness.exchange.dispatches;
        assertEquals(3, dispatches.size(), "45 ids fan out into three chunk requests");
        assertEquals(ids.subList(0, 20), dispatches.get(0).request().ids());
        assertEquals(ids.subList(20, 40), dispatches.get(1).request().ids());
        assertEquals(ids.subList(40, 45), dispatches.get(2).request().ids());
        assertEquals(PlaceEnrichmentRequest.Kind.DETAILS, dispatches.getFirst().request().kind());
    }

    @Test
    void describeDispatchesItsOwnKind() {
        Harness harness = new Harness();

        Harness.RunResult result = harness.runDescribe("poi-a");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(PlaceEnrichmentRequest.Kind.DESCRIPTIONS, harness.exchange.dispatches.getFirst().request().kind());
    }

    @Test
    void duplicateIdsWalkTheChunksTheirInputPositionsEarn() {
        Harness harness = new Harness();
        List<String> ids = new ArrayList<>(java.util.Collections.nCopies(21, "same"));

        Harness.RunResult result = harness.runDetails(ids.toArray(String[]::new));

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(2, harness.exchange.dispatches.size());
        assertEquals(java.util.Collections.nCopies(20, "same"), harness.exchange.dispatches.get(0).request().ids());
        assertEquals(List.of("same"), harness.exchange.dispatches.get(1).request().ids());
    }

    @Test
    void theTwoHundredthIdIsAcceptedAndTheTwoHundredAndFirstRejectedBeforeAnyDispatch() {
        Harness harness = new Harness();
        String[] twoHundred = IntStream.rangeClosed(1, 200).mapToObj(number -> "id-" + number).toArray(String[]::new);

        assertEquals(0, harness.runDetails(twoHundred).exitCode());
        assertEquals(10, harness.exchange.dispatches.size(), "the cap composes exactly ten chunks");

        String[] twoHundredOne =
                IntStream.rangeClosed(1, 201).mapToObj(number -> "id-" + number).toArray(String[]::new);
        int dispatchesBefore = harness.exchange.dispatches.size();
        Harness.RunResult rejected = harness.runDetails(twoHundredOne);

        assertEquals(2, rejected.exitCode(), () -> "the cap is a usage rule: " + rejected.describe());
        assertTrue(rejected.stderr().contains("at most 200 ids"), rejected.describe());
        assertEquals(dispatchesBefore, harness.exchange.dispatches.size(), "a rejected cap never dispatches");
    }

    @Test
    void describeInvocationCapIsRejectedBeforeAnyDispatch() {
        Harness harness = new Harness();
        String[] twoHundredOne =
                IntStream.rangeClosed(1, 201).mapToObj(number -> "id-" + number).toArray(String[]::new);

        Harness.RunResult rejected = harness.runDescribe(twoHundredOne);

        assertEquals(2, rejected.exitCode(), () -> "the cap is a usage rule of both commands: " + rejected.describe());
        assertTrue(rejected.stderr().contains("at most 200 ids"), rejected.describe());
        assertEquals(0, harness.exchange.dispatches.size(), "a rejected cap never dispatches");
    }

    @Test
    void zeroIdsAreRejectedAsUsageByTheArityRule() {
        Harness harness = new Harness();

        Harness.RunResult result = harness.runDetails();

        assertEquals(2, result.exitCode(), () -> result.describe());
        assertTrue(result.stderr().contains("Missing required parameter"), result.describe());
    }

    @Test
    void rawOutputIsRejectedBeforeAnyDispatchForBothCommands() {
        Harness details = new Harness();
        Harness.RunResult rawDetails = details.runDetails("--output", "raw", "poi-a");
        assertEquals(2, rawDetails.exitCode(), () -> rawDetails.describe());
        assertTrue(rawDetails.stderr().contains("raw is not accepted by this command"), rawDetails.describe());
        assertEquals(0, details.exchange.dispatches.size(), "raw is refused before anything is dispatched");

        Harness describe = new Harness();
        Harness.RunResult rawDescribe = describe.runDescribe("--output", "raw", "poi-a");
        assertEquals(2, rawDescribe.exitCode(), () -> rawDescribe.describe());
        assertEquals(0, describe.exchange.dispatches.size());
    }

    @Test
    void everySharedSearchSpellingIsAnUnknownOption() {
        assertUnknownOption("--count", "5");
        assertUnknownOption("--country", "US");
        assertUnknownOption("--freshness", "pd");
        assertUnknownOption("--page", "2");
        assertUnknownOption("--all-pages");
        assertUnknownOption("--goggle", "https://example.com/g");
        assertUnknownOption("--location", "Philadelphia");
        assertUnknownOption("--latitude", "40.69");
    }

    @Test
    void aDoubleDashLetsAnIdStartLikeAnOption() {
        Harness harness = new Harness();

        Harness.RunResult result = harness.runDetails("--", "--looks-like-an-option");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(List.of("--looks-like-an-option"), harness.exchange.dispatches.getFirst().request().ids());
    }

    @Test
    void aFailingChunkHandsTheFailureKindToThePresenterStatus() {
        Harness harness = new Harness();
        harness.exchange.outcome = new Outcome.Failure<>(
                io.amscotti.bravesearch.domain.error.FailureKind.AUTHENTICATION,
                "upstream exchange failed with status 401");

        Harness.RunResult result = harness.runDetails("poi-a");

        assertEquals(4, result.exitCode(), () -> result.describe());
    }

    @Test
    void anInterruptLatchedMidChunkWalkRendersTheTransportFailureButExits130() {
        Harness harness = new Harness();
        List<CancellationContext> liveContexts = new CopyOnWriteArrayList<>();
        harness.cancellations =
                context -> {
                    liveContexts.add(context);
                    return () -> liveContexts.remove(context);
                };
        for (int chunk = 1; chunk <= 3; chunk++) {
            harness.exchange.enqueue(chunkOutcome(200, "{}"));
        }
        harness.exchange.onDispatch =
                () -> {
                    if (harness.exchange.dispatches.size() == 1) {
                        liveContexts.forEach(context -> context.latch(CancellationContext.Cause.SIGINT));
                    }
                };

        String[] fortyFiveIds =
                IntStream.rangeClosed(1, 45).mapToObj(number -> "poi-" + number).toArray(String[]::new);
        Harness.RunResult result = harness.runDetails(fortyFiveIds);

        assertEquals(
                130,
                result.exitCode(),
                () -> "the latched interrupt owns the process status even though the render is the"
                        + " transport failure document: " + result.describe());
        assertEquals(
                1,
                harness.exchange.dispatches.size(),
                () -> "no chunk request leaves after the interrupt latched: " + result.describe());
        assertEquals(
                List.of("places.details: pagination was cancelled before page 2 (1 of 3 requests completed)"),
                result.stderr().lines().toList(),
                () -> "the transport render carries the walk's request counts: " + result.describe());
    }

    private static Outcome.Success<PlaceEnrichmentResult> chunkOutcome(int status, String body) {
        return new Outcome.Success<>(
                new PlaceEnrichmentResult(status, new UpstreamPayload(body.getBytes(UTF_8)), null, null, null, null));
    }

    private static void assertUnknownOption(String... spelling) {
        Harness harness = new Harness();
        String[] arguments = new String[spelling.length + 1];
        System.arraycopy(spelling, 0, arguments, 0, spelling.length);
        arguments[spelling.length] = "poi-a";
        Harness.RunResult result = harness.runDetails(arguments);
        assertEquals(2, result.exitCode(), () -> "an undocumented spelling is unknown here: " + result.describe());
        assertTrue(result.stderr().contains("Unknown option"), result.describe());
        assertEquals(0, harness.exchange.dispatches.size());
    }

    /** Root fixture shaped like the process root: shared globals plus the places group. */
    @Command(name = "brave-search", description = "Brave Search command line client.")
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
        io.amscotti.bravesearch.application.stream.CancellationRegistry cancellations = new ExchangeRegistry();

        Harness() {
            stored.credential = credential("stored-production-token");
            loopback.credential = credential("loopback-test-key");
        }

        RunResult runDetails(String... args) {
            return run("details", args);
        }

        RunResult runDescribe(String... args) {
            return run("describe", args);
        }

        private RunResult run(String subcommand, String... args) {
            JsonMappers mappers = new JsonMappers();
            JsonlCodec jsonl = new JsonlCodec(mappers);
            PlaceDetailsPresenter detailsPresenter = new PlaceDetailsPresenterImpl(
                    new EnvelopeCodec(mappers),
                    jsonl,
                    new PlaceDetailsExtractor(mappers),
                    (mode, diagnostics, results, quiet) ->
                            new ModeAwareWarnings(mode, PlaceDetailsPresenter.COMMAND, diagnostics, results, jsonl, quiet));
            PlaceDescribePresenter describePresenter = new PlaceDescribePresenterImpl(
                    new EnvelopeCodec(mappers),
                    jsonl,
                    new PlaceDescriptionsExtractor(mappers),
                    (mode, diagnostics, results, quiet) ->
                            new ModeAwareWarnings(mode, PlaceDescribePresenter.COMMAND, diagnostics, results, jsonl, quiet));
            PaginationService<PlaceEnrichmentResult> pagination = new PaginationService<>(
                    PaginationService.ContinuationProbe.alwaysContinue(), new SlicedRateWaiter(Clock.systemUTC()));
            PlacesDetailsCommand details = new PlacesDetailsCommand(
                    globals,
                    new RemoteOptions(STUB_LOCALHOST),
                    stored,
                    loopback,
                    exchange,
                    detailsPresenter,
                    pagination,
                    cancellations,
                    new OutputStreamResultWriter(stdoutBytes),
                    WriterDiagnosticsSink::new);
            PlacesDescribeCommand describe = new PlacesDescribeCommand(
                    globals,
                    new RemoteOptions(STUB_LOCALHOST),
                    stored,
                    loopback,
                    exchange,
                    describePresenter,
                    pagination,
                    cancellations,
                    new OutputStreamResultWriter(stdoutBytes),
                    WriterDiagnosticsSink::new);
            CommandLine group = new CommandLine(new PlacesCommand());
            group.addSubcommand(PlacesDetailsCommand.NAME, new CommandLine(details));
            group.addSubcommand(PlacesDescribeCommand.NAME, new CommandLine(describe));
            Root root = new Root();
            root.globals = globals;
            CommandLine commandLine = new CommandLine(root);
            commandLine.addSubcommand(PlacesCommand.NAME, group);
            StrictOptionParsing.apply(commandLine);
            commandLine.setOut(new PrintWriter(new java.io.OutputStreamWriter(stdoutBytes, UTF_8), true));
            commandLine.setErr(new PrintWriter(new java.io.OutputStreamWriter(stderrBytes, UTF_8), true));
            String[] full = new String[args.length + 2];
            full[0] = "places";
            full[1] = subcommand;
            System.arraycopy(args, 0, full, 2, args.length);
            int exitCode = commandLine.execute(full);
            return new RunResult(exitCode, stdoutBytes.toString(UTF_8), stderrBytes.toString(UTF_8));
        }

        record RunResult(int exitCode, String stdout, String stderr) {
            String describe() {
                return "exitCode=" + exitCode + ", stdout=<" + stdout + ">, stderr=<" + stderr + ">";
            }
        }
    }

    private static final class RecordingExchange implements PlaceEnrichmentExchange {
        final List<PlaceEnrichmentDispatch> dispatches = new ArrayList<>();
        final java.util.ArrayDeque<Outcome<PlaceEnrichmentResult>> queued = new java.util.ArrayDeque<>();
        Runnable onDispatch = () -> {};
        Outcome<PlaceEnrichmentResult> outcome = new Outcome.Success<>(
                new PlaceEnrichmentResult(200, new UpstreamPayload("{}".getBytes(UTF_8)), null, null, null, null));

        void enqueue(Outcome<PlaceEnrichmentResult> queued) {
            this.queued.add(queued);
        }

        @Override
        public Outcome<PlaceEnrichmentResult> dispatch(PlaceEnrichmentDispatch invocation) {
            dispatches.add(invocation);
            onDispatch.run();
            return queued.isEmpty() ? outcome : queued.poll();
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

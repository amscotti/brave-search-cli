package io.amscotti.bravesearch.adapter.cli.command.places;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.option.CommonSearchOptions;
import io.amscotti.bravesearch.adapter.cli.option.GlobalOptions;
import io.amscotti.bravesearch.adapter.cli.option.RemoteOptions;
import io.amscotti.bravesearch.adapter.cli.option.StrictOptionParsing;
import io.amscotti.bravesearch.adapter.cli.presentation.OutputStreamResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.PlacesSearchPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.WriterDiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.places.PlacesProjectionExtractor;
import io.amscotti.bravesearch.adapter.cli.presentation.places.PlacesSearchPresenterImpl;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.LocalhostResolver;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.CredentialResolutionException;
import io.amscotti.bravesearch.application.port.out.PlaceSearchDispatch;
import io.amscotti.bravesearch.application.port.out.PlaceSearchExchange;
import io.amscotti.bravesearch.application.port.out.ResolvedCredential;
import io.amscotti.bravesearch.bootstrap.ExchangeRegistry;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.request.PlaceAnchor;
import io.amscotti.bravesearch.domain.request.PlaceSearchRequest;
import io.amscotti.bravesearch.domain.result.PlaceSearchResult;
import java.io.PrintWriter;
import java.math.BigDecimal;
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
 * Grammar and dispatch contracts of the places search command: the query is always
 * optional — the explore and broad-global spellings dispatch exactly like a queried
 * one — coordinates pair and refuse the place-name anchor, both geoloc components are
 * range-checked and serialized exactly, the radius keeps its finite-decimal rule, the
 * count bound of 100 fails before any dispatch, the place-name anchor refuses control
 * characters and edge whitespace at the option boundary, and the shared spellings the
 * places endpoint leaves undocumented are refused here.
 */
final class PlacesSearchCommandTest {

    private static final LocalhostResolver STUB_LOCALHOST = host -> List.of(InetAddress.getByName("127.0.0.1"));

    @Test
    void dispatchesTheFullyMappedRequest() {
        Harness harness = new Harness();

        harness.run(
                "places",
                "search",
                "three word query",
                "--latitude",
                "40.69",
                "--longitude",
                "-74.25",
                "--radius",
                "1500",
                "--count",
                "50",
                "--geoloc",
                "40.69x-74.25",
                "--units",
                "imperial",
                "--country",
                "US",
                "--search-lang",
                "en",
                "--ui-lang",
                "en-US",
                "--safe-search",
                "moderate",
                "--no-spellcheck");

        PlaceSearchRequest request = harness.exchange.dispatches.getFirst().request();
        assertEquals("three word query", request.query());
        assertEquals(new PlaceAnchor.Coordinates(40.69, -74.25), request.anchor());
        assertEquals(new BigDecimal("1500"), request.radius());
        assertEquals(50, request.count());
        assertEquals("40.69x-74.25", request.geoloc().wireForm());
        assertEquals(io.amscotti.bravesearch.domain.request.Units.IMPERIAL, request.units());
        assertEquals("US", request.country());
        assertEquals("en", request.searchLang());
        assertEquals("en-US", request.uiLang());
        assertEquals(io.amscotti.bravesearch.domain.request.SafeSearch.MODERATE, request.safeSearch());
        assertEquals(Boolean.FALSE, request.spellcheck());
        assertEquals(BraveApiOrigin.production(), harness.exchange.dispatches.getFirst().origin());
    }

    @Test
    void exploreModeDispatchesTheAnchorWithoutAnyQuery() {
        Harness harness = new Harness();

        harness.run("places", "search", "--location", "Philadelphia PA US");

        PlaceSearchRequest request = harness.exchange.dispatches.getFirst().request();
        assertNull(request.query(), "the explore request carries no query at all");
        assertEquals(new PlaceAnchor.LocationName("Philadelphia PA US"), request.anchor());
    }

    @Test
    void broadGlobalModeDispatchesWithoutQueryOrAnchor() {
        Harness harness = new Harness();

        harness.run("places", "search");

        PlaceSearchRequest request = harness.exchange.dispatches.getFirst().request();
        assertNull(request.query());
        assertNull(request.anchor());
    }

    @Test
    void everyAnchorRuleFailsUsageBeforeAnyDispatch() {
        Harness harness = new Harness();

        assertUsageRejection(harness, "latitude and longitude must be supplied together", "--latitude", "40.69");
        assertUsageRejection(harness, "latitude and longitude must be supplied together", "--longitude", "-74.25");
        assertUsageRejection(
                harness,
                "latitude and longitude cannot be combined with location",
                "--latitude",
                "40.69",
                "--longitude",
                "-74.25",
                "--location",
                "Philadelphia");
        assertUsageRejection(harness, "latitude must be between -90 and 90", "--latitude", "91", "--longitude", "0");
        assertUsageRejection(harness, "longitude must be between -180 and 180", "--latitude", "0", "--longitude", "181");
        assertUsageRejection(harness, "Invalid value for option '--latitude'", "--latitude", "NaN", "--longitude", "0");
        assertUsageRejection(harness, "Invalid value for option '--longitude'", "--latitude", "0", "--longitude", "Infinity");
        assertUsageRejection(harness, "geoloc latitude must be between -90 and 90", "--geoloc", "91x0");
        assertUsageRejection(harness, "geoloc longitude must be between -180 and 180", "--geoloc", "0x181");
        assertUsageRejection(
                harness,
                "geoloc must be two decimal coordinates spelled latitudexlongitude",
                "--geoloc",
                "40.69");
        assertEquals(0, harness.exchange.dispatches.size(), "an invalid request must never be dispatched");
    }

    @Test
    void adversarialLocationStringsAreRejectedAtTheOptionBoundary() {
        Harness harness = new Harness();

        assertUsageRejection(harness, "location must not carry control characters", "--location", "Phil\nadelphia");
        assertUsageRejection(harness, "location must not carry control characters", "--location", "Phil\0adelphia");
        assertUsageRejection(harness, "location must not begin or end with whitespace", "--location", " Philadelphia");
        assertUsageRejection(harness, "location must not begin or end with whitespace", "--location", "Philadelphia ");
        assertUsageRejection(harness, "location must not be blank when supplied", "--location", "  ");
        assertEquals(0, harness.exchange.dispatches.size());
    }

    @Test
    void theCountBudgetAndRadiusRulesFailUsageBeforeAnyDispatch() {
        Harness harness = new Harness();

        assertUsageRejection(harness, "count must be between 1 and 100", "--count", "101", "coffee");
        assertUsageRejection(harness, "count must be between 1 and 100", "--count", "0", "coffee");
        assertUsageRejection(harness, "Invalid value for option '--radius'", "--radius", "NaN", "coffee");
        assertUsageRejection(harness, "Invalid value for option '--radius'", "--radius", "Infinity", "coffee");
        assertUsageRejection(harness, "radius must be zero or greater", "--radius", "-1", "coffee");
        assertEquals(0, harness.exchange.dispatches.size());
    }

    @Test
    void exponentSpellingsOfTheDecimalOptionsFailUsageBeforeAnyDispatch() {
        Harness harness = new Harness();

        assertUsageRejection(
                harness,
                "geoloc must be two decimal coordinates spelled latitudexlongitude",
                "--geoloc",
                "1e2x5",
                "coffee");
        assertUsageRejection(
                harness,
                "radius must be a plain decimal, not an exponent spelling",
                "--radius",
                "1e2147483647",
                "coffee");
        assertEquals(0, harness.exchange.dispatches.size());
    }

    @Test
    void undocumentedSharedSpellingsAreRejectedHere() {
        Harness harness = new Harness();

        Harness.RunResult freshness = harness.run("places", "search", "--freshness", "pd", "coffee");
        assertEquals(2, freshness.exitCode(), () -> "the spelling must be refused: " + freshness.describe());
        assertTrue(freshness.stderr().contains("--freshness is not accepted by this command"), () -> freshness.describe());

        Harness.RunResult page = harness.run("places", "search", "--page", "2", "coffee");
        assertEquals(2, page.exitCode(), () -> "the spelling must be refused: " + page.describe());
        assertTrue(page.stderr().contains("--page is not accepted by this command"), () -> page.describe());
        assertEquals(0, harness.exchange.dispatches.size());
    }

    @Test
    void goggleAndPageWalkSpellingsAreUnknownOptionsOfThePlacesCommand() {
        Harness harness = new Harness();

        for (String spelling : new String[] {"--goggle", "--goggle-file", "--include-site", "--exclude-site",
            "--all-pages", "--max-pages", "--loc-city", "--loc-timezone"}) {
            Harness.RunResult result = harness.run("places", "search", spelling, "a.example", "coffee");
            assertEquals(2, result.exitCode(), () -> "the spelling must be unknown: " + result.describe());
            assertTrue(result.stderr().contains("Unknown option"), () -> result.describe());
        }
        assertEquals(0, harness.exchange.dispatches.size());
    }

    @Test
    void rawOutputIsAcceptedForTheSingleRequestPlacesCommand() {
        Harness harness = new Harness();

        Harness.RunResult result = harness.run("places", "search", "--output", "raw", "coffee");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(1, harness.exchange.dispatches.size());
    }

    @Test
    void aFailingExchangeHandsTheFailureKindToThePresenterStatus() {
        Harness harness = new Harness();
        harness.exchange.outcome =
                new Outcome.Failure<>(FailureKind.AUTHENTICATION, "upstream exchange failed with status 401");

        Harness.RunResult result = harness.run("places", "search", "coffee");

        assertEquals(4, result.exitCode(), () -> result.describe());
    }

    private static void assertUsageRejection(Harness harness, String expectedFragment, String... arguments) {
        String[] full = new String[arguments.length + 2];
        full[0] = "places";
        full[1] = "search";
        System.arraycopy(arguments, 0, full, 2, arguments.length);
        Harness.RunResult result = harness.run(full);
        assertEquals(2, result.exitCode(), () -> "the rule must reject with usage: " + result.describe());
        assertTrue(result.stderr().contains(expectedFragment), () -> result.describe());
    }

    /** Root fixture shaped like the process root: shared globals plus the places group. */
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
            PlacesSearchPresenter presenter = new PlacesSearchPresenterImpl(
                    new EnvelopeCodec(mappers),
                    jsonl,
                    new io.amscotti.bravesearch.adapter.cli.presentation.json.RawCodec(),
                    new PlacesProjectionExtractor(mappers),
                    (mode, diagnostics, results, quiet) ->
                            new io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings(
                                    mode, PlacesSearchPresenter.COMMAND, diagnostics, results, jsonl, quiet));
            PlacesSearchCommand search = new PlacesSearchCommand(
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
            CommandLine group = new CommandLine(new PlacesCommand());
            group.addSubcommand(PlacesSearchCommand.NAME, new CommandLine(search));
            Root root = new Root();
            root.globals = globals;
            CommandLine commandLine = new CommandLine(root);
            commandLine.addSubcommand(PlacesCommand.NAME, group);
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

    private static final class RecordingExchange implements PlaceSearchExchange {
        final List<PlaceSearchDispatch> dispatches = new ArrayList<>();
        Outcome<PlaceSearchResult> outcome = new Outcome.Success<>(
                new PlaceSearchResult(200, new UpstreamPayload("{}".getBytes(UTF_8)), null, null, null, null));

        @Override
        public Outcome<PlaceSearchResult> dispatch(PlaceSearchDispatch invocation) {
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

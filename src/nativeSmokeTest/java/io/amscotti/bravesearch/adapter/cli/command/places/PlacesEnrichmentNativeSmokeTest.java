package io.amscotti.bravesearch.adapter.cli.command.places;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.presentation.PlaceDescribePresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.PlaceDetailsPresenter;
import io.amscotti.bravesearch.testsupport.PlacesEnrichmentScenarios;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The same whole-process place enrichment evidence as the JVM process suite, run
 * against the exact native executable produced by {@code nativeCompile}: the
 * chunked wire form, the order reconstruction, the invocation cap, the
 * partial-failure matrix, pacing, the raw rejection, the credential routing, the
 * pipe close, and the mid-fan-out interrupt must all agree with the JVM's behavior
 * — the native smoke suite is the loopback-agreeing subset of the JVM-pinned
 * scenarios, bounded by its source set, not a narrower contract.
 */
final class PlacesEnrichmentNativeSmokeTest {

    @Test
    @Timeout(180)
    void humanHappyPathRendersInputOrderWithPlaceholders() throws Exception {
        PlacesEnrichmentScenarios.humanHappyPathRendersInputOrderWithPlaceholders(detailsCommand());
    }

    @Test
    @Timeout(180)
    void autoChunkingServesFortyFiveIdsAsThreeChunkRequests() throws Exception {
        PlacesEnrichmentScenarios.autoChunkingServesFortyFiveIdsAsThreeChunkRequests(detailsCommand());
    }

    @Test
    @Timeout(180)
    void describeAutoChunkingWalksItsOwnEndpointPath() throws Exception {
        PlacesEnrichmentScenarios.describeAutoChunkingWalksItsOwnEndpointPath(describeCommand());
    }

    @Test
    @Timeout(180)
    void jsonEnvelopeCarriesTheMultiRequestUpstreamArray() throws Exception {
        PlacesEnrichmentScenarios.jsonEnvelopeCarriesTheMultiRequestUpstreamArray(detailsCommand());
    }

    @Test
    @Timeout(180)
    void duplicatesAndMissingIdsReconstructAtTheirOriginalPositions() throws Exception {
        PlacesEnrichmentScenarios.duplicatesAndMissingIdsReconstructAtTheirOriginalPositions(
                detailsCommand(),
                PlaceDetailsPresenter.COMMAND,
                "places-details-result.schema.json",
                "places-details-summary.schema.json");
    }

    @Test
    @Timeout(180)
    void describeDuplicatesAndMissingIdsReconstructAtTheirOriginalPositions() throws Exception {
        PlacesEnrichmentScenarios.duplicatesAndMissingIdsReconstructAtTheirOriginalPositions(
                describeCommand(),
                PlaceDescribePresenter.COMMAND,
                "places-describe-result.schema.json",
                "places-describe-summary.schema.json");
    }

    @Test
    @Timeout(180)
    void invocationCapAcceptsTwoHundredAndRejectsTwoHundredAndOne() throws Exception {
        PlacesEnrichmentScenarios.invocationCapAcceptsTwoHundredAndRejectsTwoHundredAndOne(detailsCommand());
    }

    @Test
    @Timeout(180)
    void laterChunkFailureFollowsThePaginationPartialRules() throws Exception {
        PlacesEnrichmentScenarios.laterChunkFailureFollowsThePaginationPartialRules(
                detailsCommand(), PlaceDetailsPresenter.COMMAND);
    }

    @Test
    @Timeout(180)
    void describeLaterChunkFailureFollowsThePaginationPartialRules() throws Exception {
        PlacesEnrichmentScenarios.laterChunkFailureFollowsThePaginationPartialRules(
                describeCommand(), PlaceDescribePresenter.COMMAND);
    }

    @Test
    @Timeout(180)
    void chunkPacingWaitsBehindAnExhaustedWindow() throws Exception {
        PlacesEnrichmentScenarios.chunkPacingWaitsBehindAnExhaustedWindow(detailsCommand());
    }

    @Test
    @Timeout(180)
    void rawOutputIsRejectedBeforeAnyDispatch() throws Exception {
        PlacesEnrichmentScenarios.rawOutputIsRejectedBeforeAnyDispatch(detailsCommand());
    }

    @Test
    @Timeout(180)
    void describeRawOutputIsRejectedBeforeAnyDispatch() throws Exception {
        PlacesEnrichmentScenarios.rawOutputIsRejectedBeforeAnyDispatch(describeCommand());
    }

    @Test
    @Timeout(180)
    void authenticationFailureExitsFourInHumanAndJsonModes() throws Exception {
        PlacesEnrichmentScenarios.authenticationFailureExitsFourInHumanAndJsonModes(
                detailsCommand(), PlaceDetailsPresenter.COMMAND);
    }

    @Test
    @Timeout(180)
    void loopbackRunSendsTheTestKeyOnEveryChunk() throws Exception {
        PlacesEnrichmentScenarios.loopbackRunSendsTheTestKeyOnEveryChunk(detailsCommand());
    }

    @Test
    @Timeout(180)
    void apiVersionPinReachesTheWire() throws Exception {
        PlacesEnrichmentScenarios.apiVersionPinReachesTheWire(detailsCommand());
    }

    @Test
    @Timeout(180)
    void downstreamPipeCloseMidFanOutIsSilentZero() throws Exception {
        PlacesEnrichmentScenarios.downstreamPipeCloseMidFanOutIsSilentZero(detailsCommand());
    }

    @Test
    @Timeout(180)
    void sigintDuringPacedChunkWalkExits130() throws Exception {
        PlacesEnrichmentScenarios.sigintDuringPacedChunkWalkExits130(detailsCommand(), PlaceDetailsPresenter.COMMAND);
    }

    private static List<String> detailsCommand() {
        return enrichmentCommand(PlacesDetailsCommand.NAME);
    }

    private static List<String> describeCommand() {
        return enrichmentCommand(PlacesDescribeCommand.NAME);
    }

    private static List<String> enrichmentCommand(String subcommand) {
        String binaryProperty = System.getProperty("brave.search.native.binary");
        assertNotNull(binaryProperty, "brave.search.native.binary must be injected by the build");
        Path binary = Path.of(binaryProperty);
        assertTrue(Files.isRegularFile(binary), () -> "native binary missing: " + binary);
        assertTrue(Files.isExecutable(binary), () -> "native binary not executable: " + binary);
        return List.of(binary.toString(), PlacesCommand.NAME, subcommand);
    }
}

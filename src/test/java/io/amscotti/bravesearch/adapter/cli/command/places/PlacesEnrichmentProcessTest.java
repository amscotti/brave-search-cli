package io.amscotti.bravesearch.adapter.cli.command.places;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.presentation.PlaceDescribePresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.PlaceDetailsPresenter;
import io.amscotti.bravesearch.testsupport.PlacesEnrichmentScenarios;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Whole-process evidence for the place enrichment commands against the JVM executable
 * produced by {@code installDist}: the launcher script, the real credential routing,
 * the real stdout pipe, and the real exit statuses, all against a scripted loopback
 * server with an isolated environment.
 */
final class PlacesEnrichmentProcessTest {

    @Test
    @Timeout(120)
    void humanHappyPathRendersInputOrderWithPlaceholders() throws Exception {
        PlacesEnrichmentScenarios.humanHappyPathRendersInputOrderWithPlaceholders(detailsCommand());
    }

    @Test
    @Timeout(120)
    void autoChunkingServesFortyFiveIdsAsThreeChunkRequests() throws Exception {
        PlacesEnrichmentScenarios.autoChunkingServesFortyFiveIdsAsThreeChunkRequests(detailsCommand());
    }

    @Test
    @Timeout(120)
    void describeAutoChunkingWalksItsOwnEndpointPath() throws Exception {
        PlacesEnrichmentScenarios.describeAutoChunkingWalksItsOwnEndpointPath(describeCommand());
    }

    @Test
    @Timeout(120)
    void jsonEnvelopeCarriesTheMultiRequestUpstreamArray() throws Exception {
        PlacesEnrichmentScenarios.jsonEnvelopeCarriesTheMultiRequestUpstreamArray(detailsCommand());
    }

    @Test
    @Timeout(120)
    void duplicatesAndMissingIdsReconstructAtTheirOriginalPositions() throws Exception {
        PlacesEnrichmentScenarios.duplicatesAndMissingIdsReconstructAtTheirOriginalPositions(
                detailsCommand(),
                PlaceDetailsPresenter.COMMAND,
                "places-details-result.schema.json",
                "places-details-summary.schema.json");
    }

    @Test
    @Timeout(120)
    void describeDuplicatesAndMissingIdsReconstructAtTheirOriginalPositions() throws Exception {
        PlacesEnrichmentScenarios.duplicatesAndMissingIdsReconstructAtTheirOriginalPositions(
                describeCommand(),
                PlaceDescribePresenter.COMMAND,
                "places-describe-result.schema.json",
                "places-describe-summary.schema.json");
    }

    @Test
    @Timeout(120)
    void invocationCapAcceptsTwoHundredAndRejectsTwoHundredAndOne() throws Exception {
        PlacesEnrichmentScenarios.invocationCapAcceptsTwoHundredAndRejectsTwoHundredAndOne(detailsCommand());
    }

    @Test
    @Timeout(120)
    void laterChunkFailureFollowsThePaginationPartialRules() throws Exception {
        PlacesEnrichmentScenarios.laterChunkFailureFollowsThePaginationPartialRules(
                detailsCommand(), PlaceDetailsPresenter.COMMAND);
    }

    @Test
    @Timeout(120)
    void describeLaterChunkFailureFollowsThePaginationPartialRules() throws Exception {
        PlacesEnrichmentScenarios.laterChunkFailureFollowsThePaginationPartialRules(
                describeCommand(), PlaceDescribePresenter.COMMAND);
    }

    @Test
    @Timeout(120)
    void chunkPacingWaitsBehindAnExhaustedWindow() throws Exception {
        PlacesEnrichmentScenarios.chunkPacingWaitsBehindAnExhaustedWindow(detailsCommand());
    }

    @Test
    @Timeout(120)
    void rawOutputIsRejectedBeforeAnyDispatch() throws Exception {
        PlacesEnrichmentScenarios.rawOutputIsRejectedBeforeAnyDispatch(detailsCommand());
    }

    @Test
    @Timeout(120)
    void describeRawOutputIsRejectedBeforeAnyDispatch() throws Exception {
        PlacesEnrichmentScenarios.rawOutputIsRejectedBeforeAnyDispatch(describeCommand());
    }

    @Test
    @Timeout(120)
    void authenticationFailureExitsFourInHumanAndJsonModes() throws Exception {
        PlacesEnrichmentScenarios.authenticationFailureExitsFourInHumanAndJsonModes(
                detailsCommand(), PlaceDetailsPresenter.COMMAND);
    }

    @Test
    @Timeout(120)
    void loopbackRunSendsTheTestKeyOnEveryChunk() throws Exception {
        PlacesEnrichmentScenarios.loopbackRunSendsTheTestKeyOnEveryChunk(detailsCommand());
    }

    @Test
    @Timeout(120)
    void apiVersionPinReachesTheWire() throws Exception {
        PlacesEnrichmentScenarios.apiVersionPinReachesTheWire(detailsCommand());
    }

    @Test
    @Timeout(120)
    void downstreamPipeCloseMidFanOutIsSilentZero() throws Exception {
        PlacesEnrichmentScenarios.downstreamPipeCloseMidFanOutIsSilentZero(detailsCommand());
    }

    @Test
    @Timeout(120)
    void sigintDuringPacedChunkWalkExits130() throws Exception {
        PlacesEnrichmentScenarios.sigintDuringPacedChunkWalkExits130(detailsCommand(), PlaceDetailsPresenter.COMMAND);
    }

    @Test
    @Timeout(120)
    void chunkWalkInterruptWaitsForTheCurrentInvocationToStart() throws Exception {
        List<String> delayedCommand = new ArrayList<>(List.of(
                "/bin/sh", "-c", "trap 'exit 130' INT; sleep 2; exec \"$@\"", "delayed-search"));
        delayedCommand.addAll(detailsCommand());

        PlacesEnrichmentScenarios.sigintDuringPacedChunkWalkExits130(delayedCommand, PlaceDetailsPresenter.COMMAND);
    }

    private static List<String> detailsCommand() {
        return enrichmentCommand(PlacesDetailsCommand.NAME);
    }

    private static List<String> describeCommand() {
        return enrichmentCommand(PlacesDescribeCommand.NAME);
    }

    private static List<String> enrichmentCommand(String subcommand) {
        String launcherProperty = System.getProperty("brave.search.jvm.launcher");
        assertNotNull(launcherProperty, "brave.search.jvm.launcher must be injected by the build");
        Path launcher = Path.of(launcherProperty);
        assertTrue(Files.isRegularFile(launcher), () -> "installed launcher missing: " + launcher);
        assertTrue(Files.isExecutable(launcher), () -> "installed launcher not executable: " + launcher);
        return List.of(launcher.toString(), PlacesCommand.NAME, subcommand);
    }
}

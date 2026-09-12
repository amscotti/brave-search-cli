package io.amscotti.bravesearch.adapter.cli.command.places;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.testsupport.PlacesScenarios;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The same whole-process places search command evidence as the JVM process suite, run
 * against the exact native executable produced by {@code nativeCompile}: every success
 * shape, the failure and usage contracts — the anchor rules, the geoloc spelling and
 * ranges, the radius rule, the count budget, and the header-safety boundary included —
 * the query-less explore and broad-global spellings, the credential routing under a
 * decoy environment, and the Api-Version pin must all agree with the JVM's behavior —
 * the native smoke suite is the loopback-agreeing subset of the JVM-pinned scenarios,
 * bounded by its source set, not a narrower contract.
 */
final class PlacesNativeSmokeTest {

    @Test
    @Timeout(180)
    void humanHappyPathRendersNumberedLfLinesWithoutAnsi() throws Exception {
        PlacesScenarios.humanHappyPathRendersNumberedLfLinesWithoutAnsi(placesCommand());
    }

    @Test
    @Timeout(180)
    void jsonEnvelopeIsOneValidatedLfLine() throws Exception {
        PlacesScenarios.jsonSuccessEnvelopeIsOneValidatedLfLine(placesCommand());
    }

    @Test
    @Timeout(180)
    void jsonlEmitsValidatedResultRecordsThenSummary() throws Exception {
        PlacesScenarios.jsonlEmitsValidatedResultRecordsThenSummary(placesCommand());
    }

    @Test
    @Timeout(180)
    void rawOutputIsTheServedBodyBytesExactly() throws Exception {
        PlacesScenarios.rawOutputIsTheServedBodyBytesExactly(placesCommand());
    }

    @Test
    @Timeout(180)
    void authenticationFailureExitsFourInHumanAndJsonModes() throws Exception {
        PlacesScenarios.authenticationFailureExitsFourInHumanAndJsonModes(placesCommand());
    }

    @Test
    @Timeout(180)
    void usageFailureHappensBeforeAnyNetworkDispatch() throws Exception {
        PlacesScenarios.usageFailureHappensBeforeAnyNetworkDispatch(placesCommand());
    }

    @Test
    @Timeout(180)
    void exploreModeDispatchesTheAnchorWithoutAnyQuery() throws Exception {
        PlacesScenarios.exploreModeDispatchesTheAnchorWithoutAnyQuery(placesCommand());
    }

    @Test
    @Timeout(180)
    void broadGlobalModeDispatchesWithoutQueryOrAnchor() throws Exception {
        PlacesScenarios.broadGlobalModeDispatchesWithoutQueryOrAnchor(placesCommand());
    }

    @Test
    @Timeout(180)
    void loopbackRunsSendTheTestKeyWithTheExactAnchorWireForm() throws Exception {
        PlacesScenarios.loopbackRunSendsTheTestKeyWithTheExactAnchorWireForm(placesCommand());
    }

    @Test
    @Timeout(180)
    void missingLoopbackTestKeyKeepsTheLocalConfigurationExit() throws Exception {
        PlacesScenarios.missingLoopbackTestKeyKeepsTheLocalConfigurationExit(placesCommand());
    }

    @Test
    @Timeout(180)
    void requestedRadiusAppendsTheRankingBiasNote() throws Exception {
        PlacesScenarios.requestedRadiusAppendsTheRankingBiasNote(placesCommand());
    }

    @Test
    @Timeout(180)
    void downstreamPipeCloseIsSilentZero() throws Exception {
        PlacesScenarios.downstreamPipeCloseIsSilentZero(placesCommand());
    }

    @Test
    @Timeout(180)
    void apiVersionPinReachesTheWireAndImpossibleDatesFailUsage() throws Exception {
        PlacesScenarios.apiVersionPinReachesTheWireAndImpossibleDatesFailUsage(placesCommand());
    }

    private static List<String> placesCommand() {
        String binaryProperty = System.getProperty("brave.search.native.binary");
        assertNotNull(binaryProperty, "brave.search.native.binary must be injected by the build");
        Path binary = Path.of(binaryProperty);
        assertTrue(Files.isRegularFile(binary), () -> "native binary missing: " + binary);
        assertTrue(Files.isExecutable(binary), () -> "native binary not executable: " + binary);
        return List.of(binary.toString(), PlacesCommand.NAME, PlacesSearchCommand.NAME);
    }
}

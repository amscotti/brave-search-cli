package io.amscotti.bravesearch.adapter.bravehttp.endpoint;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import org.junit.jupiter.api.Test;

/**
 * The tolerant web continuation probe: only a boolean {@code true} on the top-level
 * {@code query.more_results_available} member continues a pagination run — false, absent,
 * non-boolean, nested lookalikes, and unreadable bodies all stop after their page, because
 * a misread continuation must never burst a user's quota.
 */
final class WebContinuationProbeTest {

    private final WebContinuationProbe probe = new WebContinuationProbe();

    @Test
    void aTrueFlagOnTheQueryMemberContinues() {
        assertTrue(probe.moreResultsAvailable(body("{\"query\":{\"more_results_available\":true}}")));
        assertTrue(
                probe.moreResultsAvailable(
                        body("{\"query\":{\"more_results_available\":true,\"original\":\"q\"},\"web\":{\"results\":[]}}")));
    }

    @Test
    void aFalseFlagStopsTheRun() {
        assertFalse(probe.moreResultsAvailable(body("{\"query\":{\"more_results_available\":false}}")));
    }

    @Test
    void anAbsentFlagStopsTheRun() {
        assertFalse(probe.moreResultsAvailable(body("{\"query\":{\"original\":\"q\"}}")));
        assertFalse(probe.moreResultsAvailable(body("{\"web\":{\"results\":[]}}")));
        assertFalse(probe.moreResultsAvailable(body("{}")));
        assertFalse(probe.moreResultsAvailable(body("[1,2,3]")));
    }

    @Test
    void aNonBooleanFlagStopsTheRun() {
        assertFalse(probe.moreResultsAvailable(body("{\"query\":{\"more_results_available\":\"true\"}}")));
        assertFalse(probe.moreResultsAvailable(body("{\"query\":{\"more_results_available\":1}}")));
        assertFalse(probe.moreResultsAvailable(body("{\"query\":{\"more_results_available\":null}}")));
    }

    @Test
    void nestedLookalikesNeverContinueTheRun() {
        assertFalse(
                probe.moreResultsAvailable(
                        body("{\"query\":{\"more_results_available\":false},\"discussions\":{\"more_results_available\":true}}")));
        assertFalse(
                probe.moreResultsAvailable(
                        body("{\"query\":{\"original\":\"see {\\\"more_results_available\\\":true} here\"}}")));
        assertFalse(probe.moreResultsAvailable(body("{\"videos\":{\"more_results_available\":true}}")));
    }

    @Test
    void anUnreadableBodyIsTreatedAsAnAbsentFlag() {
        assertFalse(probe.moreResultsAvailable(body("gateway exploded <html>")));
        assertFalse(probe.moreResultsAvailable(body("")));
        assertFalse(probe.moreResultsAvailable(body("{\"query\":{\"more_results_available\":tr")));
    }

    private static UpstreamPayload body(String text) {
        return new UpstreamPayload(text.getBytes(UTF_8));
    }
}

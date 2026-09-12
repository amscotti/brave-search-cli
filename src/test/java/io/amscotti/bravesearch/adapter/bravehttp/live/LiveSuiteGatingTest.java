package io.amscotti.bravesearch.adapter.bravehttp.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Always-run structural gate of the opt-in live protocol smoke: the live-tagged suite stays
 * out of every default test run and joins only when the explicit Gradle property opts in,
 * every live class carries the tag that implements the gate, and the release documentation
 * names the invocation. This test performs no network exchange; it pins the gating mechanics
 * so a wiring regression fails an ordinary hermetic build.
 */
final class LiveSuiteGatingTest {

    private static final Path BUILD_SCRIPT = Path.of("build.gradle.kts");

    private static final Path RELEASE_DOC = Path.of("docs", "release.md");

    private static final Path GRADLE_PROPERTIES = Path.of("gradle.properties");

    /** The live protocol smoke classes the Gradle tag gate keeps out of default runs. */
    private static final List<String> LIVE_CLASSES =
            List.of(
                    "io.amscotti.bravesearch.adapter.bravehttp.live.LiveWebSearchTest",
                    "io.amscotti.bravesearch.adapter.bravehttp.live.LiveContextTest",
                    "io.amscotti.bravesearch.adapter.bravehttp.live.LivePlacesTest",
                    "io.amscotti.bravesearch.adapter.bravehttp.live.LiveAnswersTest",
                    "io.amscotti.bravesearch.adapter.bravehttp.live.LiveApiVersionObservationTest");

    @Test
    void liveSuiteIsExcludedByDefaultAndJoinsOnlyThroughTheGradleProperty() throws IOException {
        String build = Files.readString(BUILD_SCRIPT, StandardCharsets.UTF_8);
        assertTrue(
                build.contains("liveBraveTests"),
                "the build script must gate the live suite behind the liveBraveTests Gradle property");
        assertTrue(
                build.contains("excludeTags(\"live\")"),
                "the default test run must exclude the live tag, so ordinary builds make zero external calls");
        assertTrue(
                build.contains("maxParallelForks = 1"),
                "the test task must pin single-fork execution for the serial live policy");
    }

    @Test
    void everyLiveTestClassCarriesTheLiveTag() throws Exception {
        for (String className : LIVE_CLASSES) {
            Class<?> live = Class.forName(className);
            Tag tag = live.getAnnotation(Tag.class);
            assertNotNull(tag, className + " must be tagged for the Gradle gate to exclude it");
            assertEquals("live", tag.value(), className + " must carry exactly the live tag");
        }
    }

    @Test
    void releaseDocumentationNamesTheOptInInvocation() throws IOException {
        String release = Files.readString(RELEASE_DOC, StandardCharsets.UTF_8);
        assertTrue(
                release.contains("-PliveBraveTests=true"),
                "docs/release.md must document the explicit opt-in invocation of the live suite");
    }

    @Test
    void theLiveOptInStaysCommandLineOnlyAndNeverPersistsInGradleProperties() throws IOException {
        String properties = Files.readString(GRADLE_PROPERTIES, StandardCharsets.UTF_8);
        assertFalse(
                properties.contains("liveBraveTests"),
                "gradle.properties must never persist liveBraveTests: a committed property would silently"
                        + " turn every local and CI build into a live, credentialed run; the opt-in stays"
                        + " a command-line -P flag");
        String release = Files.readString(RELEASE_DOC, StandardCharsets.UTF_8);
        assertTrue(
                release.contains("never persists in `gradle.properties`"),
                "docs/release.md must state that the live opt-in is command-line only and never persisted"
                        + " in gradle.properties");
    }

    @Test
    void everyLiveTestDeadlineFiresStrictlyAfterTheClientTotalBudget() throws Exception {
        for (String className : LIVE_CLASSES) {
            Class<?> live = Class.forName(className);
            for (Method method : live.getDeclaredMethods()) {
                Timeout deadline = method.getAnnotation(Timeout.class);
                if (deadline == null) {
                    continue;
                }
                assertTrue(
                        Duration.ofSeconds(deadline.value()).compareTo(LiveGuard.CLIENT_TOTAL_TIMEOUT) > 0,
                        className + "#" + method.getName()
                                + " must carry a JUnit deadline strictly after the client's total budget"
                                + " ("
                                + LiveGuard.CLIENT_TOTAL_TIMEOUT
                                + "), so a hung exchange fails as the client's own classified transport"
                                + " failure first and the JUnit timeout stays the backstop");
            }
        }
    }
}

package io.amscotti.bravesearch.release;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * Durability gate of the repository retry budget. Every build resolves from a clean cache, so a
 * rate-limited repository reaches Gradle itself, which retries a transient failure — an HTTP
 * 429 or 5xx, a dropped connection — with a doubling pause. Gradle's default budget is three
 * attempts within three seconds, shorter than a rate-limit window lasts, and an exhausted
 * budget surfaces as a plugin or artifact that "was not found". {@code gradle.properties}
 * raises the budget, and because the two settings name Gradle-internal properties, this gate
 * also proves the running distribution still reads the names they are declared under: a
 * renamed property would leave the budget inert with nothing failing.
 */
final class RepositoryRetryBudgetTest {

    private static final Path PROPERTIES = Paths.get("gradle.properties");

    /** Where the build names the Gradle installation whose classes this gate inspects. */
    private static final String GRADLE_HOME = "brave.search.gradle.home";

    /** The budget's attempts, as the repository access reads them when it is created. */
    private static final String ATTEMPTS = "org.gradle.internal.repository.max.tentatives";

    /** The pause before the second attempt, in milliseconds; every further pause doubles it. */
    private static final String INITIAL_BACKOFF_MILLIS = "org.gradle.internal.repository.initial.backoff";

    /** Long enough to outlast a rate-limit window; the default three-second budget cannot. */
    private static final long SHORTEST_SPAN_MILLIS = 120_000;

    /** Short enough that a resolution which cannot succeed still fails within minutes. */
    private static final long LONGEST_SPAN_MILLIS = 300_000;

    @Test
    void theBudgetSpansMinutesWhereGradlesDefaultSpansThreeSeconds() throws IOException {
        Properties settings = new Properties();
        try (Reader reader = Files.newBufferedReader(PROPERTIES, StandardCharsets.UTF_8)) {
            settings.load(reader);
        }

        int attempts = budgeted(settings, ATTEMPTS);
        int backoff = budgeted(settings, INITIAL_BACKOFF_MILLIS);

        long spanMillis = 0;
        long pause = backoff;
        for (int attempt = 1; attempt < attempts; attempt++) {
            spanMillis += pause;
            pause *= 2;
        }
        assertTrue(
                spanMillis >= SHORTEST_SPAN_MILLIS,
                "the budget must outlast a rate-limit window: " + attempts + " attempts from " + backoff + " ms span only "
                        + spanMillis + " ms");
        assertTrue(
                spanMillis <= LONGEST_SPAN_MILLIS,
                "a resolution that cannot succeed must still fail within minutes: " + attempts + " attempts from "
                        + backoff + " ms span " + spanMillis + " ms");
    }

    @Test
    void theRunningGradleReadsThePropertiesTheBudgetIsDeclaredUnder() throws IOException {
        String home = System.getProperty(GRADLE_HOME, "");
        assertTrue(!home.isBlank(), "the plain test task names the running Gradle's home through " + GRADLE_HOME);
        List<Path> jars;
        try (Stream<Path> found = Files.walk(Paths.get(home, "lib"))) {
            jars = found.filter(path -> path.toString().endsWith(".jar")).sorted().toList();
        }
        assertTrue(!jars.isEmpty(), "the Gradle installation at " + home + " carries its libraries under lib");

        for (String property : List.of(ATTEMPTS, INITIAL_BACKOFF_MILLIS)) {
            assertTrue(
                    someClassNames(jars, property),
                    "the Gradle at " + home + " reads no property named " + property
                            + ": the retry budget in gradle.properties is inert, so find the name this version reads");
        }
    }

    @Test
    void editingOnlyTheBudgetReRunsTheSuiteInsteadOfReusingACachedVerdict() throws IOException {
        String build = Files.readString(Paths.get("build.gradle.kts"), StandardCharsets.UTF_8);
        String testTask = TestTaskIndependenceTest.everyConfigurationBlock(
                build, TestTaskIndependenceTest.TEST_TASK);

        assertTrue(
                testTask.contains("inputs.file(\"gradle.properties\")"),
                "the plain test task must declare gradle.properties as an input: this gate reads the budget from that"
                        + " file at test time, and a budget edited alone must re-run the suite, never pass on a cached verdict");
    }

    /** The integer a {@code systemProp.} entry declares, refusing a missing or non-numeric one. */
    private static int budgeted(Properties settings, String property) {
        String declared = settings.getProperty("systemProp." + property);
        assertTrue(declared != null, "gradle.properties must declare systemProp." + property);
        int value;
        try {
            value = Integer.parseInt(declared.strip());
        } catch (NumberFormatException notANumber) {
            throw new AssertionError("systemProp." + property + " is not a whole number: " + declared, notANumber);
        }
        assertTrue(value > 0, "systemProp." + property + " must be positive: " + declared);
        return value;
    }

    /** Whether any class in the given libraries carries {@code text} in its constant pool. */
    private static boolean someClassNames(List<Path> jars, String text) throws IOException {
        byte[] needle = text.getBytes(StandardCharsets.UTF_8);
        for (Path jar : jars) {
            try (ZipFile library = new ZipFile(jar.toFile())) {
                for (ZipEntry entry : Collections.list(library.entries())) {
                    if (!entry.getName().endsWith(".class")) {
                        continue;
                    }
                    try (InputStream bytes = library.getInputStream(entry)) {
                        if (contains(bytes.readAllBytes(), needle)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int from = 0; from + needle.length <= haystack.length; from++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (haystack[from + offset] != needle[offset]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}

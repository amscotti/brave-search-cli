package io.amscotti.bravesearch.adapter.cli.option;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.application.port.out.GoggleFileSource;
import io.amscotti.bravesearch.domain.error.UsageValidationError;
import io.amscotti.bravesearch.domain.goggles.Goggle;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/**
 * Grammar and strategy arbitration of the goggle options: the four spellings parse
 * repeatably, the three input strategies — direct values, files, and the site shortcuts
 * that mix freely among themselves — are mutually exclusive, each strategy alone holds
 * the upstream maximum of three goggles, and every rejection is a typed usage failure
 * that names counts and never content.
 */
final class GoggleOptionsTest {

    /** The file seam as a recording stub: loads succeed with inert definitions. */
    private static final class RecordingFileSource implements GoggleFileSource {
        final AtomicInteger loads = new AtomicInteger();

        @Override
        public LoadedGoggle load(Path path) {
            loads.incrementAndGet();
            return new LoadedGoggle("! loaded from " + path.getFileName(), 24);
        }
    }

    @Command(name = "fixture", description = "Option-parsing fixture.")
    static final class Fixture implements Runnable {
        @Mixin
        GoggleOptions goggles;

        @Override
        public void run() {
            // parsing is the fixture's whole job
        }
    }

    @Test
    void noGoggleOptionCompilesToNoGoggle() {
        GoggleOptions parsed = parse(new RecordingFileSource());

        assertEquals(List.of(), parsed.compileGoggles());
    }

    @Test
    void urlReferencesCompileInTheirGivenOrder() {
        GoggleOptions parsed = parse(source(), "--goggle", "https://example.com/one", "--goggle", "http://example.org/two");

        assertEquals(
                List.of(
                        new Goggle.UrlReference("https://example.com/one"),
                        new Goggle.UrlReference("http://example.org/two")),
                parsed.compileGoggles());
    }

    @Test
    void anInlineGoggleValueCompilesAsAValidatedDefinition() {
        GoggleOptions parsed = parse(source(), "--goggle", "!name: local\n+site:example.com");

        assertEquals(List.of(new Goggle.Inline("!name: local\n+site:example.com")), parsed.compileGoggles());
    }

    @Test
    void anInvalidInlineGoggleValueIsAUsageRejection() {
        GoggleOptions parsed = parse(source(), "--goggle", "+site:example.com\u0000");

        assertThrows(UsageValidationError.class, parsed::compileGoggles);
    }

    @Test
    void atMostThreeDirectGogglesAreAccepted() {
        GoggleOptions four = parse(
                source(),
                "--goggle",
                "https://example.com/one",
                "--goggle",
                "https://example.com/two",
                "--goggle",
                "https://example.com/three",
                "--goggle",
                "https://example.com/four");

        UsageValidationError rejected = assertThrows(UsageValidationError.class, four::compileGoggles);

        assertTrue(rejected.getMessage().contains("4"), "the rejection names the count: " + rejected.getMessage());
        assertTrue(rejected.getMessage().contains("3"), "the rejection names the maximum: " + rejected.getMessage());
    }

    @Test
    void atMostThreeGoggleFilesAreAccepted() {
        RecordingFileSource files = new RecordingFileSource();
        GoggleOptions four = parse(
                files,
                "--goggle-file",
                "/tmp/one.goggle",
                "--goggle-file",
                "/tmp/two.goggle",
                "--goggle-file",
                "/tmp/three.goggle",
                "--goggle-file",
                "/tmp/four.goggle");

        assertThrows(UsageValidationError.class, four::compileGoggles);
        assertEquals(0, files.loads.get(), "the count rejection precedes every file load");
    }

    @Test
    void goggleFilesLoadAndCompileAsInlineDefinitions() {
        RecordingFileSource files = new RecordingFileSource();
        GoggleOptions parsed = parse(files, "--goggle-file", "/tmp/a.goggle", "--goggle-file", "/tmp/b.goggle");

        List<Goggle> compiled = parsed.compileGoggles();

        assertEquals(2, compiled.size());
        assertEquals("! loaded from a.goggle", ((Goggle.Inline) compiled.get(0)).definition());
        assertEquals("! loaded from b.goggle", ((Goggle.Inline) compiled.get(1)).definition());
        assertEquals(2, files.loads.get());
        assertEquals(
                List.of(
                        new GoggleFileSource.LoadedFile(java.nio.file.Path.of("/tmp/a.goggle"), 24),
                        new GoggleFileSource.LoadedFile(java.nio.file.Path.of("/tmp/b.goggle"), 24)),
                parsed.goggleFileReports(),
                "each successful load reports its source path and byte length, content-free");
    }

    @Test
    void noFileStrategyMeansNoFileReports() {
        GoggleOptions parsed = parse(source(), "--goggle", "https://example.com/one");
        parsed.compileGoggles();

        assertEquals(List.of(), parsed.goggleFileReports(), "only the file strategy produces load reports");
    }

    @Test
    void includeAndExcludeSitesCompileTogetherAsOneStrategy() {
        GoggleOptions parsed = parse(
                source(), "--include-site", "example.com", "--exclude-site", "spam.example", "--include-site", "a.example");

        assertEquals(
                List.of(new Goggle.Inline("+site:example.com\n+site:a.example\n-site:spam.example")),
                parsed.compileGoggles());
    }

    @Test
    void anInjectionAttemptAgainstASiteShortcutIsAUsageRejection() {
        GoggleOptions parsed = parse(source(), "--include-site", "example.com$strict");

        assertThrows(UsageValidationError.class, parsed::compileGoggles);
    }

    @Test
    void theInputStrategiesAreMutuallyExclusive() {
        String[][] conflicting = {
            {"--goggle", "https://a.example", "--goggle-file", "/tmp/a.goggle"},
            {"--goggle", "https://a.example", "--include-site", "a.example"},
            {"--goggle", "https://a.example", "--exclude-site", "a.example"},
            {"--goggle-file", "/tmp/a.goggle", "--include-site", "a.example"},
            {"--goggle-file", "/tmp/a.goggle", "--exclude-site", "a.example"},
            {
                "--goggle",
                "https://a.example",
                "--goggle-file",
                "/tmp/a.goggle",
                "--include-site",
                "a.example",
                "--exclude-site",
                "b.example"
            },
        };

        for (String[] argv : conflicting) {
            GoggleOptions parsed = parse(source(), argv);
            assertThrows(
                    UsageValidationError.class,
                    parsed::compileGoggles,
                    "the strategies must be exclusive: " + String.join(" ", argv));
        }
    }

    @Test
    void theExclusivityRejectionNamesTheStrategiesActuallySupplied() {
        GoggleOptions directAndSites =
                parse(source(), "--goggle", "https://a.example", "--include-site", "a.example");

        UsageValidationError rejected = assertThrows(UsageValidationError.class, directAndSites::compileGoggles);

        assertTrue(
                rejected.getMessage().contains("--goggle"),
                "the rejection names the supplied direct strategy: " + rejected.getMessage());
        assertTrue(
                rejected.getMessage().contains("--include-site/--exclude-site"),
                "the rejection names the supplied site strategy: " + rejected.getMessage());
        assertFalse(
                rejected.getMessage().contains("--goggle-file"),
                "the rejection never names a strategy the user did not supply: " + rejected.getMessage());

        GoggleOptions filesAndSites =
                parse(source(), "--goggle-file", "/tmp/a.goggle", "--exclude-site", "a.example");
        UsageValidationError fileRejected = assertThrows(UsageValidationError.class, filesAndSites::compileGoggles);
        assertTrue(
                fileRejected
                        .getMessage()
                        .startsWith("--goggle-file, --include-site/--exclude-site are mutually exclusive"),
                "the bare --goggle spelling stays absent when only files and sites conflicted: "
                        + fileRejected.getMessage());
    }

    @Test
    void exclusivityIsArbitratedBeforeAnyFileIsTouched() {
        RecordingFileSource files = new RecordingFileSource();
        GoggleOptions parsed = parse(files, "--goggle", "https://a.example", "--goggle-file", "/tmp/a.goggle");

        assertThrows(UsageValidationError.class, parsed::compileGoggles);
        assertEquals(0, files.loads.get(), "the strategy arbitration precedes every file load");
    }

    private static RecordingFileSource source() {
        return new RecordingFileSource();
    }

    private static GoggleOptions parse(GoggleFileSource files, String... argv) {
        Fixture fixture = new Fixture();
        fixture.goggles = new GoggleOptions(files);
        CommandLine commandLine = new CommandLine(fixture);
        StrictOptionParsing.apply(commandLine);
        int exitCode = commandLine.execute(argv);
        assertEquals(0, exitCode, "the fixture parses successfully: " + String.join(" ", argv));
        assertInstanceOf(GoggleOptions.class, fixture.goggles);
        return fixture.goggles;
    }
}

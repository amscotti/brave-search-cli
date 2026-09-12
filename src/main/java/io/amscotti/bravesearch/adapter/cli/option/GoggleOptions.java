package io.amscotti.bravesearch.adapter.cli.option;

import io.amscotti.bravesearch.adapter.cli.presentation.DiagnosticsSink;
import io.amscotti.bravesearch.application.port.out.GoggleFileSource;
import io.amscotti.bravesearch.domain.error.UsageValidationError;
import io.amscotti.bravesearch.domain.goggles.Goggle;
import io.amscotti.bravesearch.domain.goggles.GoggleCompiler;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.request.WebSearchRequest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import picocli.CommandLine.Option;

/**
 * The goggle input options of every endpoint that documents Goggles: the repeatable
 * {@code --goggle <url-or-inline>} and {@code --goggle-file <path>}, and the site
 * shortcuts {@code --include-site <domain>} and {@code --exclude-site <domain>}, which
 * mix freely among themselves and compile together into one inline Goggle.
 *
 * <p>Exactly one input strategy may appear per invocation — direct values, files, or
 * site shortcuts — and each strategy alone holds the upstream maximum of three Goggles
 * ({@link WebSearchRequest#MAX_GOGGLES}); {@link #compileGoggles()} arbitrates both rules
 * before a single file is read and before any dispatch can happen. File access arrives
 * through the injected {@link GoggleFileSource} port, so this option group never touches
 * the filesystem itself and the adapters never depend on each other. Every rejection
 * names the rule, the counts, and at most a path — never goggle content, never a site
 * value.
 *
 * <p>The instance is constructed and injected by a composition root, never by the
 * commands reading it, matching the shared-option mixin shape of {@link
 * CommonSearchOptions}. A command whose endpoint documents no Goggles does not mix this
 * instance in and rejects the spellings as unknown options.
 */
public final class GoggleOptions {

    @Option(
            names = "--goggle",
            paramLabel = "<url-or-inline>",
            description = "Goggle as an absolute http(s) URL or an inline definition; repeatable, at most 3.")
    List<String> goggles = new ArrayList<>();

    @Option(
            names = "--goggle-file",
            paramLabel = "<path>",
            description = "Goggle read from a regular UTF-8 file of at most 2 MiB; repeatable, at most 3.")
    List<Path> goggleFiles = new ArrayList<>();

    @Option(
            names = "--include-site",
            paramLabel = "<domain>",
            description = "Compile an include-site rule for one IDNA domain; repeatable, mixes with --exclude-site.")
    List<String> includeSites = new ArrayList<>();

    @Option(
            names = "--exclude-site",
            paramLabel = "<domain>",
            description = "Compile an exclude-site rule for one IDNA domain; repeatable, mixes with --include-site.")
    List<String> excludeSites = new ArrayList<>();

    private final GoggleFileSource files;

    /** The successful loads of the last {@link #compileGoggles()} call, in given order. */
    private List<GoggleFileSource.LoadedFile> goggleFileReports = List.of();

    /** @param files the bounded local-file seam behind {@code --goggle-file} */
    public GoggleOptions(GoggleFileSource files) {
        this.files = Objects.requireNonNull(files, "files");
    }

    /**
     * The successful goggle-file loads of the last compilation: one report per file, carrying
     * its source path and byte length and never its content, so a diagnostics channel can
     * report what was loaded without touching user-owned material.
     */
    public List<GoggleFileSource.LoadedFile> goggleFileReports() {
        return goggleFileReports;
    }

    /**
     * The verbose goggle-file report: one diagnostics line per loaded file, naming only its
     * source path and byte length — the content-free debug shape the contract fixes for
     * file-derived goggles. Without {@code --verbose} nothing is reported.
     *
     * @param command the canonical command name the report lines start with
     */
    public void reportLoadedFiles(String command, OutputRequest output, DiagnosticsSink diagnostics) {
        if (!output.verbose()) {
            return;
        }
        for (GoggleFileSource.LoadedFile report : goggleFileReports()) {
            diagnostics.emit(command + ": loaded goggle file " + report.path() + " (" + report.byteLength() + " bytes)");
        }
    }

    /**
     * Compiles the chosen strategy into its goggles.
     *
     * @throws UsageValidationError when two strategies appear together, when a strategy
     *     exceeds the upstream maximum of three goggles, or when any value or file breaks
     *     a documented rule
     */
    public List<Goggle> compileGoggles() {
        boolean directValues = !goggles.isEmpty();
        boolean fileValues = !goggleFiles.isEmpty();
        boolean siteShortcuts = !includeSites.isEmpty() || !excludeSites.isEmpty();
        int strategies = (directValues ? 1 : 0) + (fileValues ? 1 : 0) + (siteShortcuts ? 1 : 0);
        if (strategies > 1) {
            throw new UsageValidationError(suppliedStrategies(directValues, fileValues, siteShortcuts)
                    + " are mutually exclusive input strategies");
        }
        if (directValues) {
            requireWithinMaximum("--goggle", goggles.size());
            return goggles.stream().map(GoggleCompiler::fromUrlOrInline).toList();
        }
        if (fileValues) {
            requireWithinMaximum("--goggle-file", goggleFiles.size());
            List<Goggle> compiled = new ArrayList<>(goggleFiles.size());
            List<GoggleFileSource.LoadedFile> reports = new ArrayList<>(goggleFiles.size());
            for (Path path : goggleFiles) {
                GoggleFileSource.LoadedGoggle loaded = files.load(path);
                compiled.add(GoggleCompiler.compileInline(loaded.definition()));
                reports.add(new GoggleFileSource.LoadedFile(path, loaded.byteLength()));
            }
            goggleFileReports = List.copyOf(reports);
            return List.copyOf(compiled);
        }
        if (siteShortcuts) {
            return List.<Goggle>of(GoggleCompiler.compileSiteShortcuts(includeSites, excludeSites));
        }
        return List.of();
    }

    /**
     * The strategy names the user actually supplied, so the exclusivity rejection never
     * teaches a spelling the invocation never used.
     */
    private static String suppliedStrategies(boolean directValues, boolean fileValues, boolean siteShortcuts) {
        List<String> supplied = new ArrayList<>(3);
        if (directValues) {
            supplied.add("--goggle");
        }
        if (fileValues) {
            supplied.add("--goggle-file");
        }
        if (siteShortcuts) {
            supplied.add("--include-site/--exclude-site");
        }
        return String.join(", ", supplied);
    }

    private static void requireWithinMaximum(String optionName, int supplied) {
        if (supplied > WebSearchRequest.MAX_GOGGLES) {
            throw new UsageValidationError(
                    optionName + " was supplied " + supplied + " times; upstream accepts at most "
                            + WebSearchRequest.MAX_GOGGLES + " goggles");
        }
    }
}

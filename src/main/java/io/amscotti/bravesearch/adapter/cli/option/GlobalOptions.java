package io.amscotti.bravesearch.adapter.cli.option;

import io.amscotti.bravesearch.adapter.cli.presentation.TerminalDetector;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import java.util.Objects;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

/**
 * The all-command global options: {@code --verbose}, {@code --quiet}, {@code --no-color},
 * {@code -h/--help}, and {@code -V/--version}, registered on the root command with inherited
 * scope so they parse anywhere before the end-of-options marker — before or after the
 * subcommand token — into this one shared instance the commands read by reference.
 *
 * <p>The verbosity pair rejects its own contradiction at parse time in both orders, and the
 * help and version flags keep picocli's help semantics while inheriting the same placement.
 * The instance is created by the composition root, never by the commands reading it; the
 * composition root also injects the {@link TerminalDetector} whose terminal decision combines
 * with {@code --no-color} here into the invocation's single color enablement, so no command
 * or presenter ever re-derives it. An instance without a detector — the hermetic shape — can
 * never enable color, because an unknown terminal state is exactly the uncertainty that
 * disables.
 */
public final class GlobalOptions {

    @Spec
    CommandSpec spec;

    private final TerminalDetector terminal;

    private boolean verbose;

    private boolean quiet;

    private boolean noColor;

    /** The hermetic shape: no terminal was detected, so color can never be enabled. */
    public GlobalOptions() {
        this.terminal = null;
    }

    /** @param terminal the process terminal detector the color decision consults */
    public GlobalOptions(TerminalDetector terminal) {
        this.terminal = Objects.requireNonNull(terminal, "terminal");
    }

    public boolean verbose() {
        return verbose;
    }

    public boolean quiet() {
        return quiet;
    }

    public boolean noColor() {
        return noColor;
    }

    /**
     * Whether this invocation's human stdout documents may carry ANSI styling: a proven
     * color-capable terminal and no {@code --no-color} flag. Every disabler inside the
     * detection and the flag here must agree before a single escape byte is written.
     */
    public boolean colorEnabled() {
        return !noColor && terminal != null && terminal.interactive();
    }

    /**
     * The render width this invocation's human documents wrap to: the width the terminal
     * detector observed — an exported, sane {@code COLUMNS} — or the default of 100. The
     * hermetic shape without a detector keeps the default, because no terminal was
     * observed at all.
     */
    public int outputWidth() {
        return terminal == null ? OutputRequest.DEFAULT_WIDTH : terminal.width();
    }

    @Option(
            names = "--verbose",
            scope = CommandLine.ScopeType.INHERIT,
            description = "Print diagnostics and resolved non-secret metadata to stderr.")
    void setVerbose(boolean verbose) {
        this.verbose = verbose;
        requireNotBothVerboseAndQuiet();
    }

    @Option(
            names = "--quiet",
            scope = CommandLine.ScopeType.INHERIT,
            description = "Suppress optional diagnostics, never errors.")
    void setQuiet(boolean quiet) {
        this.quiet = quiet;
        requireNotBothVerboseAndQuiet();
    }

    @Option(
            names = "--no-color",
            scope = CommandLine.ScopeType.INHERIT,
            description = "Disable ANSI color unconditionally; NO_COLOR disables it too.")
    void setNoColor(boolean noColor) {
        this.noColor = noColor;
    }

    @Option(
            names = {"-h", "--help"},
            scope = CommandLine.ScopeType.INHERIT,
            usageHelp = true,
            description = "Show this help message and exit.")
    void setHelp(boolean help) {
        // picocli owns the help behavior; the flag only needs the inherited placement
    }

    @Option(
            names = {"-V", "--version"},
            scope = CommandLine.ScopeType.INHERIT,
            versionHelp = true,
            description = "Print version information to stdout and exit.")
    void setVersion(boolean version) {
        // picocli owns the version behavior; the flag only needs the inherited placement
    }

    private void requireNotBothVerboseAndQuiet() {
        if (verbose && quiet) {
            throw new ParameterException(spec.commandLine(), "--verbose and --quiet are mutually exclusive");
        }
    }
}
